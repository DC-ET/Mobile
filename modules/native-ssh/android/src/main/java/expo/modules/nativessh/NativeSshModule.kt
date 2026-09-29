package expo.modules.nativessh

import android.os.Handler
import android.os.Looper
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Expo module that dials SSH from the phone's own network stack.
 *
 * The Android app used to have no SSH client at all: every terminal went to the
 * Termix server over a WebSocket and the server dialed on the phone's behalf,
 * so a VPN running on the phone never affected SSH traffic. This module is that
 * missing local path.
 *
 * Session model: one `connect` returns one session id. Reconnect policy lives
 * in JS (which knows about tabs, auth dialogs and backgrounding), so the native
 * side never retries on its own — a failed dial simply ends.
 */
class NativeSshModule : Module() {
  private val connections = ConcurrentHashMap<Int, OpenConnection>()
  private val nextSessionId = AtomicInteger(1)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val eventSink = ModuleEventSink(mainHandler)

  companion object {
    /**
     * The live module instance, so Kotlin code with no Expo context can still
     * push events. Cleared when the module is destroyed.
     */
    @Volatile
    var instance: NativeSshModule? = null
  }
  private val workers = ConcurrentHashMap<Int, Thread>()

  override fun definition() = ModuleDefinition {
    Name("NativeSsh")

    // The sink must exist before any dial; otherwise early failures land in the
    // no-op sink and the UI sits in "connecting" until its own timeout.
    //
    // The security providers are set up here too, and first: sshj resolves its
    // JCE lookups once and caches the answer, so on Android it has to be told
    // to use the bundled BouncyCastle under its non-colliding name before the
    // first `SSHClient` is built. See SecurityProviders.kt.
    OnCreate {
      installSecurityProviders()
      instance = this@NativeSshModule
      NativeSsh.events = eventSink
    }

    OnDestroy {
      disconnectAllSessions()
      // Drop anything still queued on the main loop: every event it would have
      // delivered goes to NoopEventSink below, so posting it is pure waste.
      mainHandler.removeCallbacksAndMessages(null)
      // A recreated module restarts the session id counter, so stale markers
      // for the ids it is about to reuse have to go first.
      Emitter.forgetAll()
      NativeSsh.events = NoopEventSink
      instance = null
    }

    Events("onData", "onClosed", "onError", "onHostKey", "onStateChange")

    /**
     * Opens a connection and starts an interactive shell.
     *
     * @param host target hostname or IP address.
     * @param port SSH port.
     * @param username login name.
     * @param authType "password", "key", "credential" or "none".
     * @param password plaintext password, when authType is "password".
     * @param privateKey PEM private key body, when authType is "key".
     * @param keyPassphrase passphrase protecting that key, if encrypted.
     * @param cols initial terminal width in columns.
     * @param rows initial terminal height in rows.
     * @param knownHostKey host key fingerprint already pinned for this host,
     *   either by the server or by a previous in-app trust decision.
     */
    AsyncFunction("connect") { options: ConnectOptions ->
      val id = nextSessionId.getAndIncrement()
      val emit = Emitter(id)

      val worker = thread(name = "native-ssh-$id") {
        var opened: OpenConnection? = null
        try {
          opened = openConnection(options)
          connections[id] = opened
          emit.state("connected")
          pumpOutput(opened, emit)
        } catch (e: HostKeyUnknownException) {
          // Nothing to trust yet: surface the fingerprint and let the user decide.
          runCatching { e.client.disconnect() }
          emit.hostKey(e.fingerprint, e.keyType, e.algorithm, "new")
        } catch (e: HostKeyMismatchException) {
          // Refuse rather than silently accepting a different key. Raised as a
          // host key event, not an error: the terminal already has the
          // "changed key" dialog with the old-vs-new comparison, and going
          // through the error path would close the tab instead of letting the
          // user see — and decide on — the change.
          runCatching { e.client.disconnect() }
          emit.hostKey(
            e.presented,
            e.keyType,
            e.algorithm,
            scenario = "changed",
            trusted = e.trusted,
          )
        } catch (e: Throwable) {
          emit.error(classify(e), e.message ?: "Connection failed")
        } finally {
          connections.remove(id)?.let {
            it.close()
            it.scrubCredentials()
          }
          workers.remove(id)
          // Swallowed automatically when the write path already announced the
          // teardown for this session id — that is the dedup working.
          emit.closed()
        }
      }
      workers[id] = worker
      id
    }

    /**
     * Resizes the pty behind an open shell.
     *
     * A missing session is a no-op rather than an error: JS checks its own
     * `sessionId` before calling, but `onClosed` arrives asynchronously through
     * the main handler, so a resize can still land in the window after the
     * session is gone. Throwing there would surface as an unhandled rejection
     * in a path JS deliberately leaves unguarded.
     */
    AsyncFunction("resize") { sessionId: Int, cols: Int, rows: Int ->
      val connection = connections[sessionId] ?: return@AsyncFunction Unit
      // A resize arriving after the remote closed the channel is not something
      // the user can act on, so it is swallowed rather than surfaced.
      runCatching { connection.shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    /** Writes user input (keystrokes, pastes) to the shell. */
    AsyncFunction("write") { sessionId: Int, data: String ->
      val connection = connections[sessionId] ?: return@AsyncFunction Unit
      try {
        connection.shellIn.write(data.toByteArray(Charsets.UTF_8))
        connection.shellIn.flush()
      } catch (_: Throwable) {
        // The remote side went away mid-keystroke. Tear the session down so the
        // UI shows the disconnect instead of buffering input forever.
        if (connections.remove(sessionId) != null) {
          connection.close()
          connection.scrubCredentials()
          Emitter(sessionId).closed()
        }
      }
    }

    /** Closes one session; the worker thread unwinds and emits onClosed. */
    AsyncFunction("disconnect") { sessionId: Int ->
      val connection = connections.remove(sessionId) ?: return@AsyncFunction Unit
      // Closing the channel makes pumpOutput hit EOF, which lets the worker
      // exit on its own — do not join here, that would block the JS thread.
      connection.close()
      connection.scrubCredentials()
    }

    /** Closes every open session; used when the app tears a terminal down. */
    AsyncFunction("disconnectAll") {
      disconnectAllSessions()
    }
  }

  private fun disconnectAllSessions() {
    for (id in connections.keys.toList()) {
      connections.remove(id)?.let {
        it.close()
        it.scrubCredentials()
      }
    }
  }
}
