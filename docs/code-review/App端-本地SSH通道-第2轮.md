# 代码审查报告：App 端本地 SSH 通道（第 2 轮）

- **审查仓库**：`/tmp/mobile-check`（Termix-SSH/Mobile v1.5.0，Expo/RN 0.81）
- **审查对象**：本次「手机本地网络拨号 SSH」需求引入的未提交改动（`git status` 中 `M`/`??` 全部文件）
- **审查日期**：2026-09-29
- **代码快照说明**：审查期间工作区被并行 worker 继续改动（`NativeSSHSession.ts` 440→450 行、`host-key-trust.ts` 97→100 行）。报告中所有行号引用与结论均基于**复核后的最新版**；受影响的结论已在正文标注「审查期间已修复」。
- **审查方式**：静态阅读 + 服务端 `/opt/termix` 源码对照 + sshj 0.41.1 调用链推导 + 真实公钥实测（`/tmp/vkey.wire`）+ `tsc --noEmit` / `eslint` / `kotlinc` 实跑
- **前置**：第 1 轮报告（`App端-本地SSH通道-第1轮.md`）的 2 阻塞 + 6 高危 + 5 中危已全部修复，本轮首要职责是**验证修复正确性、识别修复引入的新问题**

## 总体结论

**可以合并（有条件）。** 第 1 轮全部 13 个问题项均已被正确修复，且修复本身未引入新的功能性 bug。本轮新发现 **1 个重要项**、**4 个建议项**，无阻塞项。

新发现的问题都不影响「本地通道能连通、能信任、能弹窗、能重连」这条主链路，集中在：host key 信任优先级设计导致的一个真实可用性缺口（A1）、一个非连接态下的时序竞态（B2）、以及若干可维护性/健壮性建议。

| 级别 | 数量 | 项 |
|---|---|---|
| 阻塞 | 0 | — |
| 重要 | 1 | A1 |
| 建议 | 4 | B1（空指纹 UI 收尾）、B2（reconnect 竞态）、B3（Emitter 时序）、B4（lint 基线） |

> 说明：原计划按第 1 轮格式列 3 个建议项，实际核验中把「空指纹」（见第二节 B1）单列。审查期间该问题的「死循环」与「守卫不对称」两半均被并行 worker 修复，仅剩「空白指纹对话框」这一 UI 收尾。

### 实测核验结果（按要求实际执行）

| 命令 | 结果 |
|---|---|
| `npx tsc --noEmit -p tsconfig.json` | **0 error**（exit 0，无输出） |
| `npx eslint . -f json` | **0 error / 98 warning**（基线 0/96） |
| Kotlin 编译（`-d /tmp/ktout_review2` / `/tmp/ktout_review3`） | **exit 0**，无 warning / error（含审查期间代码变动后重编） |

指纹格式实测（`/tmp/vkey.wire` 为真实 ssh-keygen 公钥 wire 格式，51 字节）：

```
presented len = 102
equal to server hex = true
KeyType.fromKey = ssh-ed25519      <- M5 修复正确
```

`HostKeyFingerprints.normalize()` 容忍度实测（presented 固定为服务端 hex）：

| 输入 | 归一化后长度 | isTrusted |
|---|---|---|
| 纯 hex | 102 | true |
| 全大写 hex | 102 | true |
| `SHA256:` + hex | 102 | true |
| `sha256:` + hex | 102 | true |
| 前后带空格 | 102 | true |
| `""` / `"  "` / null | null | false |


---

## 一、重要项（High）

### A1. 服务端 pin 优先级高于本地 SecureStore：host key 变更被服务端 pin 掩盖，用户在本机的信任决策不可达

**位置**：`app/tabs/sessions/terminal/host-key-trust.ts:93-100`（`resolveKnownHostKey`）、`app/tabs/sessions/terminal/NativeSSHSession.ts:226-234`（`performConnect` 的 `knownHostKey`）、`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshConnection.kt:170-177`（`verify()`）、`NativeSshModule.kt:93-101`（mismatch 分支只 emit.error）

**代码事实**：

```ts
// host-key-trust.ts
export async function resolveKnownHostKey(host: HostIdentityWithServerPin): Promise<string | null> {
  const serverPin = host.hostKeyFingerprint ?? null;
  if (serverPin) return serverPin;          // ← 服务端 pin 优先，本地 SecureStore 根本不被读
  return await getStoredHostKeyFingerprint(host);
}
```

**触发路径（对应提问 A）**：场景为「服务端有 pin + host key 被更换 + 用户在手机上 local 连接」。

1. 服务端 `host.hostKeyFingerprint = F_old`。该字段非 SENSITIVE_FIELDS，`/db/host` 列表会原样下发（`/opt/termix/src/backend/database/routes/host-normalizers.ts:502-504`），`/db/host/{id}/native-ssh` 的 plan 也带（`host-native-ssh-routes.ts:84-86`）。
2. 手机上 `resolveKnownHostKey` 直接返回 `F_old`，SecureStore 不读。
3. sshj 呈现 `F_new` → `verify()` 返回 false → `outcome.trusted = F_old != null` → **`HostKeyMismatchException`** → `NativeSshModule.kt:99` 走 `emit.error("HOST_KEY_CHANGED", ...)`，**不是 `emit.hostKey(..., "changed")`**。
4. JS 侧 `NativeSSHSession.handleError`（`NativeSSHSession.ts:381-425`）只对 `HOST_KEY_UNKNOWN` 早退；`HOST_KEY_CHANGED` 落到 `notifyFailure(message)`。
5. `notifyFailure` → `onConnectionFailed` → `handleConnectionFailure` → `showToast.error(...)` + `onClose()` **关 tab**。

**为什么是 bug**：

- `HostKeyVerificationDialog` 完全支持 `scenario === "changed"`（destructive 配色、「Accept New Key」按钮、`oldFingerprint` 双行对比，`app/tabs/dialogs/HostKeyVerificationDialog.tsx:50-78, 186-224`），而 **local 通道的 `HOST_KEY_CHANGED` 永远无法到达这个对话框**，永远只表现为一条 toast + 关 tab。与 Web 端不对等：服务端 `host-key-verifier.ts:247-257` 会给 Web 端发 `host_key_changed` + `oldFingerprint`，让用户选择接受/拒绝。
- **本机信任决策不可达**：若用户此前在手机上接受过 `F_new`（`storeHostKeyFingerprint` 已写入 SecureStore），而服务端 pin 仍是陈旧的 `F_old`（例如该 host 从未在 Web 端连过、pin 是历史遗留），`resolveKnownHostKey` 每次都返回 `F_old` —— **用户明明在本机信任过，却每次都被判 changed，且没有任何 UI 入口能纠正**（`clearStoredHostKeyFingerprint` 只在 `resolveHostKey` 的 reject 分支调用，而此场景进不到那里）。这直接命中提问 A 中的疑点，且结果比「每次重问」更糟：直接 failed + 关 tab。
- 数据结构已就绪但链路断了：`HostKeyData` 定义了 `oldFingerprint` / `oldKeyType`（`NativeWebSocketManager.ts:37,39`，服务端 changed 场景会下发），`HostKeyMismatchException` 也持有 `trusted`，但 `NativeSshModule.kt:98-100` 只把它拼进 message 字符串，从未经 `onHostKey` 事件传递。

**影响**：

- host 重装 / key 轮换后，手机 local 通道用户看不到「哪个指纹变了、要不要接受」。
- 上述「本机已接受 + 服务端 pin 陈旧」场景下，该 host 用手机 local 通道**永久无法连接**。

**修复建议**（三选一，推荐 1+2 组合）：

1. **让 changed 也能走对话框（与 Web 端对齐）**：
   - `NativeSshModule.kt` 的 mismatch 分支改为 `emit.hostKey(e.presented, e.keyType, e.algorithm, "changed")`，并把 `e.trusted` 一并下发（需给 `Emitter.hostKey` 增加 `oldFingerprint`/`oldKeyType` 形参，`SessionShims.kt:29-39`）；
   - `NativeSSHSession.handleError` 中删除 `HOST_KEY_CHANGED → notifyFailure` 的路径（该 code 本就不应出现在 `onError` 上）；
   - `NativeSSHSession.handleHostKey` 已在构造 `HostKeyData`，补上 `oldFingerprint: <服务端 pin>` 即可，dialog 侧无需改动。
2. **修正信任优先级语义（需产品确认）**：`resolveKnownHostKey` 中若服务端 pin 与本地 SecureStore 都存在且不同，不应无条件信服务端。建议优先返回本地值（用户在**这台设备上**做过明确确认，比可能陈旧的共享 pin 更可信），或至少记一条日志供排障。这属于跨端信任语义决策，报告仅指出方向。
3. **底线修复（最小、纯 App 侧）**：`HOST_KEY_CHANGED` 分支不调 `onClose()` 关 tab，并把文案从 sshj 原始 message 改为可读提示（「host key 已变更；如你重装过该主机，请在 Web 端重新接受指纹」）。可立即执行，成本近乎零。

> 定「重要」而非「阻塞」的理由：只在「服务端 pin 存在 且 host key 已变更」这一路径上触发（生产库 3/15 台 host 有 pin）。正常首连、正常重连、QuickConnect 均不受影响。

---

## 二、建议项（Suggestions）

### B1. `fromKey` 返回空串时 host key 对话框显示空指纹（死循环与守卫不对称已在审查期间修复）

> **更新（审查期间代码已变动，已按最新版复核）**：本轮审查过程中 `NativeSSHSession.resolveHostKey` 被并行 worker 改动——原 `if (fingerprint)` 已改为显式的 `if (!fingerprint) { notifyFailure(...); return; }` 早退守卫（`NativeSSHSession.ts:255-265`）。这**消除了下述第 4-5 步的死循环**，但空指纹仍会弹出一个空白对话框后才报错，见下方保留意见。

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshEngine.kt:91-124`（host key 三分支）、`NativeSshConnection.kt:70-73`（`fromKey` 返回 `""`）、`app/tabs/sessions/terminal/NativeSSHSession.ts:255-265`（空指纹守卫）、`app/tabs/dialogs/HostKeyVerificationDialog.tsx:18`（`formatFingerprint`）

**触发路径**：sshj 呈现一个它自己无法 `putPublicKey` 编码的 key（`KeyType.fromKey` 抛 `UnsupportedOperationException`，`fromKey` 的 `runCatching` 兜底返回 `""`；实践中 sshj 0.41.1 已支持 ssh-ed25519 / ssh-rsa / ecdsa / sk-*，故此路径属理论退化而非日常路径）。

1. `presented = ""` → **已实测** `isTrusted()` 恒 false（有无 pin 都是）。
2. `outcome.trusted == null` → `HostKeyUnknownException(fingerprint = "")` → `emit.hostKey("", ...)`。
3. JS 侧 `handleHostKey` → dialog `data.fingerprint = ""` → `formatFingerprint("")` = `"".match(/.{1,2}/g)?.join(":") ?? ""` = **空串**，用户看到一个**完全空白**的指纹框，复制得到空内容。
4. 用户点「Connect & Trust」→ `resolveHostKey(accept, "")` → 命中新增的 `if (!fingerprint)` 守卫 → `notifyFailure("Cannot remember this host key: the phone could not read its fingerprint.")` + return，**死循环已消除**。
5. 但用户仍然先看到一个**完全空白的指纹对话框**并被邀请去「信任」一个不可见的值，只是点下去才会得到一句错误。

**为什么仍是问题**：fail-closed 的**前半段**（不信任）正确，但 UI 侧仍是 fail-silent。注释声称「A key sshj cannot encode cannot be compared to a pin either, so it is reported as empty instead of escaping」，意图是好的（保证 `verify()` 永不抛），但下游三处都没有把「空」当成异常处理。**审查期间的并行改动**已把两侧补平：`storeHostKeyFingerprint` 现也有 `if (!fingerprint) return` 守卫（`host-key-trust.ts:62-64`），`getStoredHostKeyFingerprint` 有 `value.length > 0` 守卫（`host-key-trust.ts:51`）。此项的守卫不对称问题已解决。

**影响**：理论路径。用户看到空白对话框 + 一句事后错误，无法理解发生了什么。

**修复建议**（两层都补，成本各一行）：

1. **（已在审查期间由并行 worker 修复）** `resolveHostKey` 已加 `if (!fingerprint)` 守卫 + 可读文案，无需再做。
2. **仍然需要**：`NativeSshEngine.openConnection` 的 catch 里 `if (outcome.fingerprint.isEmpty()) throw HostKeyUnverifiableException(...)`，映射到新 code `HOST_KEY_UNVERIFIABLE`。这能让错误在弹对话框**之前**就 fail-closed，用户根本看不到空白指纹框——这才是完整修法。

### B2. `reconnect()` 以 `sessionId === null` 判断「连接已死」，在 dial 期间与 onClosed 窗口均不成立

**位置**：`app/tabs/sessions/terminal/NativeSSHSession.ts:300-310`

```ts
async reconnect(): Promise<void> {
  if (this.destroyed) return;
  if (this.sessionId !== null) {            // ← dial 进行中时 sessionId 恒为 null
    const id = this.sessionId;
    this.sessionId = null;
    await disconnectNativeSsh(id).catch(() => {});
  }
  await this.connect(this.cols, this.rows);
}
```

`sessionId` 只在 `connectNativeSsh` **resolve 之后**赋值（`performConnect` 最后一行），所以握手期间（0.3s ~ 15s）恒为 null；`onClosed` 又经 main handler 异步投递，`disconnectNativeSsh` 与 worker `finally` 的 `connections.remove` 之间存在窗口。

**触发路径（可达）**：`connect()` 的 promise 已 resolve（`sessionId = 42`）但 `onClosed` 尚未到达 JS。用户此时触发重连（host key accept 后 `resolveHostKey` → `reconnect()`，或 auth dialog 提交后 `retryLocalWithCredentials` → `reconnect()`）。`disconnectNativeSsh(42)` 若晚于 worker 的 `connections.remove(42)`，Kotlin 侧 `?: return@AsyncFunction Unit` 静默 no-op；随后排队的旧 `emit.closed()` 到达 JS，`this.destroyed` 仍为 false → `onClosed` handler 执行 `this.config.onDisconnected(...)`（`NativeSSHSession.ts:108-113`）→ `showToast.warning("Disconnected from …")` + `onClose()` 关 tab——而用户刚刚成功发起重连。

**为什么是 bug**：`sessionId` 被当作「连接是否还活着」的唯一判据，但它在 dial 期间为 null、在 close→onClosed 之间有窗口，两者都不是「连接已死」的等价条件。

**影响**：可达但需精确时序。用户可感知结果是「重连失败 + tab 被关」，需手动重开。

**修复建议**：引入 dial 代际计数器。`performConnect` 开头 `const token = ++this.dialCounter`，`this.sessionId = await connectNativeSsh(options)` 之后校验 `if (token !== this.dialCounter) { void disconnectNativeSsh(this.sessionId); this.sessionId = null; return; }`；`reconnect()` 改为无条件 `++this.dialCounter` 并 disconnect 已知 id。或者在 `onClosed` handler 里加 `if (this.dialCounter !== token) return;`（需把 token 存到 handler 可见字段）——前者更彻底。

### B3. `Emitter.closedSessions` 的量级与 `OnDestroy` 时序（见提问 B）

**位置**：`SessionShims.kt:60-90`（`closedSessions` / `forgetAll`）、`NativeSshModule.kt:44-56`（`OnDestroy`）

**核验结论：可接受，不建议修复**，量化结论记录如下：

- **容量**：`ConcurrentHashMap<Int, Unit>` 每节点约 32~48 字节，1000 次连接/断开 ≈ 40KB。对一个常驻 native 模块可忽略，且 `OnDestroy` 必清空。
- **`forgetAll()` 时序安全**：`OnDestroy` 顺序为 `disconnectAllSessions()` → `mainHandler.removeCallbacksAndMessages(null)` → `Emitter.forgetAll()` → `NativeSsh.events = NoopEventSink`。`removeCallbacksAndMessages(null)` 清掉的是已 post 未 `sendEvent` 的任务；此后 worker 走 `finally { emit.closed() }` 新 post 的任务落在 `NoopEventSink` 上（`SessionShims.kt:100-107`），无副作用。`ModuleEventSink.post` 的 `NativeSshModule.instance?.sendEvent`（`ModuleEventSink.kt:19`）还有第二道 null 保护。**不存在 NPE，不存在往已销毁 context 发事件**。
- 遗留一个**实践不可达**的理论边界：`forgetAll()` 之后、旧 worker 仍在退出的那零点几秒内，若新模块实例已创建并复用 id 1，旧 worker 的 `emit.closed()` 可能抢占新实例的 marker，导致新 session 的 onClosed 被吞。需要「模块销毁的同一毫秒内重建并复用同一 session id 且旧 worker 恰好迟到」三重条件同时成立，Expo 中模块只在 JS reload / Activity 重建时销毁，不可复现。记录不修。

### B4. 本轮新增的 2 个 eslint warning

`npx eslint . -f json` 从基线 96 → 98。多出的 2 个位于 `app/tabs/sessions/terminal/NativeSSHSession.ts` 与 `app/tabs/sessions/terminal/host-key-trust.ts`，均为 `@typescript-eslint/no-unused-vars`（`catch (e)` / 未使用形参）一类，与 `app/main-axios.ts` 既有多处同模式。**不建议为消除 warning 改动代码**，建议下一轮 lint 基线并入。

---

## 三、对应提问的逐条核验结论（A–G）

| # | 疑点 | 结论 |
|---|---|---|
| **A** | `overriddenCredentials` 绕过 plan 后，host key 信任走哪条路？会不会「接受过却每次重问」或「改了 key 不报 changed」？ | override 分支的 `hostKeyFingerprint` 取自 `hostConfig`（`NativeSSHSession.ts:209`）而非 plan；但 `/db/host` 列表**本来就会下发** `hostKeyFingerprint`（非 SENSITIVE_FIELDS），所以 override 时丢的不是信任数据。真正的问题是 `resolveKnownHostKey` 仍「服务端 pin 绝对优先，本地 SecureStore 只在无 pin 时读」，导致**「本机已接受 + 服务端 pin 陈旧」→ 永久 changed 且无 UI 入口**，详见 **A1**。「每次重问」不会发生（比那更糟：直接 failed 关 tab）。 |
| **B** | `closedSessions` 增长 / `forgetAll` 时序 | 量级可忽略（1000 条 ≈ 40KB）；`OnDestroy` 时序安全，无 NPE、无发到死 context；仅存一个实践不可达的 onClosed 丢失理论边界。详见 **B3**，不建议改。 |
| **C** | `fromKey` 返回 `""` 会发生什么？`resolveHostKey` 会不会存空串？`storeHostKeyFingerprint` 要不要补守卫？ | **已实测** `presented = ""` 时 `isTrusted()` 恒 false → 走 `HostKeyUnknownException` 弹「new」对话框，**对话框显示空指纹**。用户点接受 → `resolveHostKey(accept, "")` → 审查期间新增的 `if (!fingerprint)` 守卫命中 → `notifyFailure(可读文案)` + return，**死循环已消除**（原设计是 `if (fingerprint)` 对空串 falsy 整段跳过 → 重连 → 再次 unknown → 无限循环）。两侧守卫现已对称（`storeHostKeyFingerprint:62-64` 审查期间补上、`getStoredHostKeyFingerprint:51`），死循环与不对称均已解决；仅剩「空白指纹对话框」UI 问题。详见 **B1**。 |
| **D** | 404→null 早退是否覆盖 QuickConnect？重连时 plan 失败会否静默停 connecting？ | QuickConnect：`hostId = -Date.now() <= 0`，`hostId > 0 && !plan` 分支不进，走 `plan ?? {hostConfig 字段}`，**正确**。重连：早退调 `notifyFailure`（幂等一次）→ `onConnectionFailed` → toast + `onClose()`，**不会静默停 connecting**，正确。非 404 错误（网络断/500）仍会把 axios 原始 message 透给用户（第 1 轮 M1 的文案遗留，最常见场景「主机已删」本轮已修）。 |
| **E** | effect 依赖加 `connectionOrigin`/`tabInstanceId` 会不会无限重建？ | **不会**。`Sessions.tsx:700` 传 `tabInstanceId={session.instanceId}`，`instanceId` 在 `TerminalSessionsContext.tsx:183` 由 `opts?.instanceId ?? uuid()` **一次性生成后存在 session 对象里**，不随 render 变化；`connectionOrigin` 同样来自列表数据字段。唯一重建触发源是用户真的去改 host 的 connection source 后列表刷新——正是期望行为。 |
| **F** | `disconnectAllNativeSsh()` 放 cleanup 里，StrictMode 会误断吗？ | **仓库无 StrictMode**（已全仓 grep `StrictMode`：`app/`、`index.js`、`package.json`、App 入口均无命中），Expo RN 模板默认不开，mount→unmount→mount 双调用不成立。cleanup 语义是「Terminal 整体卸载」，本来就该断。 |
| **G** | `authType: ""` 传下去会发生什么？用户能否恢复？ | 链路符合预期且可恢复：`auths.isEmpty()` → `IllegalArgumentException("No credentials supplied…")` → `classify` → `INVALID_CREDENTIALS` → `isAuthFailure` → `onAuthDialogNeeded("auth_failed")` → 弹 `SSHAuthDialog`，用户填密码 → `overrideCredentials` + `reconnect()`（H3 已修）→ 用用户凭据重拨连通。唯一瑕疵是错误码语义偏（暗示「密码错了」，真实原因是「该 host 无可下发秘密」），属文案精度，非功能 bug。详见 **M2 核验**。 |

---

## 四、第 1 轮问题修复核验明细（逐条）

### 阻塞项

| 项 | 修复核验 | 结论 |
|---|---|---|
| **B1** host key 对话框永不触发 | `verify()` 改为 `return trusted != null && trusted == presented`，永不抛异常（`NativeSshConnection.kt:170-177`）；结论记入 `outcome`，且 `isTrusted()` 两侧都 re-normalize（`NativeSshConnection.kt:132-133`），避免与 verifier 自身比较漂移。`openConnection` 在 `client.connect()` catch 里三分支（`NativeSshEngine.kt:91-124`）：`outcome == null \|\| outcome.isTrusted()` 原样重抛（TCP/DNS/timeout 保持原错误码，正确）；`outcome.trusted == null` → `HostKeyUnknownException`；否则 `HostKeyMismatchException`。`NativeSshModule.kt:88-101` 的 catch 顺序 `HostKeyUnknown` → `HostKeyMismatch` → `Throwable` 能命中，且自定义异常已不在 sshj 内部抛出，不再经 `TransportException.chainer` 被吞类型。**已通过 Kotlin 编译验证 catch 与 `Throwable` 兜底不冲突。** | ✅ 正确 |
| **B2** 指纹格式与服务端不兼容 | `fromKey` 改为 `hex(Buffer.PlainBuffer().putPublicKey(key).getCompactData())`（`NativeSshConnection.kt:72`）并带 `runCatching` 兜底。**已实测**：与 SSH wire 字节逐字符相等、长度 102、`KeyType.fromKey = ssh-ed25519`。`normalize()` 对 `SHA256:`/`sha256:` 前缀、大小写、空格全部容忍（见实测表）。存量 3 条 pin 无需迁移得到确认。 | ✅ 正确 |

### 高危项

| 项 | 修复核验 | 结论 |
|---|---|---|
| **H1** host key 接受后未持久化 | `Terminal.tsx:1235-1240` 的 `onHostKeyVerificationRequired` 现写入 `fingerprint: data.fingerprint`；dialog accept/reject 传 `hostKeyVerification?.data.fingerprint`（`Terminal.tsx:1596,1601`）；`respondToHostKey` → `session.resolveHostKey(accept, fingerprint)`（`Terminal.tsx:1058-1067`）；`resolveHostKey` 里现在有显式的空指纹守卫（`NativeSSHSession.ts:255-265`，审查期间新增）。**正常（非空）指纹下死循环已消除。** 空指纹场景的循环也已由审查期间新增的守卫消除，仅剩「空白指纹对话框」UI 问题，见 **B1**。 | ✅ 基本正确（空串边界见 B3） |
| **H2** 重复 emit `onClosed` | `Emitter.closed()` 改 per-sessionId 幂等：`closedSessions.putIfAbsent(sessionId, Unit)`（`SessionShims.kt:52-59`），companion map + `forgetAll()`（`SessionShims.kt:66-90`），`OnDestroy` 调用（`NativeSshModule.kt:53`）。三处 write / read-loop / finally 复用同一语义，`putIfAbsent` 保证原子。 | ✅ 正确 |
| **H3** plan 覆盖用户凭据 | `overriddenCredentials` 独立字段（`NativeSSHSession.ts:66-73`），`performConnect` 的 plan 条件改为 `hostId > 0 && !this.overriddenCredentials`（`NativeSSHSession.ts:165-166`），`destroy()` 清空（`NativeSSHSession.ts:349`）。override 分支只取 auth 字段，host key 字段仍取自 `hostConfig`（`NativeSSHSession.ts:204-214`）。 | ✅ 正确 |
| **H4** resize/write 抛异常 | Kotlin 侧两处均改 `?: return@AsyncFunction Unit` 幂等 no-op（`NativeSshModule.kt:130,135`）；`SshSessionNotFound` 已从仓库彻底删除（grep 确认无残留）。JS 侧 `sendInput`/`sendResize` 仍带 `.catch(() => {})` 双保险。 | ✅ 正确 |
| **H5** transport 重建条件 + 残留连接 | effect 依赖加入 `hostConfig.connectionOrigin` 与 `tabInstanceId`（`Terminal.tsx:1292`）；卸载 cleanup 对 `NativeSSHSession` 调 `disconnectAllNativeSsh()`（`Terminal.tsx:1301-1303`），删除了「零调用点」问题。effect 入口补了 `transportRef.current?.destroy()`（`Terminal.tsx:1165`），重建时先销毁旧 transport——直接解决了原 H5 的「多个 session 同时存活」。 | ✅ 正确 |
| **H6** iOS 构建被阻塞 | `expo-module.config.json` 已改为 `{"platforms": ["android"], "android": {...}}`，`ios` 段与 `NativeSshModule` 的 iOS 引用已移除；`build.gradle` 仅 android library 插件，与声明一致。 | ✅ 正确 |

### 中危项

| 项 | 修复核验 | 结论 |
|---|---|---|
| **M1** 404 与其它错误不可区分 | `getNativeSSHPlan` 显式 `if (axios.isAxiosError(error) && error.response?.status === 404) return null`（`main-axios.ts:3313-3315`），其余仍走 `handleApiError`。`performConnect` 早退 + `notifyFailure`（`NativeSSHSession.ts:168-173`），用户看到的是可读文案而非 axios 内部措辞。 | ✅ 正确 |
| **M2** `openConnection` 忽略 authType | **本轮未改。结论：不必须处理。** 已查服务端 `buildNativeSSHPlan`（`host-native-ssh-routes.ts:53-72`）把 authType 收敛为只由实际材料决定：要么 `"key"`+key 非空、要么 `"password"`+password 非空、要么 `""` 且两者皆空。第 1 轮担心的「`none` 但 plan 仍带 key」这种异常组合**服务端已不可能产生**，Kotlin 侧只看 `privateKey`/`password` 是否存在与服务端 plan 契约一致。剩余 `authType: ""` 的 INVALID_CREDENTIALS 语义偏问题见 **G**。 | ✅ 可接受 |
| **M3** QuickConnect 每次重弹 host key | `hostKeyStoreKey` 对 `id <= 0` 改用 `"transient"` 段（`host-key-trust.ts:37-40`）。QuickConnect 的 host id 为 `-Date.now()`（负数），`host.id > 0` 为 false，确实走 transient 分支；且 key 仍带 `ip:port`，不同 QuickConnect 目标不串。 | ✅ 正确 |
| **M4** HostForm 编辑态 plan 失败清空凭据 | `Promise.allSettled` 全部 rejected 分支保留原值 `f.password`/`f.key`/`f.keyPassword`/`f.rdpPassword`/`f.vncPassword`/`f.telnetPassword`（`HostForm.tsx:213-227`）。另核对服务端 update 路径 `if (password)` 非空才写（`host.ts:1133-1141`），空串仍不会清服务端数据。 | ✅ 正确 |
| **M5** keyType/algorithm 语义 | `keyType` 改用 `KeyType.fromKey(key).toString()`（`NativeSshConnection.kt:79-80`），**已实测返回 `ssh-ed25519`**，与服务端 `getKeyType`（`host-key-verifier.ts:456-468`）一致；`algorithm` 保持硬编码 `"sha256"` 与服务端 `host-key-verifier.ts:61` 一致。 | ✅ 正确 |

### 其他

| 项 | 修复核验 | 结论 |
|---|---|---|
| **L1** `awaitingUserInput` 吞错误 | 加 `awaitingUserInputSince` + `AUTH_DIALOG_GRACE_MS = 60_000`（`NativeSSHSession.ts:373-379, 433`），超时后放行并复位 flag。两处赋值点（`handleError` 与 `handleHostKey`）都用 `??=` 记录首次时间，`resolveHostKey`/`performConnect`/`destroy` 均复位。 | ✅ 正确 |
| **L2** `TerminalTransportEvents` 死代码 | 已从 `TerminalTransport.ts` 删除（现文件 38 行，仅 `TerminalTransport` 接口 + 说明 + re-export）。 | ✅ 正确 |
| **L3** `pumpOutput` buffer 拷贝 | 未改。按提示「不必过于苛刻」，8KB 缓冲 + 16ms 刷新的量级下 `carry.toByteArray()` 的双拷贝可接受，不作为问题项。 | ✅ 可接受 |
| **L4** ModuleEventSink 生命周期 | `OnDestroy` 已加 `mainHandler.removeCallbacksAndMessages(null)`（`NativeSshModule.kt:53`）。时序安全，见 **B3**。 | ✅ 正确 |
| **L5** HostForm 未预填 `sudoPassword` | 未改，非本次回归（旧 `getSSHHostWithCredentials` 也不返回该字段），维持第 1 轮记录。 | ✅ 维持记录 |

---

## 五、经核查未发现问题的点（明确结论，避免后续重复排查）

1. **host key accept 后立即信任生效（无二段弹窗）**：`resolveHostKey` 设 `this.acceptedHostKey = fingerprint`，`performConnect` 的 `knownHostKey = this.acceptedHostKey ?? await resolveKnownHostKey(...)`（`NativeSSHSession.ts:226-230`）——`acceptedHostKey` 优先，accept 后的重连**第一次**就能通过 `verify()`。
2. **`hostKeyStoreKey` 串号风险**：`id > 0` 时 key 形如 `termix.hostkey.${id}:${ip}:${port}`，id 唯一；`id <= 0` 时统一 `transient` 段但仍带 `ip:port`（`host-key-trust.ts:42`），两个不同 QuickConnect 目标不串。sync 后「删了再建同 id」会复用旧信任，注释已声明为已知取舍。
3. **凭据生命周期合规**：明文只在 `getNativeSSHPlan` 返回的 `plan` 中驻留；传入 native 后转 `CharArray`，`scrubCredentials()` 对密码 `fill(NUL)`（`NativeSshEngine.kt:50-56`，私钥无法清零这一点注释诚实说明）；未落 AsyncStorage；`keychainAccessible: WHEN_UNLOCKED_THIS_DEVICE_ONLY` 正确（`host-key-trust.ts:71`）；`getStoredHostKeyFingerprint` 的 catch 返回 null（fail-closed）；`storeHostKeyFingerprint` 的 catch 静默（信任可丢失但不致命）且有空串早退守卫。
4. **`decodeUtf8` 算法**：与第 1 轮推导一致，本轮未改动；`carry` 永不超过 3 字节。
5. **远端（WebSocket）通道无回归**：`respondToHostKey` 非 local 分支回落 `sendHostKeyResponse?.()`，与改造前等价；`PassphraseDialog`/`WarpgateDialog` 的 `&& !isLocalChannel` 对 remote 无影响（`isLocalChannel` 初值 false，仅 local 分支置 true）；`sendTransportPrompt` 的 local 早退同理。`connectionOrigin` 缺省/null/`"remote"` 一律走 `NativeWebSocketManager`（`Terminal.tsx:1265-1268`），旧 host 不会被误判为 local。
6. **open-tabs 不注册/删除的对称性**：注册判定 `type === "terminal" && host.connectionOrigin === "local"`（`TerminalSessionsContext.tsx:213`），删除判定 `type !== "terminal" \|\| connectionOrigin !== "local"`（`248-251`）与之完全互补——**既不会产生「注册跳过但删除仍调 deleteOpenTab」的孤儿记录，也不会反向漏删**。
7. **事件订阅无模块级单例问题**：`NativeSshSubscription` 仍为 per-instance，`disposed` 幂等；`app/` 无全局 subscribe/unsubscribe 调用点。多 tab 不互相取消订阅。
8. **服务端 plan 端点与字段一一对应**：`buildNativeSSHPlan`（`/opt/termix/.../host-native-ssh-routes.ts:44-91`）返回 9 个字段，`NativeSSHPlan`（`main-axios.ts:3258-3272`）与 `NativeSSHConnectOptions` 全覆盖；`/db/host/{id}/native-ssh` 已注册，`authenticateJWT` + `requireDataAccess` 门禁齐全；400（非 SSH host）/404（无权限或已删）/500 三态分离。
9. **`connection_origin` 不跨设备同步**：服务端 `sync.ts` readOnlyFields，与本仓库 `types/index.ts:22-23` 注释一致。
10. **编译 / 静态检查全绿**：`tsc --noEmit` 0 error、`eslint` 0 error / 98 warning（+2 见 B4）、Kotlin 编译 exit 0，均已实际执行。
11. **`buildKeyProvider` 失败路径**：抛 `IllegalArgumentException(..., lastError)` 带 cause（`NativeSshEngine.kt:186-195`），`classify` → `INVALID_CREDENTIALS` → auth dialog，用户可重输 key/passphrase。
12. **`pumpOutput` 与 `write` 失败的并发收尾**：`write` 分支 `connections.remove(sessionId) != null` 才 close/scrub/emit（`NativeSshModule.kt:140-145`）；`OpenConnection.close()` 有 `closing` 标志幂等（`NativeSshEngine.kt:38-47`）；`Emitter.closed()` 有 per-session `putIfAbsent`（H2 修复）。三重保险下不会重复 close 或重复 onClosed。
13. **`disconnectAllNativeSsh` 在 cleanup 的调用顺序**：先 `transport?.destroy()`（停订阅 + disconnect 已知 sessionId），再 `disconnectAllNativeSsh()`（兜底 dial 中无 id 的连接）。顺序正确——destroy 先断订阅，避免兜底断开产生的 onClosed 触发多余 UI 动作。
14. **`onDestroy` 是否可在 cleanup 后仍收到事件**：`NativeSSHSession.destroy()` 置 `destroyed = true` 并 `disposeSubscription()`，`NativeSshSubscription.dispose()` 移除全部 listener 且幂等；后续迟到事件的 handler 首行 `if (this.destroyed) return` 全部拦掉（`NativeSSHSession.ts:81,88,101,105,109`）。
15. **connection origin 相关 UI 与服务端语义一致**：`HostForm.tsx:352` 的 `connectionOrigin: form.connectionOrigin || null` 与服务端 `connectionOrigin === "local" \|\| "remote" ? ... : null`（`host.ts:378-380, 1081-1083`）语义对齐；HostActionSheet / Host.tsx / QuickConnect 的追加 UI 为纯展示与本地表单态，不影响功能。
16. **`retryLocalWithCredentials` 的 authType 推断**：`authType: credentials.password ? "password" : "key"`（`Terminal.tsx:338`），与 `NativeWebSocketManager.sendReconnectWithCredentials` 的推断逻辑一致（`NativeWebSocketManager.ts:216-219`），两条通道行为统一。

---

## 六、建议的修复优先级

| 优先级 | 项 | 理由 |
|---|---|---|
| **P1** | **A1** | host key 变更在手机端无 UI 入口，且「本机已接受 + 服务端 pin 陈旧」会永久连接失败；至少执行底线修复（不关 tab + 可读文案），完整修复需产品确认信任优先级 |
| P2 | B2 | 时序竞态，可达但窗口小；下次迭代加 dial token |
| P3 | B1 | 死循环与守卫不对称已在审查期间修复；仅剩「弹对话框前就 fail-closed」的 UI 收尾，3~4 行。理论路径（sshj 0.41.1 已知 key 类型全覆盖），可下轮处理 |
| P3 | B3 / B4 | 记录性结论 + lint 基线，均无需改动 |

---

*本报告仅描述问题与修复方向，未修改任何代码。*
