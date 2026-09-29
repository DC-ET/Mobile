import type { NativeWSConfig } from "./NativeWebSocketManager";

/**
 * The surface the terminal screen needs from a transport, independent of
 * whether SSH is dialed by the Termix server or by this phone.
 *
 * `NativeWebSocketManager` (server-dialed) and `NativeSSHSession`
 * (phone-dialed) both satisfy it, so the terminal code has one path instead of
 * an `isLocal` branch at every call site.
 */
export interface TerminalTransport {
  connect(cols: number, rows: number): Promise<void> | void;
  sendInput(data: string): void;
  sendResize(cols: number, rows: number): void;
  destroy(): void;
  notifyBackgrounded(): void;
  notifyForegrounded(): void;

  // --- Interactions that only exist on the server-dialed path. On the local
  // path these are absent rather than no-ops, so the terminal can grey out or
  // hide the matching dialogs instead of swallowing the user's input.
  sendTotpResponse?(code: string, isPassword: boolean): void;
  sendReconnectWithCredentials?(
    credentials: { password?: string; sshKey?: string; keyPassword?: string },
    cols: number,
    rows: number,
  ): void;
  sendHostKeyResponse?(action: "accept" | "reject"): void;
  sendPassphraseResponse?(passphrase: string): void;
  sendWarpgateContinue?(): void;
}

// The event vocabulary both transports speak is `NativeWSConfig`
// (`NativeWebSocketManager.ts`), which the terminal builds once and hands to
// either transport. It is deliberately the only such interface: a second one
// here would drift from it the moment either gains a callback.

export type { NativeWSConfig };
