package expo.modules.nativessh

import java.security.PublicKey
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Raised when the host key the server presented differs from the pin already
 * held for this host.
 *
 * No longer thrown from inside the verifier. sshj's `HostKeyVerifier.verify()`
 * contract is to return a Boolean: `KeyExchanger.verifyHost()` walks the
 * registered verifiers and only raises `HOST_KEY_NOT_VERIFIABLE` once all of
 * them returned false, and `KeyExchanger.handle()` catches nothing but
 * `GeneralSecurityException`. An exception thrown out of `verify()` therefore
 * travels up to the reader thread and gets wrapped by
 * `TransportException.chainer` into an untyped `TransportException`, so nobody
 * could ever catch it. `openConnection` now reads the verifier's conclusion and
 * translates it into this type, which `NativeSshModule` does catch.
 *
 * `client` is carried so the module can drop the half-open transport; it is not
 * closed here because the throw site is no longer inside sshj.
 */
class HostKeyMismatchException(
  val client: SSHClient,
  val trusted: String,
  val presented: String,
  val keyType: String,
  val algorithm: String,
) : Exception("Host key changed: $presented does not match $trusted")

/**
 * Raised when the server presented a host key that has never been trusted or
 * pinned for this host.
 *
 * See [HostKeyMismatchException] for why this is thrown by `openConnection`
 * rather than by the verifier.
 */
class HostKeyUnknownException(
  val client: SSHClient,
  val fingerprint: String,
  val keyType: String,
  val algorithm: String,
) : Exception("Host key is not trusted yet")

/**
 * Fingerprinting that matches what the Termix server stores for a host and what
 * the web client displays.
 *
 * A stored fingerprint is the SSH wire format encoding of the host's public key
 * — exactly the bytes `Buffer.putPublicKey(key)` emits — rendered as lowercase
 * hex. That is what the server computes at `host-key-verifier.ts:59`
 * (`Buffer.PlainBuffer().putPublicKey(hostkey).getCompactData().toString("hex")`),
 * and the `algorithm` it stores alongside is the literal `"sha256"`
 * (`host-key-verifier.ts:61`). Using any other encoding here (a SHA-256 digest
 * of `key.encoded`, or a base64 blob) would make a pin that the server and the
 * phone both call "the same host key" compare unequal, so the local trust
 * decision could never be shared with the server's.
 */
object HostKeyFingerprints {
  /**
   * Lowercase hex of the SSH wire format public key bytes.
   *
   * Guarded: `putPublicKey` walks `KeyType.fromKey(key)`, and sshj's `UNKNOWN`
   * key type throws `UnsupportedOperationException` rather than encoding. A key
   * sshj cannot encode cannot be compared to a pin either, so it is reported as
   * empty instead of escaping — `verify()` must never throw.
   */
  fun fromKey(key: PublicKey): String =
    runCatching { Buffer.PlainBuffer().putPublicKey(key).getCompactData().toHex() }.getOrElse { "" }

  /**
   * The key's SSH algorithm name — `ssh-ed25519`, `ssh-rsa`,
   * `ecdsa-sha2-nistp256` — matching the server's `getKeyType` and the label the
   * web client shows.
   */
  fun keyType(key: PublicKey): String =
    runCatching { KeyType.fromKey(key).toString() }.getOrElse { key.algorithm }

  /** Always `"sha256"`: the server stores that literal for every key type. */
  fun algorithm(key: PublicKey): String = "sha256"

  /**
   * Canonicalizes a pin to lowercase hex, or null when there is nothing pinned.
   * The server and the local trust store both hold plain hex; case and a
   * historical `SHA256:`/`sha256:` prefix are tolerated.
   */
  fun normalize(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val withoutPrefix = if (trimmed.contains(":")) trimmed.substringAfter(":") else trimmed
    return withoutPrefix.trim().lowercase()
  }
}

/** Lowercase hex encoding of these bytes. */
private fun ByteArray.toHex(): String {
  val digits = "0123456789abcdef"
  val out = StringBuilder(size * 2)
  for (byte in this) {
    val value = byte.toInt() and 0xFF
    out.append(digits[value ushr 4]).append(digits[value and 0x0F])
  }
  return out.toString()
}

/**
 * The conclusion of one host key exchange, as recorded by the verifier.
 *
 * @property fingerprint the presented key, lowercase hex.
 * @property keyType the presented key's SSH algorithm name.
 * @property algorithm always `"sha256"`, mirroring the server's column.
 * @property trusted the pin this attempt compared against; null means no pin was
 *   ever supplied for the host, i.e. a genuinely new key.
 */
class HostKeyOutcome(
  val fingerprint: String,
  val keyType: String,
  val algorithm: String,
  val trusted: String?,
) {
  /**
   * Whether this key satisfied the pin, i.e. the exact verdict `verify()`
   * returned.
   *
   * Both sides are re-normalized rather than compared directly so the verdict
   * cannot drift from the verifier's own comparison if one side is ever stored
   * pre-normalized and the other is not.
   */
  fun isTrusted(): Boolean =
    trusted != null && HostKeyFingerprints.normalize(trusted) == HostKeyFingerprints.normalize(fingerprint)
}

/**
 * Verifies the server's host key against the fingerprint already pinned for this
 * host — by the Termix server, or by a previous in-app trust decision that JS
 * handed back in as the pin.
 *
 * The rules mirror the server-side verifier:
 *
 *  - a pin exists and matches → trust silently
 *  - a pin exists and differs → refuse, so the caller raises a "changed" dialog
 *    (this is what a MITM or a re-imaged host looks like)
 *  - no pin                  → refuse, so the caller raises a "new" dialog; the
 *    user's decision is then stored locally and passed back in as the pin on the
 *    next connect
 *
 * `verify()` never throws. Refusal is expressed as `false`, and *which* rule
 * fired is recorded in [outcome] so `openConnection` can raise the right
 * exception once `connect()` has returned. Throwing instead would be invisible
 * to the caller: see [HostKeyMismatchException].
 *
 * A verifier is created per connection, so [outcome] carries no state across
 * dials.
 */
class PinnedHostKeyVerifier(
  private val trustedFingerprint: String?,
  /**
   * The client this verifier belongs to, carried into the host key exceptions
   * so the caller can drop the half-open transport.
   */
  private val client: SSHClient,
) : HostKeyVerifier {
  /** Read by the caller after `connect()` returns or throws. */
  var outcome: HostKeyOutcome? = null
    private set

  override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
    val presented = HostKeyFingerprints.fromKey(key)
    val trusted = HostKeyFingerprints.normalize(trustedFingerprint)
    outcome = HostKeyOutcome(
      fingerprint = presented,
      keyType = HostKeyFingerprints.keyType(key),
      algorithm = HostKeyFingerprints.algorithm(key),
      trusted = trusted,
    )
    return trusted != null && trusted == presented
  }

  override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
    return mutableListOf()
  }
}
