import { requireOptionalNativeModule } from "expo-modules-core";

export type NativeSshAuthType = "password" | "key" | "credential" | "none";

export type NativeSshState = "connecting" | "connected" | "disconnected";

/** Server-presented host key that the app has no trusted fingerprint for. */
export interface NativeSshHostKeyEvent {
  sessionId: number;
  fingerprint: string;
  keyType: string;
  algorithm: string;
  scenario: "new" | "changed";
  /**
   * The fingerprint the presented key was compared against, present only for a
   * "changed" event. The dialog shows it beside the new one.
   */
  trusted?: string | null;
}

export interface NativeSshDataEvent {
  sessionId: number;
  data: string;
}

export interface NativeSshStateEvent {
  sessionId: number;
  state: NativeSshState;
}

export interface NativeSshErrorEvent {
  sessionId: number;
  /** Stable, user-facing code. See NATIVE_SSH_ERRORS. */
  code: string;
  message: string;
}

export interface NativeSshClosedEvent {
  sessionId: number;
}

export interface NativeSshConnectOptions {
  host: string;
  port: number;
  username: string;
  authType: NativeSshAuthType;
  password?: string | null;
  /** PEM/OpenSSH private key body. */
  privateKey?: string | null;
  keyPassphrase?: string | null;
  cols: number;
  rows: number;
  /**
   * Fingerprint already trusted for this host, either pinned by the Termix
   * server or accepted in-app earlier. Omit (or pass null) for a first
   * connection, which raises the host key dialog.
   */
  knownHostKey?: string | null;
}

interface NativeSshModuleApi {
  connect(options: NativeSshConnectOptions): Promise<number>;
  resize(sessionId: number, cols: number, rows: number): Promise<void>;
  write(sessionId: number, data: string): Promise<void>;
  disconnect(sessionId: number): Promise<void>;
  disconnectAll(): Promise<void>;
  addListener(
    eventName: "onData",
    listener: (event: NativeSshDataEvent) => void,
  ): { remove: () => void };
  addListener(
    eventName: "onStateChange",
    listener: (event: NativeSshStateEvent) => void,
  ): { remove: () => void };
  addListener(
    eventName: "onError",
    listener: (event: NativeSshErrorEvent) => void,
  ): { remove: () => void };
  addListener(
    eventName: "onHostKey",
    listener: (event: NativeSshHostKeyEvent) => void,
  ): { remove: () => void };
  addListener(
    eventName: "onClosed",
    listener: (event: NativeSshClosedEvent) => void,
  ): { remove: () => void };
}

const NativeSsh =
  requireOptionalNativeModule<NativeSshModuleApi>("NativeSsh");

/**
 * Error codes the native module can report.
 *
 * Kept as data rather than strings scattered through call sites so the UI can
 * decide how reachable each failure is (a changed host key is a security
 * signal, a timeout is not).
 */
export const NATIVE_SSH_ERRORS = {
  HOST_KEY_CHANGED: "HOST_KEY_CHANGED",
  HOST_KEY_UNKNOWN: "HOST_KEY_UNKNOWN",
  TIMEOUT: "TIMEOUT",
  HOST_UNREACHABLE: "HOST_UNREACHABLE",
  INVALID_CREDENTIALS: "INVALID_CREDENTIALS",
  IO_ERROR: "IO_ERROR",
  CONNECT_FAILED: "CONNECT_FAILED",
} as const;

/**
 * True when the native SSH module is present in the running app.
 *
 * Dev builds via EAS include it; a JS-only reload (Expo Go, or a dev client
 * from before the module was added) does not, and the caller has to fall back
 * to the server-dialed WebSocket path.
 */
export function isNativeSshAvailable(): boolean {
  return NativeSsh !== null && NativeSsh !== undefined;
}

export interface NativeSshHandlers {
  onData?: (data: string) => void;
  onStateChange?: (state: NativeSshState) => void;
  onError?: (code: string, message: string) => void;
  onHostKey?: (event: {
    fingerprint: string;
    keyType: string;
    algorithm: string;
    scenario: "new" | "changed";
    trusted?: string | null;
  }) => void;
  onClosed?: () => void;
}

export class NativeSshUnavailableError extends Error {
  constructor() {
    super(
      "Native SSH is not available in this build. Rebuild the app to dial SSH from this device.",
    );
    this.name = "NativeSshUnavailableError";
  }
}

/**
 * A set of native event registrations owned by one caller.
 *
 * Scoped per instance rather than module-global: the app keeps several terminal
 * tabs alive at once (one per open tab in the sessions screen), and a global
 * registry would let the newest tab cancel the older tabs' subscriptions.
 */
export class NativeSshSubscription {
  private cleanups: (() => void)[] = [];
  private disposed = false;

  constructor(handlers: NativeSshHandlers) {
    if (!NativeSsh) return;

    if (handlers.onData) {
      const sub = NativeSsh.addListener("onData", (e) => handlers.onData?.(e.data));
      this.cleanups.push(() => sub.remove());
    }
    if (handlers.onStateChange) {
      const sub = NativeSsh.addListener("onStateChange", (e) =>
        handlers.onStateChange?.(e.state),
      );
      this.cleanups.push(() => sub.remove());
    }
    if (handlers.onError) {
      const sub = NativeSsh.addListener("onError", (e) =>
        handlers.onError?.(e.code, e.message),
      );
      this.cleanups.push(() => sub.remove());
    }
    if (handlers.onHostKey) {
      const sub = NativeSsh.addListener("onHostKey", (e) => handlers.onHostKey?.(e));
      this.cleanups.push(() => sub.remove());
    }
    if (handlers.onClosed) {
      const sub = NativeSsh.addListener("onClosed", () => handlers.onClosed?.());
      this.cleanups.push(() => sub.remove());
    }
  }

  /** Drops every listener this instance registered. Idempotent. */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const cleanup of this.cleanups) cleanup();
    this.cleanups = [];
  }
}

/** Opens a session and returns the native session id. */
export async function connectNativeSsh(
  options: NativeSshConnectOptions,
): Promise<number> {
  if (!NativeSsh) throw new NativeSshUnavailableError();
  return await NativeSsh.connect(options);
}

export async function resizeNativeSsh(
  sessionId: number,
  cols: number,
  rows: number,
): Promise<void> {
  await NativeSsh?.resize(sessionId, cols, rows);
}

export async function writeNativeSsh(
  sessionId: number,
  data: string,
): Promise<void> {
  await NativeSsh?.write(sessionId, data);
}

export async function disconnectNativeSsh(sessionId: number): Promise<void> {
  await NativeSsh?.disconnect(sessionId);
}

export async function disconnectAllNativeSsh(): Promise<void> {
  await NativeSsh?.disconnectAll();
}
