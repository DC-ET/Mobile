package expo.modules.nativessh

import android.os.Handler

/**
 * Bridges native events to JS on the main thread.
 *
 * Expo's `sendEvent` is safe from any thread, but routing through one handler
 * keeps the event ordering stable: two chunks emitted from the read loop and a
 * state change emitted from an error path can otherwise reach JS interleaved,
 * which shows up as characters landing after the "disconnected" indicator.
 */
internal class ModuleEventSink(private val handler: Handler) : EventSink {
  override fun post(event: String, payload: Map<String, Any?>) {
    handler.post {
      NativeSshModule.instance?.sendEvent(event, payload)
    }
  }
}
