# 代码审查报告：App 端本地 SSH 通道（第 1 轮）

- **审查仓库**：`/tmp/mobile-check`（Termix-SSH/Mobile v1.5.0，Expo/RN 0.81）
- **审查对象**：本次「手机本地网络拨号 SSH」需求引入的未提交改动（git status 中 `M`/`??` 全部文件）
- **审查日期**：2026-09-29
- **审查方式**：静态阅读 + 对 sshj 0.41.1 源码/JVM class 的调用链推导 + 生产 MySQL 指纹实测 + `tsc --noEmit` 类型检查（0 error）

## 总体结论

**不建议合并。** 共发现 **2 个阻塞项**、6 个高危项、5 个中危项、若干低危建议。

两个阻塞项都集中在 host key 信任链，且都让「本地通道」这一需求的核心安全承诺（fail-closed + 用户确认）**实际无法工作**：

1. `PinnedHostKeyVerifier.verify()` 抛出自定义异常，而 sshj 的 `KeyExchanger.verifyHost()` 只捕获 `GeneralSecurityException`，自定义异常穿透到 Reader 线程 → `TransportImpl.die()` → 链成 `TransportException` 从 `client.connect()` 抛出 → Kotlin 侧 `catch (HostKeyUnknownException)` / `catch (HostKeyMismatchException)` **永不命中**，`onHostKey` 事件永不发出，host key 对话框永不出现。
2. 服务端存储的 `host_key_fingerprint` 是 **hex 编码的 SSH wire 格式公钥字节**，App 端生成的是 `SHA256:base64(sha256(key.encoded))`，两种格式**永不相等**。即使问题 1 修复，所有已 pin 的 host 走本地通道也必然报 changed。生产库 15 台 host 中 3 台已受影响。

---

## 一、阻塞项（Must Fix，合入前必须解决）

### B1. `PinnedHostKeyVerifier` 抛异常导致 sshj 静默替换异常类型，host key 对话框永不触发

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshConnection.kt:92-112`、`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshModule.kt:72-105`、`app/tabs/sessions/terminal/NativeSSHSession.ts:315-330`、`modules/native-ssh/android/src/main/java/expo/modules/nativessh/SessionShims.kt:67-77`

**代码事实**（`HostKeyVerifier` 接口契约为「返回 boolean」）：

```kotlin
override fun verify(hostname: String?, port: Int, key: java.security.PublicKey): Boolean {
  ...
  if (trusted == null) throw HostKeyUnknownException(...)   // 抛异常而非 return false
  ...
  throw HostKeyMismatchException(...)                       // 同上
}
```

**触发路径（已逐层读 sshj 0.41.1 源码推导）**：

1. `KeyExchanger.handle()` 的 `FOLLOWUP` 分支（`KeyExchanger.java:429-441`）：
   ```java
   try {
     if (kex.next(msg, buf)) { verifyHost(kex.getHostKey()); ... }
   } catch (GeneralSecurityException e) { throw new TransportException(...); }
   ```
   `HostKeyVerifier.verify()` 抛出的自定义异常（继承 `Exception`，既非 `GeneralSecurityException` 也非 `RuntimeException`）不被此 catch 捕获，继续向上抛。
2. `verifyHost()` 本身（`KeyExchanger.java:229-248`）在调用 `hkv.verify()` 处**没有任何 try/catch**，循环 `for (hkv : hostVerifiers) if (hkv.verify(...)) return;` 后才是 `throw new TransportException(HOST_KEY_NOT_VERIFIABLE, ...)`。
3. 异常经 `Decoder.decode()` → `Decoder.received()` → `Reader.run()`（`Reader.java:63-67`）：
   ```java
   } catch (Exception e) {
     if (!isInterrupted()) { trans.die(e); }
   }
   ```
4. `TransportImpl.die()`（`TransportImpl.java:617-629`）执行 `SSHException.chainer.chain(ex)`。由于 `HostKeyUnknownException` 不是 `SSHException`，`chain` 走 `else` 分支 `new TransportException(t)`（`TransportException.java:29-34`）——**原始异常被包成 cause，类型信息丢失**。
5. `ErrorDeliveryUtil.alertEvents(causeOfDeath, close, serviceAccept)` + `kexer.notifyError(...)` → `Promise.deliverError` 存入 `pendingEx` → `Event.deliverError` → `KeyExchanger.waitForDone()`（`KeyExchanger.java:170-173`，由 `SSHClient.doKex()` 同步等待）在 `Promise.tryRetrieve` 中 `throw pendingEx`。
6. 结果：`client.connect(options.host, options.port)`（`NativeSshEngine.kt:86`）抛出的是 `TransportException`，消息为 sshj 自己拼的 `Could not verify `ssh-ed25519` host key with fingerprint md5-hex...`，**cause 里的 `HostKeyUnknownException` 已不可达**。

**因此在 `NativeSshModule.kt:93-104` 的 catch 链中**：

```kotlin
catch (e: HostKeyUnknownException) { ... emit.hostKey(...) }   // 永不命中
catch (e: HostKeyMismatchException) { ... emit.error("HOST_KEY_CHANGED", ...) } // 永不命中
catch (e: Throwable) { emit.error(classify(e), e.message) }    // 全部落到这里
```

`classify()` 把 `TransportException`（是 `IOException` 子类）映射为 `IO_ERROR`。

**用户可见后果**：
- 首次连接一台无 pin 的 host → 看不到 host key 对话框，直接弹 `Connection failed: Could not verify ... host key with fingerprint xx:xx:...`，然后 tab 关闭。**本地通道功能不可用**。
- 指纹还是 sshj 的 MD5 hex（`SecurityUtils.getFingerprint`，`SecurityUtils.java:124-137`），与 App/Web 展示的 SHA-256 完全不一致，用户即使想核对也无法核对。
- **fail-closed 语义被破坏**：key 被更换（MITM/主机重装）时，正确行为是拒绝并提示 changed，实际是提示一个含义不明的 verify 失败。`NativeSSHSession.handleError` 收到 `IO_ERROR` → `notifyFailure` → tab 关闭。
- 附带：`AuthPublickey`/`AuthPassword` 这些 sshj 自身的 verifier 不受影响（模块只注册了自己的 verifier，`SSHClient` 不会自动加 `OpenSSHKnownHosts`，因为 `ConfigImpl` 默认 `setHostKeyVerifier` 未被调用；此点已确认无第二 verifier 干扰）。

**修复建议**（二选一，推荐 A）：

- **A（推荐，改动最小且语义清晰）**：`verify()` 内不抛业务异常，改为**返回 false**，同时把结论写进 verifier 实例字段，由 `openConnection` 的调用方在 `connect()` 抛出后读取：
  ```kotlin
  override fun verify(...): Boolean {
    val presented = HostKeyFingerprints.sha256(key)
    this.presentedKey = KeyInfo(presented, ...)
    return trusted != null && normalize(trusted) == presented
  }
  ```
  这样 `verifyHost()` 的循环走完自然 `throw new TransportException(HOST_KEY_NOT_VERIFIABLE)`，`connect()` 抛出后 `NativeSshModule.connect` 的 `catch (e: Throwable)` 里检查 `verifier.presentedKey` 与 `trusted` 是否为 null，分别 `emit.hostKey(... "new")` / `emit.error("HOST_KEY_CHANGED", ...)`。同时保留 `client.disconnect()` 清理。
- **B**：`verify()` 抛 `GeneralSecurityException` 子类，并在 sshj 侧 `catch (GeneralSecurityException)` 处被链成 `TransportException(KEY_EXCHANGE_FAILED, e)`，cause 中保留原异常，`connect()` 后 unwrap `e.cause`。此方案更绕（需 unwrap 两层），不推荐。

**注意**：无论选哪种，都必须同步解决 B2，否则修完对话框能弹、也必然是 changed。

---

### B2. 指纹格式与服务端不兼容，已 pin 的 host 走本地通道必然误报「changed」

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshConnection.kt:30-53`（`HostKeyFingerprints.sha256()` / `normalize()`）；对照服务端 `src/backend/hosts/host-key-verifier.ts:59` 与 `src/backend/database/repositories/host-resolution-repository.ts:300-320`。

**已实测证据**（生产 MySQL，2026-09-29）：

```
SELECT COUNT(*) FROM ssh_data WHERE host_key_fingerprint IS NOT NULL AND <>'';  ->  3
id | host_key_type | host_key_algorithm | CHAR_LENGTH | HEX(前 44 位)
 1 | ssh-ed25519   | sha256             | 102         | 30303030303030623733373336383264363536343332...
 4 | ssh-ed25519   | sha256             | 102         | 同上
 5 | ssh-ed25519   | sha256             | 102         | 同上
```

`3030303030303062...` 的 ASCII 就是 `0000000b7373682d65643235353139...`，即 `0000000b` + `ssh-ed25519` + 长度前缀 + 32 字节公钥。总长 4+11+4+32 = 51 字节 -> hex 102 字符。**服务端存的是 hex(SSH wire format 原始公钥字节)**，来源即 `host-key-verifier.ts:59` 的 `hostkey.toString("hex")`。

而 App 端 `HostKeyFingerprints.sha256()`：

```kotlin
val raw = digest.digest(key.encoded)          // SHA-256
return "SHA256:" + Base64.encodeToString(raw, NO_PADDING or NO_WRAP)
```

`key.encoded` 是 X.509 SubjectPublicKeyInfo 编码，与 `Buffer.putPublicKey` 的 SSH wire 格式也不是同一份字节；且算法是 SHA-256+base64，服务端是纯 hex。`normalize()` 的 `substringAfter(":")` 对 hex 值无意义（hex 里没有 `:`）。

**触发路径**：`NativeSSHSession.performConnect:155-161` -> `resolveKnownHostKey` 优先返回 `serverPin`（hex 值）-> `PinnedHostKeyVerifier.trustedFingerprint = "0000000b..."` -> `verify()` 中 `trustedB64.equals(presented)` 恒为 false（presented 形如 `SHA256:Kx9f...`）-> 抛 `HostKeyMismatchException`。

**影响**：生产库 3/15 台 host 已在服务端 pin 了指纹，这些 host 一旦切到 local 通道，第一跳就失败（修了 B1 后表现为永远弹「host key changed」）。这是需求的核心承诺（复用服务端已有信任）完全失效。

**附证**：服务端 `host-key-verifier.ts:61` 把 `algorithm` 硬编码为 `"sha256"`，但实际格式是 hex——服务端自身存在元数据与实际格式矛盾，说明这不是 App 单方面可以对齐的隐性契约。

**修复建议**（需产品/跨仓决策，报告仅指出方向）：

1. **本地通道与服务端同算法（推荐，改动最小）**：App 端改为 `Buffer.putPublicKey(key).getCompactData()` 后 hex，即与服务端完全一致。同时 `hostKeyAlgorithm` 沿用服务端的 `"sha256"` 或改为 `"hex"` 以免误导。这样服务端 pin 与 App 信任库天然互通，手机和 Web 的 first-seen/changed 状态也一致。
2. **若要 SHA-256 base64**（更符合 OpenSSH 惯例、Web 端展示更友好）：需服务端 `host-key-verifier.ts` 与 `host-resolution-repository.ts` 同时改为 `SHA256:` + base64，并做存量 3 条数据的迁移。**属于跨仓库契约变更 + 数据迁移，需用户决策**，不应在 App 侧单方面实现。

---

## 二、高危项（High，功能不可用或明显逻辑错误）

### H1. host key 接受后未持久化，重连进入死循环

**位置**：`app/tabs/sessions/terminal/Terminal.tsx:191-195, 1236-1238, 1575-1586`、`app/tabs/sessions/terminal/NativeSSHSession.ts:190-209`

**问题**：`hostKeyVerification` state 的类型声明里有 `fingerprint?: string`，但**唯一的写入点**从不填它：

```tsx
onHostKeyVerificationRequired: (scenario, data) => {
  setHostKeyVerification({ scenario, data });   // fingerprint 恒为 undefined
},
```

于是对话框回调：

```tsx
onAccept={() => { respondToHostKey(true, hostKeyVerification?.fingerprint); ... }}
```

`NativeSSHSession.resolveHostKey(accept, undefined)`：

```ts
if (fingerprint) {                       // <- undefined，整段跳过
  this.acceptedHostKey = fingerprint;
  await storeHostKeyFingerprint(host, fingerprint);
}
await this.reconnect();
```

**触发路径**：用户点「接受」-> `storeHostKeyFingerprint` 不执行（SecureStore 不留痕）-> `acceptedHostKey` 仍为 null -> `reconnect()` 重新 `performConnect()` -> `resolveKnownHostKey` 仍返回 null（服务端无 pin）-> 再次 unknown -> 再次弹窗 -> **无限循环**（B1 未修时表现为连续失败）。

**附带说明**：`HostKeyData`（`NativeWebSocketManager.ts:33-42`）本身**已含 `fingerprint`/`keyType`/`algorithm`**，`NativeSSHSession.handleHostKey` 也已把它们放进 `data`。所以数据是齐的，只是 state 没接住。

**修复建议**：`onHostKeyVerificationRequired: (scenario, data) => setHostKeyVerification({ scenario, data, fingerprint: data.fingerprint })`。`TerminalTransport.ts:53-55` 的 `onHostKeyVerificationRequired` 签名无需改（`data` 已带 fingerprint），dialog 回调直接传 `data.fingerprint` 更直接。

---

### H2. `write` 失败与 `pumpOutput` 收尾重复 emit `closed`，导致 UI 层重复动作

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshModule.kt:116-132`、`SessionShims.kt:83-98`

**问题**：`write` 失败分支自行收尾：

```kotlin
} catch (_: Throwable) {
  if (connections.remove(sessionId) != null) {
    connection.close(); connection.scrubCredentials()
    Emitter(sessionId).closed()          // (1) 手动 emit
  }
}
```

但同一时刻 `pumpOutput` 仍阻塞在 `input.read(buffer)`。`close()` 后 read 返回 -1 或抛 IOException -> `pumpOutput` 退出循环 -> `emit.state("disconnected")` + (2) `emit.closed()`。同时 worker 的 `finally` 里 `connections.remove(id)` 返回 null（已被 write 移除）-> 不重复 close，但仍执行 `emit.closed()`（`NativeSshModule.kt:101-104`，**该行在 finally 中无条件执行**）-> (3)。

**触发路径**：远端在用户敲键过程中断开 -> 三个 `onClosed` 事件到达 JS -> `NativeSSHSession` 的 `onClosed` 回调执行 3 次：

```ts
onClosed: () => {
  if (this.destroyed) return;
  this.sessionId = null;
  this.shouldNotReconnect = true;
  this.config.onDisconnected(this.config.hostConfig.name);   // <- 执行 3 次
},
```

`Terminal.tsx:1246-1251` 的 `onDisconnected` -> `showToast.warning(...)` + `if (onClose) onClose()`，即**弹 3 次 toast、调 3 次关 tab**。`onClose` 三次触发 `TerminalSessionsContext.removeSession`，第三次 `sessionToRemove` 已找不到会 early return，不崩溃但属多余抖动。

**修复建议**：加 per-session closed-once 语义。最简做法是把 `Emitter` 的 `closed()` 改为幂等（`@Volatile private var closedEmitted = false`），并让 `pumpOutput` 退出、`write` 失败、worker `finally` 三处复用同一个 emitter 实例（目前 `write` 分支和 finally 各自 new/复用 emitter，需统一）。另外 `write` 失败只需 `close()` + `scrubCredentials()`，把 emit 交给 `pumpOutput` 自然收尾，无需手动 emit。

---

### H3. auth dialog 输入的凭据在重连时被服务端 plan 覆盖，弹窗循环

**位置**：`app/tabs/sessions/terminal/NativeSSHSession.ts:133-152`（`performConnect`）、`215-226`（`overrideCredentials`）、`232-244`（`reconnect`）

**问题**：`overrideCredentials` 把用户输入的密码写进 `this.config.hostConfig`：

```ts
this.config.hostConfig = { ...this.config.hostConfig, authType, password, key, keyPassword };
```

但 `performConnect` 对已存 host（`hostId > 0`）**仍然优先取服务端 plan 并整体覆盖**：

```ts
const plan = hostId > 0 ? await getNativeSSHPlan(hostId) : null;
const credentials = plan ?? { authType: hostConfig.authType, password: hostConfig.password ?? null, ... };
```

`plan` 非 null，用户刚输入的密码被丢弃。（fallback 分支带了 `hostKeyFingerprint` 而 plan 分支不带，这一点没问题，plan 自身带该字段；问题纯粹是覆盖。）

**触发路径**：host 在服务端密码是旧的/错的（用户在 auth dialog 里输入正确密码）-> `retryLocalWithCredentials` -> `overrideCredentials` + `reconnect()` -> `getNativeSSHPlan` 返回旧密码 -> 再次 auth 失败 -> 再次弹 auth dialog -> 循环。

**修复建议**：引入 `overriddenCredentials` 字段。`overrideCredentials` 只写该字段（不改 `hostConfig`，避免污染其他消费方），`performConnect` 改为：

```ts
const plan = hostId > 0 && !this.overriddenCredentials ? await getNativeSSHPlan(hostId) : null;
const credentials = this.overriddenCredentials ?? plan ?? { ... };
```

并在 `destroy()` 时清空该字段。

---

### H4. `resize`/`write` 抛未处理 rejection

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshModule.kt:108-132`、`app/tabs/sessions/terminal/NativeSSHSession.ts:255-270`、`modules/native-ssh/index.ts:194-202`

**问题**：Kotlin 侧 session 不存在时抛 `CodedException`：

```kotlin
AsyncFunction("resize") { sessionId, cols, rows ->
  val connection = connections[sessionId] ?: throw SshSessionNotFound()
  ...
}
```

JS 侧 `resizeNativeSsh` / `writeNativeSsh` **没有 try/catch**（`index.ts:194-202` 直接 `await NativeSsh?.resize/write(...)`），也没有像 `getHostPassword` 那样做 404 -> null 的容错转换。`NativeSSHSession.sendResize` 虽有 `.catch(() => {})`，但它是在判断 `this.sessionId !== null` 之后调用的——而 `sessionId` 是 JS 侧记录，与 Kotlin `connections` map 存在竞态：`onClosed` 经 `ModuleEventSink` 的 main handler 异步投递，`sessionId = null` 之前 `connections` 可能已被 `write` 失败分支移除。此窗口内 `sendResize` 通过检查、随后 `resize` 抛异常。

`sendInput` 的注释声称「A write to a vanished channel is not worth surfacing」，说明作者预期这条路径应静默，但 Kotlin 抛异常 + JS 吞异常的组合依赖时序，属隐性契约。

**修复建议**：Kotlin 侧把「session 不存在」从异常改为幂等 no-op（`?: return@AsyncFunction Unit`），并在 `index.ts` 的两个函数外层加 try/catch，与 `disconnectNativeSsh` 的宽容策略一致。

---

### H5. 同一 host 重连不销毁旧 transport，多个 NativeSSHSession 同时存活

**位置**：`app/tabs/sessions/terminal/Terminal.tsx:1261-1265`

```tsx
// eslint-disable-next-line react-hooks/exhaustive-deps
}, [hostConfig.id]);
```

**问题**：transport 创建 effect 的依赖只有 `hostConfig.id`。任何**不改变 id 但需要重建 transport 的情形**（例如同一 tab 内从 remote 切到 local、或 `hostConfig` 其他字段变化后需要重连）都不会销毁旧 transport：旧 `NativeSSHSession` 的 `NativeSshSubscription` 未 dispose、原生 SSH 连接仍在，两个 session 同时收事件。

`NativeSSHSession.destroy()` 只 disconnect 自己的 `sessionId`，且 `disconnectAllNativeSsh`（`modules/native-ssh/index.ts:213-215`）**在整个 `app/` 中零调用点**（已 grep 确认）。因此 native 侧连接在 Terminal 卸载后仅靠 `onClosed` 兜底，`OnDestroy` 前没有主动清理；App 退到后台长时间不回来时，worker 线程 + socket 会一直挂着。

**附带确认**（用户最担心的点，**当前版本已不成立**）：仓库里 `subscribeNativeSshEvents` / `unsubscribeAllNativeSshEvents` 已不存在，`NativeSshSubscription` 是 per-instance 且 `dispose()` 幂等（`modules/native-ssh/index.ts:131-178`），多个 tab 不会互相取消订阅。这一点是好的，报告予以确认。

**修复建议**：effect 依赖加入 `hostConfig.connectionOrigin`（以及 `tabInstanceId`，若语义上每个 tab instance 应有独立 transport）；并在 Terminal 卸载 cleanup 里对 `NativeSSHSession` 调 `disconnectAllNativeSsh()`，或增加 AppState `background` 时的批量清理钩子。

---

### H6. `expo-module.config.json` 声明 iOS 平台但无 iOS 实现，iOS 构建会失败

**位置**：`modules/native-ssh/expo-module.config.json`

```json
{ "platforms": ["ios", "android"], "ios": { "modules": ["NativeSshModule"] } }
```

仓库 `modules/native-ssh/` 下**没有 `ios/` 目录**（已 `find` 确认全部文件列表：只有 android + index.ts + package.json + config），也没有 `.podspec`。同仓库另三个本地模块（hardware-keyboard 等）都有对应平台目录。`eas.json` 中配置了 ios development/preview build profile。

**影响**：`expo run:ios` / EAS iOS build 在 autolinking 阶段会因找不到 `NativeSshModule` 的 iOS 实现而失败，**阻塞本仓库的 iOS 发布链路**——即使本次需求只针对安卓。

**修复建议**：把 `platforms` 改为 `["android"]` 并删除 `ios` 段。若团队计划后续补 iOS 实现，也应在当前 PR 里显式标注为未完成，而不是让构建在错误信息里暴露。

---

## 三、中危项（Medium）

### M1. `getNativeSSHPlan` 的 404 与其它错误不可区分

**位置**：`app/main-axios.ts:3283-3322`（`getNativeSSHPlan`）、`app/tabs/sessions/terminal/NativeSSHSession.ts:142`

`getNativeSSHPlan` 的 catch 分支调用 `handleApiError`，而后者的返回类型是 `never`，因此 404（host 已删/无权限）、500、网络断**全部以 `ApiError` 形式抛出**。`performConnect` 里 `await getNativeSSHPlan(hostId)` 无 try/catch，异常冒到 `connect()` 的 `.catch` -> `notifyFailure(errorMessage(error))`。用户看到的是「fetch native SSH connection plan: Request failed with status code 404」这类内部措辞，而不是「该主机不存在或你无权访问」。对照 `getHostPassword`（`main-axios.ts:3171-3177`）显式把 404 转 null 的处理，两者策略不一致。

**建议**：`getNativeSSHPlan` 对 404 返回 null（或抛一个带 `code: "HOST_NOT_FOUND"` 的语义错误），调用方分别处理「主机不存在」与「网络不可达」。

### M2. `openConnection` 完全忽略 `ConnectOptions.authType`

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshEngine.kt:62-142`、`ConnectOptions.kt:18`

`authType` 只被声明并加了注释，`openConnection` 只看 `privateKey`/`password` 是否存在来构造 `AuthMethod` 列表：

```kotlin
val auths: List<AuthMethod> = buildList {
  if (keyProvider != null) add(AuthPublickey(keyProvider))
  if (password != null) add(AuthPassword(...))
}
```

**影响**：

- `authType: "credential"`：服务端已把凭据合并进 plan 的 password/key，功能正常（注释已说明）。
- `authType: "none"` 但 plan 仍带 key（异常数据）：会尝试 key 认证，行为与 Web 端（服务端按 authType 裁剪）不一致。
- `authType: "none"` 且无凭据：抛 `IllegalArgumentException("No credentials supplied...")` -> `classify` 映射为 `INVALID_CREDENTIALS` -> 弹 auth dialog。可接受但错误码语义偏。
- `authType: "key"` 且服务端 plan 的 `keyPassword` 为空但私钥有 passphrase：`buildKeyProvider` 失败 -> `IllegalArgumentException("Could not read the private key")` -> `INVALID_CREDENTIALS`，与「需要 passphrase」的真实原因不符，用户被引到 auth dialog 重输密码而非 passphrase 对话框。

**建议**：至少用 `authType` 做 sanity check（`"password"` 但无 password、`"key"` 但无 privateKey 时给出明确错误码），并在 `classify` 中为 `IllegalArgumentException` 区分子类型。

### M3. QuickConnect local 模式每次连接都重新弹 host key 对话框

**位置**：`app/tabs/hosts/QuickConnect.tsx:61`（`id: -Date.now()`）、`app/tabs/sessions/terminal/host-key-trust.ts:35-38`

`hostKeyStoreKey = "termix.hostkey.${id}:${ip}:${port}"`，而 QuickConnect 的临时 host id 是 `-Date.now()`，每次连接都不同。于是即使对同一个 `ip:port` 上次已「接受并信任」，下次仍要求重新确认。功能正确但体验割裂，且与「信任库按 host 身份去重」的设计意图相悖（`HostIdentity` 的注释明确说要支持 transient host）。

**建议**：对 `id <= 0` 的 host，key 改为 `termix.hostkey.transient:${ip}:${port}:${username}`。

### M4. `HostForm` 编辑态：SSH plan 请求失败时清空已存凭据显示

**位置**：`app/tabs/hosts/HostForm.tsx:209-227`

```ts
Promise.allSettled([
  getNativeSSHPlan(host.id),
  getHostPassword(host.id, "rdpPassword"), ...
]).then(([ssh, rdp, vnc, telnet]) => {
  setForm((f) => ({
    ...f,
    password: ssh.status === "fulfilled" ? (ssh.value.password ?? "") : "",
    key:     ssh.status === "fulfilled" ? (ssh.value.key ?? "") : "",
    keyPassword: ssh.status === "fulfilled" ? (ssh.value.keyPassword ?? "") : "",
    ...
```

**影响**：`getNativeSSHPlan` 因 404/500/网络断 rejected 时，`password`/`key`/`keyPassword` 一律被置为 `""`。用户打开编辑页看到空密码，可能误以为没配而重填；若随手保存会以空串提交。**好消息（已核对服务端）**：`host.ts:1133-1141`（update 路径）是 `if (password) sshDataObj.password = password`，空串不会清空服务端数据，所以**不会造成数据破坏**。但 create 路径 `host.ts:435` 是 `password || null`，新 host 场景下空串会写入 null——不过这属于合理行为。

这是 UX/正确性隐患而非数据 bug，定级中。

**建议**：`ssh.status === "rejected"` 时**保留原表单值不清空**（`f.password`），并 toast 提示「无法加载已保存的凭据」。

### M5. `keyType`/`algorithm` 语义与服务端、Web 端不一致

**位置**：`modules/native-ssh/android/src/main/java/expo/modules/nativessh/NativeSshConnection.kt:38-40`

```kotlin
fun keyType(key: PublicKey): String = key.algorithm          // 例如 "EdDSA"
fun algorithm(key: PublicKey): String = "sha256"             // 硬编码
```

`key.algorithm` 在 java 侧对 ed25519 返回 `"EdDSA"`，而服务端 `host-key-verifier.ts` 的 `getKeyType` 与 Web 端展示的通常是 `"ssh-ed25519"`。同一个 host 在 Web 端 host key 对话框显示 `ssh-ed25519`，在手机本地通道显示 `EdDSA`，用户核对时对不上。且 `algorithm` 恒为 `"sha256"` 与实际 hex 格式自相矛盾（见 B2）。

**建议**：`keyType` 改为与服务端一致的 SSH 算法名（`ssh-ed25519` / `ssh-rsa` / `ecdsa-sha2-nistp256`），可通过 sshj 的 `KeyType.fromKey(key).toString()` 取得（`KeyExchanger.java:238` 就是这么用的）；`algorithm` 字段随 B2 的格式决策一并修正。

---

## 四、低危项 / 建议（Low & Suggestions）

### L1. `awaitingUserInput` 早退可能吞掉后续真实错误

`NativeSSHSession.ts:294-301`：`handleError` 在 `awaitingUserInput` 为 true 时直接 return。若 dialog 被用户以某种方式关闭而未走 `resolveHostKey`/`overrideCredentials`（例如 host key 对话框在 Terminal.tsx 里被 `setHostKeyVerification(null)` 清掉但 `resolveHostKey` 尚未返回），该 session 后续所有错误都会被静默。建议在 dialog 的 cancel/onReject 分支确保把一个终止信号发回 session。

### L2. `TerminalTransport.ts` 的 `TerminalTransportEvents` 是死代码

`TerminalTransport.ts:36-58` 定义了完整的事件接口，但全仓库无引用（两个 transport 实际都用 `NativeWSConfig`）。建议删除或在两个实现中真正采用它，避免「两份接口漂移」的长期风险。

### L3. `pumpOutput` 的 buffer 拷贝

`SessionShims.kt:83-98`：每次迭代 `carry.toByteArray()` 分配整个缓冲 + `carry.reset()` + `carry.write(remainder)` 再拷一次。8KB 缓冲、每 16ms 刷一次的量级下性能可接受；若后续要提高输出吞吐，建议改为带 offset 的双缓冲。

### L4. `ModuleEventSink` 与模块生命周期

`ModuleEventSink.kt:19` 用 `NativeSshModule.instance?.sendEvent(...)` 空安全调用，`OnDestroy` 后已排队的 main handler 任务不会崩。**已确认无泄漏/无崩溃风险**。仅建议在 `OnDestroy` 里额外 `mainHandler.removeCallbacksAndMessages(null)` 以减少无用工作。

### L5. `HostForm` 未预填 `sudoPassword`

编辑态预填只覆盖 SSH/RDP/VNC/Telnet。`sudoPassword` 未预填——但旧的 `getSSHHostWithCredentials` 也不返回该字段，**非本次回归**，仅记录。

---

## 五、经核查未发现问题的点（明确结论，避免后续重复排查）

以下均是重点关心的方向，已逐条读代码或手工推导确认**无功能性 bug**：

1. **`decodeUtf8` 算法正确**（`SessionShims.kt:100-151`）。手工推导用例：
   - 纯 ASCII `[0x41,0x42]`：index=2，b=0x42 非 continuation，expected=1，available=2 >= 1 -> 全解。
   - 2 字节截断 `[0x41,0xC3]`：index=1（floor=0），b=0xC3 -> expected=2，available=1 < 2 -> 解 `"A"`，remainder=`[0xC3]`。
   - 3 字节跨块 `[0xE4,0xB8]`：index 从 2 退到 1（0xB8 是 continuation），b=0xE4 -> expected=3，available=2 < 3 -> 解 `""`，remainder 两字节。
   - 4 字节差一字节 `[0xF0,0x9F,0x98]`：同理退到 lead byte，remainder 3 字节。
   - 连续多字节 `[0xE4,0xB8,0xAD,0xE6,0x96]`：index 从 5 退到 4（0x96 continuation），b=0xE6 -> expected=3，available=2 < 3 -> 正确解出 `"中"`，remainder 2 字节。
   - `floor = size-3` 对 UTF-8 最长 4 字节成立，`carry` 永不超过 3 字节，无漏判。

2. **remote（WebSocket）通道的对话框交互无回归**。逐项核对：
   - `respondToHostKey`（`Terminal.tsx:1057-1066`）在 remote 下走 `session?.sendHostKeyResponse?.("accept"|"reject")`，与改造前 `wsManagerRef.current?.sendHostKeyResponse(...)` 等价。
   - `PassphraseDialog` / `WarpgateDialog` 的 `visible` 增加的 `&& !isLocalChannel` 不影响 remote：`isLocalChannel` 初值 false，仅 local 分支 `setIsLocalChannel(true)`。
   - `sendTransportPrompt` 的 `isLocalChannel` 早退同理不影响 remote；remote 下仍是 `transportRef.current?.sendTotpResponse?.(...)`。
   - `HostKeyVerificationDialog` 的 `onReject` 带 `if (onClose) onClose()`：与改造前一致（该分支原本就存在），非本次引入。
   - `connectionOrigin` 缺省/null/"remote" 一律走 `NativeWebSocketManager`（`Terminal.tsx:1261-1264` 的 `usesLocalChannel = hostConfig.connectionOrigin === "local"`），与改造前行为一致。`buildProtocolFields` 的 `connectionOrigin` 只在 "local"/"remote" 时透传、否则 null，不会把旧 host 误判为 local。

3. **事件订阅无模块级单例问题**。`modules/native-ssh/index.ts:131-178` 的 `NativeSshSubscription` 是 per-instance 类，`cleanups` 数组 + `disposed` 标志幂等；`app/` 中已无任何 `subscribeNativeSshEvents`/`unsubscribeAllNativeSshEvents` 调用点（已 grep 确认）。**多 tab 场景不会互相取消订阅或丢失事件**——这是用户最担心的点，当前版本已正确。

4. **open-tabs 不注册不会导致 ConnectionsPanel 崩溃**。`TerminalSessionsContext.tsx:205-213` 跳过 `addOpenTab`、`248-255` 跳过 `deleteOpenTab`；`ConnectionsPanel.tsx:311-313` 的 `isLive = live?.isConnected ?? !!s.backendSessionId`，local session `sessionByInstance` 取不到时回退到 `backendSessionId`（local 为 null）-> 显示灰点。**是功能瑕疵（local 终端恒显示为「非活动」）而非崩溃**，属可接受的设计取舍，建议在文档中注明 local 会话不参与跨设备接管。

5. **凭据生命周期基本合规**。明文密码/私钥只经 `getNativeSSHPlan` 在 connect 时从内存态 `plan` 取出，传入 native 后 `OpenConnection` 持有 `CharArray`，`scrubCredentials()` 对该密码做 `fill` 清零（`NativeSshEngine.kt:44-48`）；未落 AsyncStorage；`ConnectOptions.kt:6-11` 与 `host-key-trust.ts:25-29` 的注释均声明 SecureStore-only，代码与之相符。SecureStore key 形如 `termix.hostkey.${id}:${ip}:${port}`，**不会串**（同一 host 的 id+ip+port 组合稳定）。`getStoredHostKeyFingerprint` 的 catch 返回 null（fail-closed，不会退化成信任一切），`storeHostKeyFingerprint` 的 catch 静默（信任可丢失但不致命）。`keychainAccessible: WHEN_UNLOCKED_THIS_DEVICE_ONLY` 正确避免备份恢复导致的信任串设备。

6. **`getNativeSSHPlan` 的端点存在性**。服务端 `/db/host/{id}/native-ssh`（已注册于 `host.ts:2598`）返回 `{id, authType, password, key, keyPassword, keyType, hostKeyFingerprint, hostKeyType, hostKeyAlgorithm}` 并按 authType 裁剪，与 App 侧 `NativeSSHPlan` 接口字段一一对应。旧代码调用的 `/db/host/{id}/with-credentials` 确实不存在（已 grep `app/` 仅剩注释提及），替换是正确的。

7. **`connection_origin` 不跨设备同步**。`sync.ts` 将其标记为 readOnlyFields，因此手机上改成 local 不会影响 Web 端，符合预期。

8. **`tsc --noEmit` 0 error**（在 `/tmp/mobile-check` 下执行），新文件无类型错误。

---

## 六、建议的修复优先级

| 优先级 | 项 | 理由 |
|---|---|---|
| P0 | B1 | 本地通道核心不可用；安全语义失效 |
| P0 | B2 | 需跨仓/存量数据决策，越早定越好（决定 B1 修法） |
| P1 | H1 | 修了 B1 后立刻暴露的死循环 |
| P1 | H2 | 重复事件导致 UI 抖动，用户可感知 |
| P1 | H3 | auth dialog 重输凭据无效，用户可感知 |
| P1 | H4 | 潜在 unhandled rejection |
| P1 | H5 | 连接/订阅残留，长时间使用后表现劣化 |
| P1 | H6 | 阻塞 iOS 构建链路 |
| P2 | M1-M5 | 错误信息质量与体验一致性 |

---

*本报告仅描述问题与修复方向，未修改任何代码。*
