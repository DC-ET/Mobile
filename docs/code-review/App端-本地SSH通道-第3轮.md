# 代码审查报告：App 端本地 SSH 通道（第 3 轮）

- **审查仓库**：`/tmp/mobile-check`（Termix-SSH/Mobile v1.5.0，Expo/RN 0.81）
- **审查对象**：本次「手机本地网络拨号 SSH」需求引入的未提交改动（`git status` 中 `M`/`??` 全部文件）
- **审查日期**：2026-09-29
- **审查方式**：静态阅读 + sshj 0.41.1 调用链推导 + Kotlin 字节码核验（`javap -c`）+ RN 桥 null 传递链逐层推导 + `tsc` / `eslint` / `kotlinc` 实跑
- **前置**：第 1 轮（`App端-本地SSH通道-第1轮.md`，2 阻塞 + 6 高危 + 5 中危）与第 2 轮（`App端-本地SSH通道-第2轮.md`，1 重要 + 4 建议）已全部修复。本轮职责是**验证第 3 轮修复的正确性 + 识别「修复的修复」引入的新缺陷**。

## 总体结论

**不建议合并。** 第 3 轮要求的 5 个疑点中，4 个（优先级反转、changed 通路、username 合入、Kotlin 默认参数）已核验逻辑正确；但发现 **1 个阻塞项**：`onClosed` 在 host key 事件之后立即触发 `onDisconnected` → 关 tab，导致 host key 对话框（`new` 与 `changed` 两种场景）**在用户能够看到并操作之前就被自己关掉**。

| 级别 | 数量 | 项 |
|---|---|---|
| 阻塞 | **1** | **C1**（`onClosed` 无 `awaitingUserInput` 门控，关掉刚弹出的 host key 对话框） |
| 重要 | 0 | — |
| 建议 | 0 | — |

> 判据说明：按「是否导致功能不可用、数据错乱、无限循环或安全失效」唯一判据审查，风格、文案、可读性、可维护性均不列为问题。故 C1 是本轮唯一的报告项。

---

## 一、阻塞项（Must Fix）

### C1. `NativeSSHSession` 的 `onClosed` 未检查 `awaitingUserInput`，host key 对话框弹出后立即被关 tab

**位置**：

- `app/tabs/sessions/terminal/NativeSSHSession.ts:109-113`（`onClosed` handler，**缺失门控**）
- `app/tabs/sessions/terminal/NativeSSHSession.ts:379-385`（`handleError`，**已有**同款门控，作为对照）
- `modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshModule.kt:88-119`（`finally { emit.closed() }` 无条件执行）
- `app/tabs/sessions/terminal/Terminal.tsx:1249-1253`（`onDisconnected` → `onClose()` 关 tab）
- `app/tabs/sessions/Sessions.tsx:669-670`（`<Terminal key={session.id}>`，session 被移除即卸载）

**代码事实**：

```ts
// NativeSSHSession.ts:105-113 —— onClosed handler
onClosed: () => {
  if (this.destroyed) return;
  this.sessionId = null;
  this.shouldNotReconnect = true;
  this.config.onDisconnected(this.config.hostConfig.name);   // ← 没有 awaitingUserInput 检查
},
```

```ts
// NativeSSHSession.ts:377-385 —— handleError，同文件内已有该门控
// The user is already looking at a dialog; do not close the terminal
// underneath it before they answer.
if (this.awaitingUserInput) {
  this.awaitingUserInputSince ??= Date.now();
  if (Date.now() - this.awaitingUserInputSince < AUTH_DIALOG_GRACE_MS) {
    return;
  }
  this.awaitingUserInput = false;
}
```

```kotlin
// NativeSshModule.kt:88-119 —— worker 线程
} catch (e: HostKeyUnknownException) {
  runCatching { e.client.disconnect() }
  emit.hostKey(e.fingerprint, e.keyType, e.algorithm, "new")
} catch (e: HostKeyMismatchException) {
  runCatching { e.client.disconnect() }
  emit.hostKey(e.presented, e.keyType, e.algorithm, scenario = "changed", trusted = e.trusted)
} catch (e: Throwable) {
  emit.error(classify(e), e.message ?: "Connection failed")
} finally {
  connections.remove(id)?.let { it.close(); it.scrubCredentials() }
  workers.remove(id)
  emit.closed()          // ← 三个 catch 分支之后都无条件执行
}
```

```tsx
// Terminal.tsx:1249-1253 —— onDisconnected 无条件关 tab
onDisconnected: (hostName) => {
  setConnectionState("disconnected");
  showToast.warning(`Disconnected from ${hostName}`);
  if (onClose) onClose();        // ← 关掉这个 tab
},
```

**触发路径（两条，都是正常使用路径，不是罕见时序）**：

*路径 A —— 首连（`scenario: "new"`），本地通道最常用路径*：

1. `performConnect` → `connectNativeSsh(options)` → Kotlin worker 线程。
2. `client.connect()` 抛 `TransportException` → `openConnection` 读 `hostKeyVerifier.outcome`，`outcome.trusted == null` → `HostKeyUnknownException`。
3. worker 的 `catch` 命中，`emit.hostKey(...)` 经 `ModuleEventSink.post`（`ModuleEventSink.kt:19`）投到 main Handler → **JS 事件 1**。
4. `finally` 执行 `emit.closed()` → **JS 事件 2**。两个事件由**同一个 worker 线程** post 到**同一个 main Handler**，队列 FIFO，**事件 1 严格早于事件 2 到达 JS**。
5. JS 事件 1：`onHostKey` → `handleHostKey`（`NativeSSHSession.ts:399-420`）置 `awaitingUserInput = true` / `shouldNotReconnect = true` → `config.onHostKeyVerificationRequired?.(...)` → `Terminal.tsx:1240` `setHostKeyVerification({...})` → React 渲染 `HostKeyVerificationDialog`（`visible={hostKeyVerification !== null}`）。
6. JS 事件 2（紧随其后）：`onClosed` handler。此时 `this.destroyed === false`（用户尚未回答）、`awaitingUserInput === true`，但 handler **不检查后者**，直接执行 `this.config.onDisconnected(...)`。
7. `Terminal.tsx:1249-1253` → `setConnectionState("disconnected")` + `showToast.warning("Disconnected from …")` + `if (onClose) onClose()`。
8. `onClose()` → `Sessions.tsx:705` `handleTabClose(session.id)` → `removeSession` → 该 session 从 `sessions` 移除 → `<Terminal key={session.id}>` 卸载 → cleanup 执行 `transport.destroy()` + `disconnectAllNativeSsh()`。

**净效果**：`setHostKeyVerification` 确实执行了，但**同一批 React 更新里 tab 就被移除**，用户看到的是「toast: Disconnected from X」+ 界面退回上一级——**host key 对话框从未有机会被用户看到或操作**。

*路径 B —— changed（服务端有 pin、key 已变更）*：链路相同，仅第 2 步为 `HostKeyMismatchException`。生产库 15 台 host 中 3 台已 pin 指纹（第 1 轮实测确认），该路径真实可达。

**为什么是 bug**：

1. **同文件两处不一致，门控只做了一半。** `handleError`（`:379-385`）的注释明确写了意图：「The user is already looking at a dialog; do not close the terminal underneath it before they answer.」`awaitingUserInput` 由 `handleHostKey`（`:406`）与 `handleError`（`:388`）**共同**置位，说明它就是为这两个交互场景设计的标志；但只有 `handleError` 检查它。对 `onError` 门控缺失只影响「要不要显示失败通知」，对 `onClosed` 门控缺失则直接导致「tab 被关、用户无法回答」——**后者正是该标志首要要防的事**。
2. **`shouldNotReconnect` 在此处没有防护价值。** 它只被本地重连策略读取，而 `onDisconnected` 在 `Terminal.tsx` 中是**无条件**调 `onClose()`，与之无关。
3. **对照 remote（server-dialed）通道可证明这是本地通道独有缺陷。** `NativeWebSocketManager` 的 `onDisconnected` 只在服务端显式发 `disconnected` 消息（`NativeWebSocketManager.ts:533`）或 `sessionTakenOver`（`:559`）时触发，服务端在发完 `host_key_verification_required` / `host_key_changed`（`/opt/termix/.../host-key-verifier.ts:385,449`）后不会关 WebSocket，因此 Web 端 changed 对话框能正常停留。本地通道的 Kotlin `finally` 却**无条件**补一个 `emit.closed()`，两条通道因此不对等。

**为何是本轮新引入**（非同轮前遗留）：

第 3 轮改动 1 把 `HostKeyMismatchException` 从 `emit.error("HOST_KEY_CHANGED", …)` 改为 `emit.hostKey(…, "changed", …)`：

- 旧代码走 `emit.error` → `handleError`。此时 `handleHostKey` 从未被调用，`awaitingUserInput` 为 **false**，门控自然不生效 → `notifyFailure` → toast + 关 tab。旧行为确实也是失败，但至少是**一次性的、可预期的失败**。
- 新代码把事件分流到 `onHostKey`，于是 `awaitingUserInput` 被置 true、`setHostKeyVerification` 被调用；**但 `finally` 的 `emit.closed()` 依旧执行**，两条路径同时生效。第 2 轮 A1 分析「`emit.error` → `notifyFailure` → 关 tab」这条旧路径时，**没有分析 `emit.hostKey` 之后紧跟的 `emit.closed()` 这条新路径**——两事件由同一线程顺序投递这一事实，是本次修复引入、前两轮未覆盖的新问题。

**影响**：

- 本轮修复目标（让用户看到指纹对比并决定）**完全落空**。用户视角与修复前几乎无区别，甚至少了原来那句 sshj 原始错误信息。
- `HostKeyVerificationDialog` 中全部 `scenario === "changed"` 逻辑（destructive 配色、`oldFingerprint` 双行对比、「Accept New Key」按钮）在本地通道**不可达**，第 2 轮 A1 的两条核心建议虽已实现但被 C1 抵消。
- 首连（`new` 场景）**同样受影响**，即本地通道的 host key 信任交互在**任何场景下都无法完成**。
- 用户无法接受/拒绝 → `resolveHostKey` 整条链（含本轮补的 `if (!fingerprint)` 守卫）无法触发 → `storeHostKeyFingerprint` 对本地通道**不可达**，信任存储等于没接入。

**修复建议**（两种，推荐方案 1）：

1. **给 `onClosed` handler 补上与 `handleError` 相同的门控**（与文件内既有约定一致，改动最小）：

```ts
onClosed: () => {
  if (this.destroyed) return;
  this.sessionId = null;
  this.shouldNotReconnect = true;
  // A host key or auth dialog owns this attempt: the tab must stay up until
  // the user answers, exactly as handleError already guarantees.
  if (this.awaitingUserInput) return;          // ← 不调 onDisconnected，不关 tab
  this.config.onDisconnected(this.config.hostConfig.name);
},
```

   两条退出路径仍然有效：用户点 Accept/Reject → `resolveHostKey` 置 `awaitingUserInput = false` 后 reconnect/notifyFailure；对话框被系统返回键关闭 → `Modal` 的 `onRequestClose={onReject}` → `Terminal.tsx:1599-1603` 的 `onReject` → `resolveHostKey(false, …)` → `notifyFailure` → 关 tab。因此不会出现「对话框消失后终端永远挂在 connecting」的新问题。

2. **或在 Kotlin 侧不在 host key 分支之后发 `onClosed`**：把 `emit.closed()` 从无条件 `finally` 改为仅在「连接曾真正建立过或走 error 路径」时发送。需处理 `connections.remove` / `workers.remove` 顺序，改动面大于方案 1，不推荐。

**验证方式**（修复后）：首连一台无 pin 的 host，确认不再出现 "Disconnected from …" toast、`HostKeyVerificationDialog` 停留可交互；点 Accept → `resolveHostKey(true, data.fingerprint)` → `storeHostKeyFingerprint` 写 SecureStore → `reconnect()` → 二次 dial 通过 `verify()` → 进入 connection 态。再测 changed 场景：服务端 pin 与服务器实际出示不一致，确认双行指纹对比可见。

---

## 二、疑点逐条核验结论

| # | 疑点 | 核验方式 | 结论 |
|---|---|---|---|
| **1** | 优先级反转后：服务端 pin A、本机已接受 B、服务器出示 C 会怎样？能否到达 changed？接受 C 后能否记住？`acceptedHostKey` 与 `resolveKnownHostKey` 谁在前？ | 读 `host-key-trust.ts:103-108` + `NativeSSHSession.ts:216-230` | **逻辑正确**（除 C1 的呈现问题）。`performConnect` 中 `knownHostKey = this.acceptedHostKey ?? (await resolveKnownHostKey({...}))`，本机已接受的 **B** 传给 sshj；服务器出示 C → `PinnedHostKeyVerifier.verify` 返回 false，`outcome.trusted = B != null` → `HostKeyMismatchException` → `emit.hostKey(..., "changed", trusted = B)`。**能构造出 changed 事件**。用户接受 C 后：`resolveHostKey`（`:266-268`）先置 `this.acceptedHostKey = C` 再 `await storeHostKeyFingerprint`，而 `performConnect` 的重置**不清** `acceptedHostKey`（仅 `destroy()` 清），所以重连第一次就以 C 通过 `verify()`，同时 C 已落 SecureStore。**accept 链无缺口**。注：`host-key-trust.ts:20-27` 文件头注释仍写旧优先级（服务端 pin 优先），与代码矛盾，属文档滞后，非功能 bug。 |
| **2** | changed 走得到对话框吗？`trusted: null` 在 RN 桥会变字符串 `"null"` 吗？ | Kotlin 字节码 + RN 桥 null 转换链推导 | **事件链无断点，`trusted` 正确传递为 `null`**。字节码确认 `Emitter.hostKey` 的 `mapOf` 含 `"trusted"` 键；`Emitter.hostKey$default` 正确合成；worker 中 unknown 分支第 5 参为 `aconst_null`，mismatch 分支第 5 参为 `HostKeyMismatchException.getTrusted()`。null 经 `KModuleEventEmitterWrapper.emit` → `toJSValueExperimental` → `JNIToJSIConverter.convert`（`if (value == nullptr) return jsi::Value::null()`）→ fbjni `setProperty(rt, key, null)`，**JS 侧得到 `null` 而非 `"null"`**。`handleHostKey` 用 `event.trusted ?? undefined`，对话框条件 `data && isChanged && data.oldFingerprint`，`undefined` 时安全降级为单行，无崩溃。**除 C1 外该链路完整。** |
| **3** | changed accept 后：`getNativeSSHPlan` 返回的陈旧 pin 会不会干扰后续重连？ | 读 `performConnect:216-236` + `host-key-trust.ts:103-108` | **不干扰，正确。** `resolveKnownHostKey` 内部先读本地 `getStoredHostKeyFingerprint`，本地非空即 **return**，`host.hostKeyFingerprint`（此时陈旧的服务端 pin）**根本不会被读取**。即使本地读失败（Keystore 异常返回 null），回落到的陈旧 pin 会使下一次 dial 再次 mismatch → 再次弹 changed 对话框，用户可再次接受。**不存在因回落陈旧 pin 而静默信任错误 key 的安全漏洞。** |
| **4** | `username` 合入的回归：QuickConnect / plan null / credential override 三支？会不会用户名被错误覆盖？ | 读 `NativeSSHSession.ts:186-241` + 服务端 `buildNativeSSHPlan` + `pickResolvedUsername` | **三支均正确，无误覆盖。** QuickConnect：`hostId = -Date.now() <= 0`，`plan` 的 `hostId > 0 && !overridden` 不成立 → `plan = null` → 走 `plan ?? {...}` 分支 → `username: hostConfig.username`（QuickConnect 有 `if (!username.trim())` 校验，必非空）。plan null 早退：`hostId > 0 && !plan && !overriddenCredentials` → `notifyFailure` 后 `return`，不会走到 `options` 构造。override 分支：`username: hostConfig.username`，不从 plan 取。空串兼容：`credentials.username \|\| hostConfig.username`，`plan.username === ""` 时正确回落到 hostConfig.username。且服务端 `pickResolvedUsername` 本身就是「host 用户名优先，credential 兜底」，与 App 侧回落语义一致。 |
| **5** | Kotlin `Emitter.hostKey` 具名默认参数编译产物正常吗？两种调用点都能工作？ | `javap -p` + `javap -p -c` 反编译 `/tmp/ktout_review3b` | **两种调用点均正确。** `Emitter` 类签名含 `hostKey(String,String,String,String,String)` 与 `static hostKey$default(Emitter,String,String,String,String,String,int,Object)`；`hostKey$default` 对第 5 参取默认值时填充 `null`。worker 字节码：unknown 分支 `invokestatic hostKey$default(...)` + `aconst_null`，mismatch 分支 `invokevirtual Emitter.hostKey(...)` + `getTrusted()`。 |
| **6** | `NATIVE_SSH_ERRORS.HOST_KEY_CHANGED` / `HOST_KEY_UNKNOWN` 已无生产者，是否有死代码引用导致误判？ | `grep -rn "NATIVE_SSH_ERRORS\|HOST_KEY_CHANGED\|HOST_KEY_UNKNOWN" app/ modules/` | **无死代码引用。** 全仓仅 `modules/native-ssh/index.ts:99-101` 常量定义自身命中，无任何读取点；Kotlin `classify()`（`SessionShims.kt:125-137`）无 HOST_KEY_* 分支，异常在更早处被捕获。保留或删除均无害，不构成问题。 |

---

## 三、第 2 轮问题修复核验明细（逐条）

| 项 | 第 3 轮修复核验 | 结论 |
|---|---|---|
| **A1**（本轮主目标）changed 永不到达对话框 + 「本机已接受 vs 服务端 pin 陈旧」死锁 | 改动 1-5 全部就位：mismatch 改走 `emit.hostKey`（`NativeSshModule.kt:99-107`）；`Emitter.hostKey` 加 `trusted` 形参并透传（`SessionShims.kt:47,57`）；JS 侧 `NativeSshHostKeyEvent` / handlers 补 `trusted?: string \| null`；`handleHostKey` 填 `oldFingerprint: event.trusted ?? undefined`（`NativeSSHSession.ts:411`）；`resolveKnownHostKey` 反转优先级（`host-key-trust.ts:103-108`）；`handleError` 删除 `HOST_KEY_UNKNOWN` 早退。**逻辑层修复完整且正确**（疑点 1-3 已核验）。 | ⚠️ **逻辑正确，但被 C1 抵消** |
| **C 收尾**（第 2 轮 B1 的死循环半）`resolveHostKey` 空指纹守卫 + `storeHostKeyFingerprint` 空串早退 | `NativeSSHSession.ts:255-265` 改为显式 `if (!fingerprint) { shouldNotReconnect = true; notifyFailure(...); return; }`，不再静默重连导致无限弹窗；`host-key-trust.ts:62` 加 `if (!fingerprint) return;`。两侧守卫对称。第 2 轮的「死循环」与「守卫不对称」两半均已解决。 | ✅ 正确 |
| **第 2 轮 B1 残留**（空指纹仍弹空白对话框） | `openConnection` 的 catch 未对 `outcome.fingerprint.isEmpty()` 提前 fail-closed，仍会先弹一个空白指纹对话框后才由 `resolveHostKey` 报错。属第 2 轮已记录的理论路径（sshj 0.41.1 对 ed25519/rsa/ecdsa/sk-* 已全覆盖），**不导致功能不可用**。按本轮判据不列为问题。 | ✅ 可接受（维持记录） |
| **第 2 轮 B2**（`reconnect()` 以 `sessionId === null` 判定连接已死，时序竞态） | **本轮未修**，`NativeSSHSession.ts:301-310` 仍是 `if (this.sessionId !== null)`。后果是「重连成功后旧 `emit.closed()` 到达 → `onDisconnected` → 关 tab」，与 C1 同一 UI 出口，故 C1 修好后触发面会比第 2 轮记录的略宽。但该竞态需「`disconnectNativeSsh` 恰好晚于 worker `connections.remove`」的窄窗口，**不构成功能不可用**，维持第 2 轮定级（P2，下轮加 dial token）。 | ✅ 维持记录（不列为本轮问题） |
| **第 2 轮 B3 / B4** | B3（`closedSessions` 量级与 `OnDestroy` 时序）：本轮未改，维持「可接受，不建议修复」。B4（lint 基线 96→98）：本轮实跑 `0 error / 98 warning`，多出的 2 个为 `NativeSSHSession.ts` / `host-key-trust.ts` 的 `catch (e)` 未使用变量，与 `main-axios.ts` 基线同类。 | ✅ 维持记录 |

---

## 四、经核查未发现问题的点

1. **优先级反转本身无信任错误 key 的风险**：`host-key-trust.ts:103-108` 先读本地、本地非空即 return，服务端 pin 仅作回落；Keystore 失败时 `getStoredHostKeyFingerprint` 返回 null（fail-closed，不退化成信任一切）。回落陈旧 pin 只会导致再次弹 changed，不会静默信任（疑点 3 已核验）。
2. **accept 后立即信任生效（无二段弹窗）**：`resolveHostKey` 先置 `acceptedHostKey` 再 await 持久化，`performConnect` 的 `acceptedHostKey ??` 保证重连第一次即过 `verify()`（疑点 1 已核验）。
3. **`trusted` 经 Kotlin `mapOf` → RN 桥 → JS 是 `null` 而非 `"null"`**，`oldFingerprint` 为 `undefined` 时对话框安全降级（疑点 2，字节码 + fbjni converter 双层证据）。
4. **`username` 三支覆盖正确，无用户名被错误覆盖**（疑点 4）；与后端 `pickResolvedUsername` 语义一致。
5. **Kotlin 具名默认参数编译正常**，两种调用点均生成正确字节码（疑点 5）。
6. **`NATIVE_SSH_ERRORS` 中两个已无生产者的常量无死代码引用**，不造成误判（疑点 6）。
7. **`hostKeyStoreKey` 不串号**：`id > 0` 用真实 id，`id <= 0`（QuickConnect）统一 `transient` 段但仍带 `ip:port`，不同 QuickConnect 目标不串。
8. **指纹格式与服务端兼容**：`fromKey` 为 `hex(Buffer.putPublicKey(key))`，与 `host-key-verifier.ts` 同算法；`normalize()` 容忍大小写 / `SHA256:` / `sha256:` 前缀 / 空格；`isTrusted()` 两侧都 re-normalize，不会与 verifier 自身比较漂移。
9. **凭据生命周期合规**：明文只在 `plan` 与 `CharArray` 中驻留，`scrubCredentials()` 对密码 `fill(NUL)`（私钥无法清零已在注释中诚实声明）；本地信任存 SecureStore（`WHEN_UNLOCKED_THIS_DEVICE_ONLY`）而非 AsyncStorage。
10. **`overriddenCredentials` 独立于 `hostConfig`**，不会被每次 dial 的服务端 plan 覆盖；`destroy()` 清空。
11. **open-tabs 注册/删除判定互补**：注册判定 `type === "terminal" && connectionOrigin === "local"`（`TerminalSessionsContext.tsx:211-219`），删除判定 `type !== "terminal" || connectionOrigin !== "local"`（`:249-255`），逻辑互补，无孤儿记录也不会漏删。
12. **remote（WebSocket）通道无回归**：`respondToHostKey` 非 local 分支回落 `sendHostKeyResponse?.()`；`PassphraseDialog` / `WarpgateDialog` 的 `&& !isLocalChannel` 对 remote 无影响（`isLocalChannel` 初值 false）；`connectionOrigin` 缺省/null/"remote" 一律走 `NativeWebSocketManager`。
13. **`buildProtocolFields` 的 `connectionOrigin`** 仅在 "local"/"remote" 时透传、否则 null，不会把存量 host 误判为 local。
14. **多 tab / StrictMode**：仓库无 StrictMode；`NativeSshSubscription` per-instance 且 `dispose()` 幂等，多 tab 不互相取消订阅。
15. **`Emitter.closed()` 去重仍有效**：per-sessionId `putIfAbsent`，C1 场景不会产生重复 `onClosed`。
16. **`OnDestroy` 时序安全**：`disconnectAllSessions()` → `removeCallbacksAndMessages(null)` → `forgetAll()` → `NativeSsh.events = NoopEventSink`，无 NPE、无向已销毁 context 发事件。

---

## 五、实测核验结果（按要求实际执行）

| 命令 | 结果 |
|---|---|
| `cd /tmp/mobile-check && npx tsc --noEmit -p tsconfig.json` | **exit 0，0 error** |
| `cd /tmp/mobile-check && npx eslint . -f json` | **0 error / 98 warning**（基线 96；新增 2 个为 `NativeSSHSession.ts` / `host-key-trust.ts` 的 `catch (e)` 未使用变量，属基线同类 noise） |
| Kotlin 编译（`/opt/kotlinc/kotlinc/bin/kotlinc /tmp/stubsrc/*.kt modules/native-ssh/android/src/main/java/expo/modules/nativessh/*.kt -cp "...android-35...sshj-0.41.1..." -d /tmp/ktout_review3b`） | **exit 0**，无 warning / error |

Kotlin 字节码核验（`javap`，输出目录 `/tmp/ktout_review3b`）：

```
Emitter 类签名：
  public final void hostKey(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
  public static void hostKey$default(expo.modules.nativessh.Emitter, java.lang.String, java.lang.String,
                                     java.lang.String, java.lang.String, java.lang.String, int, java.lang.Object)

Emitter.hostKey 方法体（"trusted" 键确认存在）：
   99: iconst_5
  100: ldc           #86    // String trusted
  104: invokestatic  Method kotlin/TuplesKt.to
  110: invokestatic  Method kotlin/collections/MapsKt.mapOf

NativeSshModule$definition$1$3$worker$1（两种调用点）：
  unknown  分支: 194: aconst_null
               197: aconst_null
               198: invokestatic  Emitter.hostKey$default(...)
  mismatch 分支: 344: invokevirtual HostKeyMismatchException.getTrusted()
               347: invokevirtual  Emitter.hostKey(String,String,String,String,String)
```

sshj 行为（第 2 轮已实测，本轮未改动相关代码，未重跑）：`presented len=102`、与生产库 hex 逐字符相等、`KeyType.fromKey = ssh-ed25519`；`normalize()` 对大小写 / `SHA256:` / `sha256:` / 空格全部容忍；`trusted == null` 时 `isTrusted() == false`。

---

## 六、建议的修复优先级

| 优先级 | 项 | 理由 |
|---|---|---|
| **P0** | **C1** | 不改则本轮 A1 修复完全无效：本地通道 host key 对话框（`new` 与 `changed` 两种场景）在用户看到之前就被 `onClosed` → `onDisconnected` → `onClose()` 关掉，用户无法接受/拒绝，`storeHostKeyFingerprint` 不可达。**首连即受影响，非罕见路径。** 修复为 `onClosed` handler 加一行 `awaitingUserInput` 早退，与同文件 `handleError` 既有约定一致。 |
| P2 | （第 2 轮 B2 遗留）引入 dial 代际计数器 | 时序竞态，窗口小；下轮迭代处理。C1 修好后该竞态触发面略宽，可一并考虑。 |

---

*本报告仅描述问题与修复方向，未修改任何代码。*
