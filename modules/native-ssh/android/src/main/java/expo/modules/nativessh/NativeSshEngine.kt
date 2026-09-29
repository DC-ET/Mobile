package expo.modules.nativessh

import java.io.InputStream
import java.io.OutputStream
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Session.Shell as SessionShell
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Everything a dialed connection carries, returned to the module for bookkeeping. */
internal class OpenConnection(
  val client: SSHClient,
  val session: Session,
  val shell: SessionShell,
  val shellIn: OutputStream,
  val shellOut: InputStream,
  private val password: CharArray?,
  private val keyProvider: KeyProvider?,
) {
  @Volatile var closing = false

  /**
   * Closes the channel and the transport.
   *
   * Idempotent: the read loop notices EOF and the JS side may also call
   * disconnect explicitly, and sshj throws on a second close.
   */
  fun close() {
    if (closing) return
    closing = true
    runCatching { shell.close() }
    runCatching { session.close() }
    runCatching { client.disconnect() }
  }

  /**
   * Overwrites the credential material this connection was built from.
   *
   * The parsed key provider holds decrypted private-key bytes in memory, so
   * dropping the reference is not enough — zero it before letting the object go.
   */
  fun scrubCredentials() {
    // The key path cannot be zeroed from here: sshj exposes no accessor for the
    // decoded key bytes, so the provider is simply dropped with the connection.
    // The password path is fully clearable, so do that explicitly.
    password?.fill('\u0000')
  }
}

/**
 * Dials the host and opens an interactive shell, or throws.
 *
 * Kept separate from the Expo module so the protocol flow stays readable: the
 * module owns session bookkeeping and event dispatch, this owns SSH.
 *
 * Auth precedence is intentional. A host stored with `authType: "credential"`
 * references a stored credential the server resolves for us, so by the time a
 * plan reaches this function the credential's material has already been merged
 * into `password`/`privateKey` by the server and we only need to look at what
 * is actually present.
 */
internal fun openConnection(options: ConnectOptions): OpenConnection {
  val client = SSHClient()

  // A dial may not hang the worker thread (and therefore the terminal) forever.
  client.connectTimeout = CONNECT_TIMEOUT_MS

  // Fails closed: a host key that is not pinned is refused, and the caller is
  // asked to confirm it. The server's own pin is the fast path; a key the user
  // accepted in-app arrives the same way on the next attempt.
  //
  // The verifier is held onto rather than passed inline: sshj only wants a
  // Boolean out of verify(), so what it observed about the presented key is read
  // back from `outcome` once connect() has settled. See
  // PinnedHostKeyVerifier for why it cannot report by throwing.
  val hostKeyVerifier = PinnedHostKeyVerifier(
    trustedFingerprint = options.knownHostKey,
    client = client,
  )
  client.addHostKeyVerifier(hostKeyVerifier)

  try {
    client.connect(options.host, options.port)
  } catch (e: Throwable) {
    // sshj reports an untrusted key as a plain TransportException ("Could not
    // verify `ssh-ed25519` host key ..."), which is indistinguishable from any
    // other transport failure. Only the verifier knows what the key exchange
    // actually concluded, so translate here, where the module's own catch
    // branches can see the difference.
    val outcome = hostKeyVerifier.outcome
    if (outcome == null || outcome.isTrusted()) {
      // Either key exchange never ran (TCP refused, DNS, timeout) or the key did
      // check out and something later in the handshake failed. Neither is a host
      // key decision, so the original error keeps its own error code.
      runCatching { client.disconnect() }
      throw e
    }
    // Not trusted: the reason decides between "new" and "changed". The client is
    // left open deliberately — NativeSshModule disconnects it in its catch.
    if (outcome.trusted == null) {
      throw HostKeyUnknownException(
        client = client,
        fingerprint = outcome.fingerprint,
        keyType = outcome.keyType,
        algorithm = outcome.algorithm,
      )
    }
    throw HostKeyMismatchException(
      client = client,
      trusted = outcome.trusted,
      presented = outcome.fingerprint,
      keyType = outcome.keyType,
      algorithm = outcome.algorithm,
    )
  }

  // From here on the transport is up, so every way out of this block has to
  // take it down. `NativeSshModule` cannot do it for us: this function has not
  // returned yet, so the session id it would clean up is still unmapped and
  // `connections.remove(id)` finds nothing. sshj offers no safety net either —
  // its reader is a daemon thread and SSHClient has no finalizer — so a client
  // left connected here leaks the socket for the life of the process.
  val password: CharArray? = options.password?.toCharArray()
  return try {
    val keyProvider = buildKeyProvider(options)

    val auths: List<AuthMethod> = buildList {
      if (keyProvider != null) add(AuthPublickey(keyProvider))
      if (password != null) {
        add(AuthPassword(PasswordUtils.createOneOff(password)))
      }
    }

    if (auths.isEmpty()) {
      throw IllegalArgumentException("No credentials supplied for SSH authentication")
    }

    client.auth(options.username, auths)

    val session = client.startSession()
    session.allocatePTY(
      "xterm-256color",
      options.cols.coerceAtLeast(1),
      options.rows.coerceAtLeast(1),
      /* widthPx = */ 0,
      /* heightPx = */ 0,
      emptyMap(),
    )

    // Streams are grabbed before startShell, which swaps the channel onto the
    // live connection.
    val shellIn: OutputStream = session.outputStream
    val shellOut: InputStream = session.inputStream
    // The Shell view is what carries resize; the Session reference alone cannot
    // change the window size, so keep the handle returned by startShell.
    val shell = session.startShell()

    OpenConnection(
      client = client,
      session = session,
      shell = shell,
      shellIn = shellIn,
      shellOut = shellOut,
      password = password,
      keyProvider = keyProvider,
    )
  } catch (e: Throwable) {
    // The transport (and any half-opened session) dies here; the caller keeps
    // reporting the original failure.
    runCatching { client.disconnect() }
    throw e
  }
}

/**
 * Parses a PEM/OpenSSH private key body, or returns null when the host
 * authenticates some other way.
 *
 * Reading from an in-memory reader rather than a temp file keeps the plaintext
 * key off the filesystem entirely.
 */
internal fun buildKeyProvider(options: ConnectOptions): KeyProvider? {
  val body = options.privateKey?.takeIf { it.isNotBlank() } ?: return null
  val passphrase =
    options.keyPassphrase?.takeIf { it.isNotBlank() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }

  val reader = java.io.StringReader(body)
  val formats = listOf<(java.io.Reader, net.schmizz.sshj.userauth.password.PasswordFinder?) -> KeyProvider>(
    { r, p -> OpenSSHKeyFile.Factory().create().apply { init(r, p) } },
    { r, p -> PKCS8KeyFile.Factory().create().apply { init(r, p) } },
  )

  var lastError: Throwable? = null
  for (format in formats) {
    try {
      return format(java.io.StringReader(body), passphrase)
    } catch (e: Throwable) {
      lastError = e
    }
  }

  throw IllegalArgumentException(
    "Could not read the private key for this host",
    lastError,
  )
}
