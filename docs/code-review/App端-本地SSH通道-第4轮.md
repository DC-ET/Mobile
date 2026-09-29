# 代码审查报告：App 端本地 SSH 通道（第 4 轮）

- **审查仓库**：`/tmp/mobile-check`（Termix-SSH/Mobile v1.5.0，Expo/RN 0.81）
- **审查对象**：第 3 轮阻塞项 **C1 的修复**（唯一改动：`app/tabs/sessions/terminal/NativeSSHSession.ts` 的 `onClosed` handler 新增 `awaitingUserInput` 早退）
- **审查日期**：2026-09-29
- **审查方式**：只读复审 + 事件投递时序推导 + `awaitingUserInput` 全场景枚举 + 逐条核对退出路径 + `tsc` / `eslint` 实跑（Kotlin 未改动，无需重编）
- **前置**：第 1-3 轮报告（`App端-本地SSH通道-第{1,2,3}轮.md`）。第 3 轮结论「不建议合并」的唯一原因是 C1；本轮即针对该修复复审。

## 总体结论

**可以合并。**

第 3 轮的阻塞项 C1 已被正确闭合：`new` 与 `changed` 两条路径的对话框都能停留，Accept / Reject / 系统返回键三条出口均可正常收尾，不存在「野连接」或「tab 永远挂在 connecting」的新死局。本次修复未引入新的功能性问题。

| 级别 | 数量 | 项 |
|---|---|---|
| 阻塞 | 0 | — |
| 重要 | 0 | — |
| 建议 | 0 | — |

> 按本轮判据（功能不可用 / 数据错乱 / 无限循环 / 安全失效）未发现需修复项，故第 3 轮 P2 遗留项（`reconnect()` 时序竞态）经复核**触发面未变宽**，维持第 2/3 轮「P2，下轮迭代」结论，本轮不列为问题。详见第六节。

---

## 一、C1 修复的正确性核验

**改动本身**（`app/tabs/sessions/terminal/NativeSSHSession.ts:109-118`）：

```ts
onClosed: () => {
  if (this.destroyed) return;
  this.sessionId = null;
  this.shouldNotReconnect = true;
  // A host key or auth dialog owns this attempt: the native side closes
  // its socket before asking, so this handler would otherwise tear the
  // terminal down underneath the dialog the user is looking at — exactly
  // what `handleError` below refuses to do. The dialog is then the only
  // way out: answering it clears `awaitingUserInput` and reports.
  if (this.awaitingUserInput) return;
  this.config.onDisconnected(this.config.hostConfig.name);
},
```

与同文件 `handleError:379-385` 的门控在**语义与位置上都已对齐**：两者都只做「早退」，都依赖各自的后续路径复位 `awaitingUserInput`。注释准确描述了不改的真实原因（`NativeSshModule.kt:88-119` 的 `finally { emit.closed() }` 在任何 catch 分支之后都无条件执行）。

### 1.1 事件时序确认（C1 闭合的前提）

两事件由**同一 worker 线程**、经**同一 `ModuleEventSink(mainHandler)`** post（`SessionShims.kt:21-23` 的 `NativeSsh.events.post(...)` → `ModuleEventSink.kt:19` 的 `handler.post { ... }`），Android `Handler` 对同一队列保序，因此：

- `NativeSshModule.kt:92`（unknown）或 `:99-107`（mismatch）的 `emit.hostKey(...)` → **JS 事件 1**
- `NativeSshModule.kt:117`（finally）的 `emit.closed()` → **JS 事件 2**（严格晚于事件 1）

事件 1 → `handleHostKey:405-406` 先置 `awaitingUserInput = true`；事件 2 到达时门控已生效 → 早退。**不存在「onClosed 抢先到达导致门控未起效」的窗口**，因为置位发生在 `handleHostKey` 的第一行，而 `handleHostKey` 由事件 1 同步触发。

### 1.2 两条路径都能停留

| 路径 | Kotlin 入口 | scenario | `handleHostKey` 行为 | 早退前状态 | 结果 |
|---|---|---|---|---|---|
| 首连 / 无 pin | `HostKeyUnknownException`（`NativeSshModule.kt:91-95`） | `"new"` | `awaitingUserInput=true`，`setHostKeyVerification` | 早退 | ✅ 对话框停留 |
| 服务端 pin 与出示不一致 | `HostKeyMismatchException`（`:96-107`） | `"changed"`，`trusted=e.trusted` | 同上，且 `oldFingerprint = event.trusted` | 早退 | ✅ 对话框停留，双行对比可见 |

### 1.3 三条出口逐条核验

**出口 A — Accept（`Terminal.tsx:1591-1597`）**：

```tsx
onAccept={() => {
  respondToHostKey(true, hostKeyVerification?.data.fingerprint);
  setHostKeyVerification(null);
}}
```

`fingerprint` 由 `onHostKeyVerificationRequired`（`Terminal.tsx:1237-1241`）显式写入 state，**不再是第 1 轮 H1 的 `undefined` 漏洞**。链路：`respondToHostKey`（`Terminal.tsx:1058-1067`）→ `session.resolveHostKey(true, fp)`：

1. `resolveHostKey:255-256` 先清 `awaitingUserInput`（后续 `onClosed` 不再被门控）与 `awaitingUserInputSince`；
2. `!fingerprint` 守卫（`:265-270`，本轮 3 轮 C 收尾新增）——正常路径不命中，空指纹场景下 `notifyFailure` + return，**不会无限弹窗**；
3. `this.acceptedHostKey = fingerprint`（`:275`）；
4. `await storeHostKeyFingerprint(host, fingerprint)`（`:276`）——`host-key-trust.ts:58-77`，非空时才写（`:62` 守卫），写 SecureStore；
5. `await this.reconnect()`（`:283`）。

`reconnect:312-319`：`this.sessionId === null`（被早退前那次 `onClosed` 清了）→ **跳过 `disconnectNativeSsh`** → `await this.connect(cols, rows)`。

**这里有一个必须澄清的点（对应用户疑点 1 的「会不会留下野连接」）：跳过 `disconnectNativeSsh` 是正确的、不留野连接。** 理由：

- Kotlin `openConnection` 在 host key 分支**从未把连接放进 `connections` map**：`connections[id] = opened` 位于 `NativeSshModule.kt:89`，只在 `openConnection(options)` 成功返回之后执行；host key 两个 catch 分支发生在该赋值之前，`finally` 的 `connections.remove(id)` 拿不到任何东西（`NativeSshModule.kt:112-115`）。
- 该分支已显式 `runCatching { e.client.disconnect() }`（`:94` / `:99`），half-open transport 已被主动关闭。
- 也就是说 host key 路径**从头到尾不存在一个 `connections` 中的条目**，`sessionId` 也从未被赋值（`performConnect:242` 的 `this.sessionId = await connectNativeSsh(options)` 因 `connect` 抛异常而没有执行）。JS 侧 `sessionId === null` 是**对 native 侧事实的准确反映**，不是「忘了清理」。

因此 `reconnect()` 跳过 disconnect 后不会泄漏 socket 或线程；worker 已在 `finally` 里 `workers.remove(id)` 退出。**无野连接。**

**出口 B — Reject（`Terminal.tsx:1598-1603`）**：`respondToHostKey(false, fp)` → `resolveHostKey(false, ...)`：

1. `:255-256` 清 `awaitingUserInput`；
2. `:258-264`：`await this.forgetHostKey(host)`（`this.acceptedHostKey = null` + `clearStoredHostKeyFingerprint` 删 SecureStore 条目）→ `shouldNotReconnect = true` → `notifyFailure("Rejected the host key for ...")`；
3. `notifyFailure:370-374`：`hasNotifiedFailure` 幂等（只 toast + 关 tab 一次）→ `onConnectionFailed` → `handleConnectionFailure:278-287` → toast + `setConnectionState("failed")` + `onClose()`。

**tab 正常关闭，无死局。** 且 `Terminal.tsx` 的 `onReject` 自己也调 `if (onClose) onClose()`，与 `notifyFailure` 的关 tab 路径重复但幂等（`removeSession` 第二次找不到 session 早退，`TerminalSessionsContext.tsx:245`）。

**出口 C — Android 系统返回键（`Modal.onRequestClose={onReject}`，`HostKeyVerificationDialog.tsx:93-99`）**：`Modal.d.ts:38` 明确「called when the user taps the hardware back button on Android」。`RCTModalHostView` 在 Android 上会拦截 BACK（`Modal.js:326-358`），因此返回键走到 `onReject`，即出口 B。**不存在「返回键只关掉 Modal 但 tab 挂住」的死局。**

### 1.4 对话框期间的输入/缩放（用户疑点 2）

`onClosed` 早退前先执行了 `this.sessionId = null`（`:111`），因此对话框期间：

- `sendInput:330-335` → `this.sessionId === null` → return，**静默丢弃**；
- `sendResize:337-341` → 同样 return。注意它**已经把 `this.cols = cols` 写进了字段**（`:338`），所以后续 `reconnect()` 用新尺寸拨号。

**这是预期行为，不是新引入的丢输入问题**，理由：

1. 主机 key 未确认前 socket 已被 native 主动断开（`NativeSshModule.kt:94/99`），**根本没有目的地可以送达**，任何输入都不可能真正到达远端。把输入暂存下来再「补发」反而危险——那是把用户在未确认安全性的会话里敲的内容发到可能已被 MITM 的通道上。fail-closed 下丢弃是正确选择。
2. 这是**改动前就有的行为**，非本轮引入：改动前 `onClosed` 也会执行 `this.sessionId = null`（只是不早退）。本轮只删掉了后面的 `onDisconnected` 调用，`sessionId` 的清理时机一字未变。
3. 用户不会真的「输入」：`Terminal.tsx:1393-1396` 的 `pointerEvents={"none"}` 在 `hostKeyVerification !== null` 时禁掉 WebView 触摸，且 `HostKeyVerificationDialog` 是全覆盖 `Modal`（`HostKeyVerificationDialog.tsx:93-99, 101-113`）。IME 输入也被 `isDialogOpen`（`Terminal.tsx:1336-1342`）拦掉不重新拉起（`Sessions.tsx:994-1005`）。故「用户在对话框期间敲了一串命令全部丢失」的场景基本不可达。
4. Kotlin 侧的兜底幂等都成立，即便 `sessionId` 未及时清：`NativeSshModule.kt:134`（resize）与 `:142`（write）均为 `?: return@AsyncFunction Unit`，`write` 失败分支只在 `connections.remove(sessionId) != null` 时才收尾（`:141-151`），`disconnect` 同样（`:158-159`）。

---

## 二、`awaitingUserInput` 全场景枚举（用户疑点 3）

**置位点共 2 处**（`NativeSSHSession.ts`）：

| 置位点 | 场景 | 行 |
|---|---|---|
| `handleError:394` | auth 失败（`isAuthFailure` 命中：`INVALID_CREDENTIALS` / 消息含 auth/password/permission denied） | 393-398 |
| `handleHostKey:412` | host key 需要用户决策（new/changed） | 411-414 |

**清除点共 4 处**：

| 清除点 | 行 | 机制 |
|---|---|---|
| `performConnect` 开头 | 160-161 | 每次拨号重置 |
| `resolveHostKey` 开头 | 257-258 | 用户回答对话框 |
| `destroy` | 359-360 | 组件卸载 |
| `handleError` 60s 宽限到期 | 387-391 | `Date.now() - awaitingUserInputSince >= AUTH_DIALOG_GRACE_MS` 后放行并复位 |

**逐场景检查退出路径**（每个都必须有一条**不依赖 `onDisconnected`** 的路）：

| 场景 | 对话框 | 出口 | 是否需 `onDisconnected` | 结论 |
|---|---|---|---|---|
| **S1** host key，`new` | `HostKeyVerificationDialog` | Accept → `resolveHostKey` → reconnect（出口 A）；Reject → `forgetHostKey` + `notifyFailure` 关 tab（出口 B）；系统返回键 → `Modal.onRequestClose` → `onReject`（出口 B） | **否** | ✅ 三条出口均闭环 |
| **S2** host key，`changed` | 同上（双行对比） | 同 S1 | **否** | ✅ 同 S1 |
| **S3** auth 失败 | `SSHAuthDialog`（`Terminal.tsx:1573-1587`） | Submit → `handleAuthDialogSubmit:1078-1089` → `retryLocalWithCredentials`（local 分支 `overrideCredentials` + `reconnect`，`Terminal.tsx:333-341`）+ `setConnectionState("connecting")`；Cancel → `onCancel` → `setShowAuthDialog(false)` + `if (onClose) onClose()` 关 tab；系统返回键 → `Dialog` 内部 `Modal.onRequestClose={onClose}`（`app/components/ui/Dialog.tsx:40`，已核实该组件确有此转发） | **否** | ✅ 三条出口均闭环 |
| **S4** 对话框开着时 unmount（切 tab / 杀 app） | 任一 | `Terminal.tsx:1298-1305` cleanup：`transport?.destroy()`（置 `awaitingUserInput=false`、dispose subscription、`disconnectNativeSsh`）+ `disconnectAllNativeSsh()` 兜底 | **否** | ✅ 见第三节详述 |

**「tab 永远挂在 connecting」的专门排查**：

真正会让 UI 卡在 connecting 的条件是：既没有 `onDisconnected`（被早退拦了），也没有任何其它事件把 `connectionState` 从 `"connecting"` 推走。检查所有能把状态推走的写入点：

- `Terminal.tsx:1273`（effect 重建 transport 时 `setConnectionState("connecting")`）；
- `Terminal.tsx:1076`（TOTP submit）、`:1089`（auth dialog submit）——**非 host key 场景**；
- `onStateChange: "connecting"`（`Terminal.tsx:1174-1180`，由 `performConnect:162` 的 `config.onStateChange("connecting", ...)` 触发）；
- `"connected"`（`Terminal.tsx:1184`）；
- `onAuthDialogNeeded` → `setConnectionState("disconnected")`（`Terminal.tsx:1234`）——**S3 场景下 auth dialog 打开时状态即已切走，不依赖 `onClosed`**；
- `onDisconnected` / `onConnectionFailed`（`Terminal.tsx:1249` / `:1256`）。

对 **S1/S2（host key）**，`onHostKeyVerificationRequired` **没有**改 `connectionState`，所以对话框期间状态保持 `"connecting"`，spinner overlay（`Terminal.tsx:1487-1527`，条件 `connectionState === "connecting"`）会留在屏上。需确认两点：

1. **对话框是否仍可见可交互？** `HostKeyVerificationDialog` 用的是 RN `Modal`（`:93`），Android 上最终走 `RCTModalHostView`（`node_modules/react-native/Libraries/Modal/Modal.js:326-358`），由 `ReactActivityDelegate` 挂到 activity 的 root 而非被嵌进 `Terminal.tsx` 的 `View` 树，**不受兄弟节点 zIndex 120 影响**（`zIndex` 只在同一父布局内生效）。`statusBarTranslucent`（`:98`）也印证其独立窗口语义。故对话框在 spinner 之上可见。
2. **是否有兜底避免真的永远挂着？** 有：`handleError:387-391` 的 60s 宽限到期后会把 `awaitingUserInput` 复位并继续走 `notifyFailure`。也就是说即便用户 60 秒不回答（且不发生任何 unmount），下一次到达的错误事件仍会把终端关掉，不会无限悬挂。

**结论：S1/S2/S3/S4 四条场景全部有不依赖 `onDisconnected` 的出口，不存在新死局。**

---

## 三、`destroy()` 与 unmount cleanup 的交互（用户疑点 5）

`destroy():352-367`：

```ts
destroy(): void {
  if (this.destroyed) return;
  this.destroyed = true;
  this.shouldNotReconnect = true;
  this.overriddenCredentials = null;
  this.acceptedHostKey = null;
  this.awaitingUserInput = false;      // ← 先于 dispose
  this.awaitingUserInputSince = null;
  const id = this.sessionId;
  this.sessionId = null;
  this.disposeSubscription();
  if (id !== null) void disconnectNativeSsh(id).catch(() => {});
}
```

**「已 dispose 但 harness 仍等待」不会发生**，三重证据：

1. `destroyed` 在 `disposeSubscription()` **之前**置 true，而每个 handler（`onData:78`、`onStateChange:88`、`onError:101`、`onHostKey:105`、`onClosed:109`）首行都是 `if (this.destroyed) return`。`NativeSshSubscription.dispose()` 也已移除全部 RN listener 且幂等（`modules/native-ssh/index.ts:189-193`）。因此 dispose 之后**任何**迟到事件（含 `onClosed`）都被首行拦掉，不会命中那段早退逻辑，更不会走到 `config.onDisconnected`。
2. `Terminal.tsx:1298-1305` 的顺序是 `transport?.destroy()` → `transportRef.current = null` → `if (transport instanceof NativeSSHSession) void disconnectAllNativeSsh()`。`destroy` 先断订阅再断开连接，**兜底 `disconnectAll` 产生的 `onClosed` 已无人监听**（第 2 轮已确认此顺序正确）。
3. `disconnectAllNativeSsh()`（`modules/native-ssh/index.ts:219-221`）→ Kotlin `disconnectAllSessions()`（`NativeSshModule.kt:172-178`）对 `connections` 中每个条目 close + scrub，幂等。

**S4 场景下的 native 泄漏排查**（对话框期间 unmount）：

- 若 host key 事件已发生 → native 侧连接从未进 `connections`（见 1.3 的论证），worker 已在 `finally` 退出，`sessionId === null`，`destroy()` 的 `disconnectNativeSsh` 自然跳过；`disconnectAllNativeSsh` 无对象可清。**无泄漏。**
- 若 socket 正处 dial 中（尚未 `emit.hostKey`/`emit.state`）→ `connections` 无该 id，JS `sessionId` 也为 null；此时 `disconnectAll` 同样清不到它，但 `destroyed=true` 使其后续事件全部被 handler 拦下，worker 自身会在握手完成后经 `finally` 退出（socket 由 sshj 超时或失败路径关闭）。这与第 1 轮 H5 修复时点名的「dial 中无 id 的连接」是同一已知边界，由 `disconnectAllNativeSsh` 覆盖面之外的 worker 自然收尾兜底，非本轮引入，且已存在 cleanup 调用。

---

## 四、auth dialog 场景无回归（用户疑点 4）

`handleError` 的 auth 分支（`NativeSSHSession.ts:393-398`）置 `awaitingUserInput = true` 后，若一个 `onClosed` 到达：

- **旧行为**：`onDisconnected` → toast「Disconnected from X」+ `onClose()` 关 tab → 用户正在填的 auth dialog 随 tab 一起消失，凭据白填。
- **新行为**：早退，`SSHAuthDialog` 保留。

**这是改进而非阻塞**，理由：

1. **auth 分支本来就有独立退路**：`onAuthDialogNeeded` 已把 `connectionState` 置为 `"disconnected"`（`Terminal.tsx:1232-1237`），spinner 随即消失；`SSHAuthDialog` 有 Submit（`handleAuthDialogSubmit` → `overrideCredentials` + `reconnect` + `setConnectionState("connecting")`）与 Cancel / 系统返回键（`Dialog.tsx:40` 转发 `onClose` → `Terminal.tsx:1577-1580` 的 `setShowAuthDialog(false)` + `onClose()`）两条出口。**早退不会让任何东西挂起。**
2. **`handleError` 的 60s 宽限本来就是为这个场景设计的**（注释 `NativeSSHSession.ts:377-382`："if the dialog is dismissed without an answer (the screen unmounts, the user backs out) this flag is never cleared, and every failure after that would be swallowed — including the 'connection is really gone' one"）。宽限到期后放行，auth dialog 久了没反应时终端仍会收到真实断开通知。
3. **remote 通道的对应行为更弱**：`NativeWebSocketManager` 的 `ws.onclose` 只在 `shouldNotReconnect` 时 `notifyFailureOnce("Connection closed")`（`:592-595`），auth 分支同时置 `shouldNotReconnect = true`（`:453`），所以 remote 下 auth dialog 期间也不会关 tab。本地通道新行为现在与 remote 对齐。

---

## 五、第 2/3 轮遗留项在本轮修复后的可达性（用户疑点 6）

### 5.1 P2：`reconnect()` 以 `sessionId === null` 判定「连接已死」的时序竞态

**第 3 轮的推断**：C1 修复让 `onClosed` 早退，「该竞态的触发面会比第 2 轮记录的略宽」。

**复核结论：触发面未变宽，维持「不修」。** 说明如下。

竞态的原始触发条件（第 2 轮 B2）是**同时**满足：

- (a) `connect()` 的 promise 已 resolve 且 `sessionId = 42`，但 `onClosed` 尚未到达 JS；
- (b) 用户此刻触发 reconnect（host key accept 后 `resolveHostKey` → `reconnect()`，或 auth dialog 提交后 `reconnect()`）；
- (c) `disconnectNativeSsh(42)` 晚于 worker 的 `connections.remove(42)` 落到《no-op》；
- (d) 随后排队的旧 `emit.closed()` 到达 JS，`this.destroyed === false`，`onDisconnected` → 关 tab。

C1 的早退只命中 `awaitingUserInput === true` 的情形。而 `reconnect()` 的两个调用点**在调用前都已清掉该 flag**：

| 调用点 | 是否先清 `awaitingUserInput` | 早退是否影响该路径 |
|---|---|---|
| `resolveHostKey:283`（host key accept） | ✅ `:257-258` 在函数第一行清除 | 否——accept 后 `awaitingUserInput` 已为 false |
| `Terminal.tsx:341`（auth dialog submit） | ⚠️ **未清除** | 见下 |

auth dialog submit 这条需要专门论证，因为它确实是「`awaitingUserInput` 仍为 true 时调 `reconnect()`」：

1. `handleAuthDialogSubmit`（`Terminal.tsx:1078-1089`）→ `retryLocalWithCredentials`（`:333-341`）→ `session.overrideCredentials(...)` + `void session.reconnect()`。
2. `reconnect()` → `connect()` → `performConnect():160-161` **第一件事就是** `this.awaitingUserInput = false; this.awaitingUserInputSince = null`。
3. 时序上，`performConnect` 是 `reconnect` 内同步进入、且在 `await getNativeSSHPlan(hostId)` / `await connectNativeSsh(options)` 之前就完成复位。任何从旧 dial 残留的 `emit.closed()` 只有在 **JS 主线程空闲**时才会被投递，而 `reconnect` 的同步段早已跑完复位；若旧 `emit.closed()` 在复位**之前**就被投递，则正是 C1 修复前的那种情况——但此时 tab 会被旧的 `onDisconnected` 关掉，这**就是**第 2 轮 B2 记录的行为，**不是新增行为**。
4. 换言之：早退把「旧 `emit.closed()` 在用户回答前到达」从「关 tab」变成了「忽略」，这正是 C1 的目标；而「旧 `emit.closed()` 在 `performConnect` 复位后到达」这一窗口既未被本轮改动扩大，也未被缩小——`awaitingUserInput` 恰好在那一刻为 false，走的是未改动的旧逻辑。

**结论：C1 的早退只吞掉「用户回答前到达」的旧 `onClosed`（这是好事，正是修复目标），不改变「用户回答后到达」的窗口宽度。竞态触发面不变，维持第 2/3 轮 P2 定级：下轮迭代引入 dial 代际计数器，本轮不列问题。**

> 附带记录一个**理论边界**（不列问题）：`handleError` 的 60s 宽限到期分支会 `this.awaitingUserInput = false` 却不 `notifyFailure` 就直接往下走（`:390-391`）。宽限到期后又来一个非 auth 错误时会正常 `notifyFailure` 关 tab，所以不会悬挂；但若宽限到期后**再也没有任何事件到达**（native 已静默断开），终端会停在断开前的界面而不关 tab。触发需「对话框闲置 60s 以上 + 之后零事件」，且 auth dialog 场景下 `connectionState` 已是 `"disconnected"`（UI 不显示 spinner），用户可观感为「界面停住」而非「卡在 connecting」。属既有设计取舍，非本轮引入。

### 5.2 其余遗留项复核

| 项 | 本轮可达性变化 | 结论 |
|---|---|---|
| 空指纹仍弹空白对话框（第 2/3 轮 B1 残留） | 不变。仍属理论路径（sshj 0.41.1 对 ed25519/rsa/ecdsa/sk-* 全覆盖） | 不列问题 |
| `closedSessions` 量级 / `OnDestroy` 时序（第 2 轮 B3） | 不变 | 不列问题 |
| lint 基线（第 2 轮 B4） | 本轮实跑仍 `0 error / 98 warning`，与第 3 轮一致，未继续增长 | 不列问题 |
| `Emitter.closed()` per-session 去重（第 1 轮 H2 修复） | 早退不产生重复 `onClosed`（`putIfAbsent` 保证每次仅一次投递），无回归 | ✅ |
| `NATIVE_SSH_ERRORS` 死代码常量 | 不变 | 不列问题 |
| Kotlin 侧（本轮零改动） | 无需重编；第 3 轮 `javap` 核验的字节码结论仍有效 | ✅ |

---

## 六、实测核验结果

| 命令 | 结果 |
|---|---|
| `cd /tmp/mobile-check && npx tsc --noEmit -p tsconfig.json` | **exit 0，0 error** |
| `cd /tmp/mobile-check && npx eslint . -f json` | **0 error / 98 warning**（与第 3 轮完全一致，未增长） |
| Kotlin 编译 | **本轮未执行**：`git status` 与文件 mtime（`NativeSshModule.kt` 16:11:56 同其它未改动文件）确认 Kotlin 侧零改动，第 3 轮 `/tmp/ktout_review3b` 的字节码结论继续有效 |

关键事实核验（源码定位，非推测）：

| 事实 | 位置 |
|---|---|
| host key 两分支不写入 `connections` map，故不存在可泄漏的 native 会话 | `NativeSshModule.kt:89`（赋值）vs `:92`/`:96`（两个 catch 在赋值之前） |
| host key 分支已主动断开 half-open transport | `NativeSshModule.kt:94`、`:99` 的 `runCatching { e.client.disconnect() }` |
| `emit.closed()` 无条件执行，故必须由 JS 侧门控拦住 | `NativeSshModule.kt:117`（`finally` 内） |
| `resize`/`write`/`disconnect` 均为幂等 no-op | `NativeSshModule.kt:134`、`:142`、`:159` |
| `sessionId` 仅在 `connectNativeSsh` resolve 后赋值 | `NativeSSHSession.ts:242` |
| 对话框期间 UI 已禁触摸 + IME 不拉起 | `Terminal.tsx:1393-1396`、`Terminal.tsx:1336-1342` + `Sessions.tsx:994-1005` |
| Android `Modal` 由 `RCTModalHostView` 承载，不嵌在本组件 View 树 | `node_modules/react-native/Libraries/Modal/Modal.js:326-358` → `RCTModalHostViewNativeComponent` |
| `HostKeyVerificationDialog` 的返回键出口 | `HostKeyVerificationDialog.tsx:93-99`（`onRequestClose={onReject}`）+ `Modal.d.ts:38` |
| `SSHAuthDialog` 的返回键出口 | `app/components/ui/Dialog.tsx:36-42`（`Modal` + `onRequestClose={onClose}`） |

---

## 七、结论

**可以合并。**

- 第 3 轮阻塞项 **C1 已正确闭合**：`new` / `changed` 两条路径的对话框均能停留（事件 FIFO 保证 `handleHostKey` 先于 `onClosed` 置位 `awaitingUserInput`），Accept / Reject / Android 返回键三条出口全部闭环，不留野连接（native 侧 host key 路径从未登记进 `connections`、且已主动 `disconnect`），无「tab 挂在 connecting」的新死局（60s 宽限 + auth dialog 独立改状态 + unmount cleanup 三重兜底）。
- **auth dialog 场景为改进而非阻塞**，且与 remote 通道行为对齐。
- `destroy()` / unmount cleanup 时序安全，无「已 dispose 但 harness 等待」的悬挂。
- 对话框期间 `sendInput`/`sendResize` 降级为 no-op 是**预期且安全**的行为（socket 已被 native 断开，且 UI 已禁触摸），非本轮引入。
- 第 3 轮 P2 遗留竞态经复核**触发面未变宽**（`reconnect()` 的两个调用点都在调用前或 `performConnect` 首行清掉 `awaitingUserInput`），维持「下轮迭代引入 dial token」结论，本轮不列问题。
- 未发现新的功能性问题；`tsc` 0 error、`eslint` 与第 3 轮持平。

---

*本报告仅描述问题与修复方向，未修改任何代码。*
