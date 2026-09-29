package expo.modules.nativessh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** How long a dial may take before the worker gives up. */
internal const val CONNECT_TIMEOUT_MS = 15_000

/** Read timeout on the shell stream; 0 means block until data or EOF. */
internal const val IO_TIMEOUT_MS = 0

/**
 * Marshals events for one session onto the JS bridge.
 *
 * Every event is posted from whichever thread the SSH layer happened to be on,
 * so the emitter is the single place that knows the session id.
 *
 * `closed()` is deduplicated per session id rather than per instance: a session
 * can be torn down from three different places (the read loop unwinding, a
 * failed write, the worker's `finally`) and each used to create its own
 * emitter, so per-instance state would let the same `onClosed` reach JS more
 * than once. JS turns `onClosed` into a toast plus a tab close, so a duplicate
 * is user-visible.
 */
internal class Emitter(private val sessionId: Int) {
  fun data(chunk: String) {
    NativeSsh.events.post("onData", mapOf("sessionId" to sessionId, "data" to chunk))
  }

  fun state(state: String) {
    NativeSsh.events.post("onStateChange", mapOf("sessionId" to sessionId, "state" to state))
  }

  /**
   * Announces a host key the phone cannot decide on alone.
   *
   * @param trusted the pin this key was compared against, when there was one.
   *   Only carries the "changed" case, whose dialog shows the previous value.
   */
  fun hostKey(
    fingerprint: String,
    keyType: String,
    algorithm: String,
    scenario: String,
    trusted: String? = null,
  ) {
    NativeSsh.events.post(
      "onHostKey",
      mapOf(
        "sessionId" to sessionId,
        "fingerprint" to fingerprint,
        "keyType" to keyType,
        "algorithm" to algorithm,
        "scenario" to scenario,
        "trusted" to trusted,
      ),
    )
  }

  fun error(code: String, message: String) {
    NativeSsh.events.post(
      "onError",
      mapOf("sessionId" to sessionId, "code" to code, "message" to message),
    )
  }

  fun closed() {
    // First caller wins; later callers for the same session are swallowed.
    // putIfAbsent keeps the claim atomic, so two threads racing toward the same
    // teardown (the read loop unwinding vs. a failed write) still produce
    // exactly one onClosed.
    if (closedSessions.putIfAbsent(sessionId, Unit) == null) {
      NativeSsh.events.post("onClosed", mapOf("sessionId" to sessionId))
    }
  }

  companion object {
    /**
     * Session ids whose `onClosed` has already been claimed.
     *
     * Entries are never dropped while a session is live: ids come from a
     * per-module counter and the marker has to outlive every emitter for that
     * id, including one still in flight on the write path. Dropping it there
     * would reopen the duplicate this exists to close.
     */
    private val closedSessions = ConcurrentHashMap<Int, Unit>()

    /**
     * Resets the dedup state, for when the module is destroyed and a future
     * instance takes over.
     *
     * Needed because `nextSessionId` is a field of the module instance, not a
     * process-global: a recreated module hands out id 1 again, and a leftover
     * marker would swallow that session's onClosed. Lifetime is bounded by
     * this reset, so the map cannot grow across app restarts either.
     */
    fun forgetAll() {
      closedSessions.clear()
    }
  }
}

/**
 * Singleton event sink, wired by the module when it starts.
 *
 * Kept separate from the module class so Kotlin code that has no Expo context
 * (the connection engine, the host key verifier) can still report back. When
 * the module is gone the events are dropped: there is no JS listener left.
 */
internal object NativeSsh {
  @Volatile
  var events: EventSink = NoopEventSink
}

internal interface EventSink {
  fun post(event: String, payload: Map<String, Any?>)
}

internal object NoopEventSink : EventSink {
  override fun post(event: String, payload: Map<String, Any?>) = Unit
}

/**
 * Maps a thrown failure onto a stable, user-facing error code.
 *
 * Host key outcomes are deliberately absent: the module catches
 * `HostKeyUnknownException` / `HostKeyMismatchException` before `classify` runs
 * and drives the trust dialog / HOST_KEY_CHANGED directly, so those two never
 * reach this mapping.
 */
internal fun classify(error: Throwable): String = when (error) {
  is java.net.SocketTimeoutException -> "TIMEOUT"
  is java.net.UnknownHostException -> "HOST_UNREACHABLE"
  is java.net.ConnectException -> "HOST_UNREACHABLE"
  is IllegalArgumentException -> "INVALID_CREDENTIALS"
  is IOException -> "IO_ERROR"
  else -> "CONNECT_FAILED"
}

/**
 * Reads the shell stream until the remote closes it, emitting decoded chunks.
 *
 * Runs on the connection's worker thread and returns when the channel ends.
 */
internal fun pumpOutput(connection: OpenConnection, emit: Emitter) {
  val input: InputStream = connection.shellOut
  val buffer = ByteArray(8192)
  val carry = ByteArrayOutputStream()

  while (true) {
    val read = try {
      input.read(buffer)
    } catch (_: IOException) {
      -1
    }
    if (read <= 0) break

    carry.write(buffer, 0, read)
    val (text, remainder) = decodeUtf8(carry.toByteArray())
    if (text.isNotEmpty()) emit.data(text)
    carry.reset()
    carry.write(remainder)
  }

  emit.state("disconnected")
}

/**
 * Decodes as much of [bytes] as forms complete UTF-8 characters.
 *
 * A shell's byte stream can split a multi-byte character across reads; emitting
 * a partial sequence renders as mojibake and corrupts the terminal's parser.
 *
 * @return the decoded text and the trailing bytes forming an incomplete
 *   sequence, to be prefixed to the next chunk.
 */
internal fun decodeUtf8(bytes: ByteArray): Pair<String, ByteArray> {
  if (bytes.isEmpty()) return "" to ByteArray(0)

  var index = bytes.size
  // Only the last three bytes can hold a truncated sequence.
  val floor = (bytes.size - 3).coerceAtLeast(0)
  while (index > floor) {
    val b = bytes[index - 1].toInt() and 0xFF
    if (b and 0xC0 == 0x80) {
      // Continuation byte; keep walking back to the sequence's lead byte.
      index--
      continue
    }
    val expected = when {
      b and 0xE0 == 0xC0 -> 2
      b and 0xF0 == 0xE0 -> 3
      b and 0xF8 == 0xF0 -> 4
      else -> 1
    }
    val available = bytes.size - (index - 1)
    return if (available < expected) {
      String(bytes, 0, index - 1, Charsets.UTF_8) to
        bytes.copyOfRange(index - 1, bytes.size)
    } else {
      String(bytes, 0, bytes.size, Charsets.UTF_8) to ByteArray(0)
    }
  }

  return String(bytes, 0, bytes.size, Charsets.UTF_8) to ByteArray(0)
}
