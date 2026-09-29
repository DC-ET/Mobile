import {
  connectNativeSsh,
  disconnectNativeSsh,
  isNativeSshAvailable,
  resizeNativeSsh,
  NativeSshSubscription,
  writeNativeSsh,
  type NativeSshConnectOptions,
} from "../../../../modules/native-ssh";
import { getNativeSSHPlan } from "../../../main-axios";
import {
  resolveKnownHostKey,
  storeHostKeyFingerprint,
} from "./host-key-trust";
import type {
  HostKeyData,
  NativeWSConfig,
  TerminalHostConfig,
} from "./NativeWebSocketManager";

/**
 * Drives a terminal session dialed from the phone's own network stack.
 *
 * This class exists so the terminal screen does not need to know which of the
 * two transports it is talking to: it mirrors the surface of
 * `NativeWebSocketManager` (`connect` / `sendInput` / `sendResize` /
 * `destroy`, plus the same state and auth callbacks) so both can be plugged
 * into the same handlers.
 *
 * The differences that do exist are deliberate, and come from the transport
 * itself rather than from this wrapper:
 *
 * - A local dial needs the plaintext secret, which the host list never carries.
 *   `connect` therefore fetches the connection plan from the server first, and
 *   keeps it only for the duration of the handshake.
 * - The server owns host key verification on the remote path. Here the phone
 *   does, using the server's pinned fingerprint when it has one and the user's
 *   previous decision otherwise.
 * - There is no server session to attach to, so `initialSessionId` and the
 *   open-tabs plumbing are accepted (to keep the caller uniform) but ignored.
 */
export class NativeSSHSession {
  private config: NativeWSConfig;
  private destroyed = false;
  private cols = 80;
  private rows = 24;
  /** Native session id once `connect` resolves. */
  private sessionId: number | null = null;
  private connecting: Promise<void> | null = null;
  private hasNotifiedFailure = false;
  private shouldNotReconnect = false;
  /**
   * Fingerprint the user has just accepted for a host key we had never seen.
   * Held until the retry completes, then persisted.
   */
  private acceptedHostKey: string | null = null;
  private subscription: NativeSshSubscription | null = null;
  /** True while an interactive auth/host-key dialog owns the outcome. */
  private awaitingUserInput = false;
  /** When `awaitingUserInput` went true, for the grace window in `handleError`. */
  private awaitingUserInputSince: number | null = null;
  /**
   * Credentials the user typed into the auth dialog.
   *
   * Held outside `hostConfig` on purpose: the server-side plan is fetched on
   * every dial, so a typed secret stored on the host config would be overwritten
   * by the (stale) one the server still has, and the dialog would reappear
   * forever. While this is set the plan is not fetched at all.
   */
  private overriddenCredentials: {
    authType: "password" | "key";
    password?: string;
    key?: string;
    keyPassword?: string;
  } | null = null;

  constructor(config: NativeWSConfig) {
    this.config = config;
    this.subscription = new NativeSshSubscription({
      onData: (data) => {
        if (this.destroyed) return;
        this.config.onData(data);
        this.config.onStateChange("dataReceived", {
          hostName: this.config.hostConfig.name,
        });
      },
      onStateChange: (state) => {
        if (this.destroyed) return;
        if (state === "connected") {
          this.hasNotifiedFailure = false;
          this.config.onStateChange("connected", {
            hostName: this.config.hostConfig.name,
            fromBackground: false,
            isReattach: false,
          });
          this.config.onPostConnectionSetup();
        }
        // A local dial has no reconnect policy: "disconnected" is terminal.
      },
      onError: (code, message) => {
        if (this.destroyed) return;
        this.handleError(code, message);
      },
      onHostKey: (event) => {
        if (this.destroyed) return;
        this.handleHostKey(event);
      },
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
    });
  }

  /** Tears the native event registrations down. Idempotent. */
  disposeSubscription(): void {
    this.subscription?.dispose();
    this.subscription = null;
  }

  async connect(cols: number, rows: number): Promise<void> {
    if (this.destroyed) return;
    if (this.connecting) return this.connecting;

    this.cols = cols;
    this.rows = rows;

    if (!isNativeSshAvailable()) {
      this.notifyFailure(
        "This build cannot dial SSH from the phone. Rebuild the app to use a local connection source.",
      );
      return;
    }

    this.connecting = this.performConnect()
      .catch((error: unknown) => {
        // Every failure path inside performConnect already reports to the UI;
        // this only keeps an unexpected rejection from becoming an unhandled
        // promise when the caller awaits connect().
        this.notifyFailure(errorMessage(error));
      })
      .finally(() => {
        this.connecting = null;
      });

    await this.connecting;
  }

  private async performConnect(): Promise<void> {
    this.shouldNotReconnect = false;
    this.hasNotifiedFailure = false;
    this.awaitingUserInput = false;
    this.awaitingUserInputSince = null;
    this.config.onStateChange("connecting", { retryCount: 0 });

    const hostConfig = this.config.hostConfig;
    const hostId = hostConfig.id;

    // A saved host normally gets its secrets from the server. The user's typed
    // credentials win, and must: the plan carries whatever the server still has
    // stored, which is exactly what just failed. A null answer means the host is
    // gone (deleted, or no longer shared) and there is nothing to dial with.
    const plan =
      hostId > 0 && !this.overriddenCredentials ? await getNativeSSHPlan(hostId) : null;

    if (hostId > 0 && !plan && !this.overriddenCredentials) {
      this.notifyFailure(
        "The server no longer serves this host's credentials. It may have been deleted, or sharing may have changed.",
      );
      return;
    }

    // A transient host (Quick Connect) has no host id to fetch anything for, so
    // it passes its credentials inline.
    //
    // A retry the user just answered keeps the host key facts from the host
    // config (a typed password says nothing about which key to trust) and takes
    // only the auth fields from the override.
    const override = this.overriddenCredentials;
    const credentials: {
      username: string | null;
      authType: string;
      password: string | null;
      key: string | null;
      keyPassword: string | null;
      keyType: string | null;
      hostKeyFingerprint: string | null;
      hostKeyType: string | null;
      hostKeyAlgorithm: string | null;
    } = override
      ? {
          username: hostConfig.username,
          authType: override.authType,
          password: override.password ?? null,
          key: override.key ?? null,
          keyPassword: override.keyPassword ?? null,
          keyType: null,
          hostKeyFingerprint: hostConfig.hostKeyFingerprint ?? null,
          hostKeyType: hostConfig.hostKeyType ?? null,
          hostKeyAlgorithm: hostConfig.hostKeyAlgorithm ?? null,
        }
      : (plan ?? {
          username: hostConfig.username,
          authType: hostConfig.authType,
          password: hostConfig.password ?? null,
          key: hostConfig.key ?? null,
          keyPassword: hostConfig.keyPassword ?? null,
          keyType: hostConfig.keyType ?? null,
          hostKeyFingerprint: hostConfig.hostKeyFingerprint ?? null,
          hostKeyType: hostConfig.hostKeyType ?? null,
          hostKeyAlgorithm: hostConfig.hostKeyAlgorithm ?? null,
        });

    const knownHostKey =
      this.acceptedHostKey ??
      (await resolveKnownHostKey({
        id: hostConfig.id,
        ip: hostConfig.ip,
        port: hostConfig.port,
        hostKeyFingerprint: credentials.hostKeyFingerprint ?? null,
      }));

    const options: NativeSshConnectOptions = {
      host: hostConfig.ip,
      port: hostConfig.port,
      // The plan's username is the host's resolved one (it may come from the
      // referenced credential); the transient-host path has only the inline
      // value. Falling back keeps a plan without a username dialable.
      username: credentials.username || hostConfig.username,
      authType: credentials.authType as NativeSshConnectOptions["authType"],
      password: credentials.password,
      privateKey: credentials.key,
      keyPassphrase: credentials.keyPassword,
      cols: this.cols,
      rows: this.rows,
      knownHostKey,
    };

    this.sessionId = await connectNativeSsh(options);
  }

  /**
   * Records the user's answer to a host key prompt and re-dials.
   *
   * The native layer refuses the connection outright (never trusts silently),
   * so an accepted key has to come back as a new dial attempt.
   */
  async resolveHostKey(accept: boolean, fingerprint?: string): Promise<void> {
    this.awaitingUserInput = false;
    this.awaitingUserInputSince = null;

    const host = this.config.hostConfig;
    if (!accept) {
      await this.forgetHostKey(host);
      this.shouldNotReconnect = true;
      this.notifyFailure("Rejected the host key for " + host.name);
      return;
    }

    // The fingerprint is what turns "the user accepted this key" into a key the
    // next dial can trust. Without it the accept would be forgotten immediately
    // and the dialog would reappear forever, so it is required here rather than
    // optional: the dialog only opens with a real key in hand.
    if (!fingerprint) {
      this.shouldNotReconnect = true;
      this.notifyFailure(
        "Cannot remember this host key: the phone could not read its fingerprint.",
      );
      return;
    }

    this.acceptedHostKey = fingerprint;
    await storeHostKeyFingerprint(host, fingerprint);

    await this.reconnect();
  }

  /**
   * Supplies credentials the user typed into the auth dialog.
   *
   * Used when a host is stored without a usable secret (or with the wrong one):
   * the next dial uses these instead of the server-side plan.
   */
  overrideCredentials(credentials: {
    password?: string;
    key?: string;
    keyPassword?: string;
    authType: "password" | "key";
  }): void {
    // Stored separately from `hostConfig` so it survives the server-side plan
    // fetch that `performConnect` does on every dial.
    this.overriddenCredentials = {
      authType: credentials.authType,
      password: credentials.password,
      key: credentials.key,
      keyPassword: credentials.keyPassword,
    };
  }

  /**
   * Re-runs the dial, e.g. after the user supplied a password the stored host
   * did not have.
   */
  async reconnect(): Promise<void> {
    if (this.destroyed) return;
    // Drop the half-open attempt first, so its onClosed does not report a
    // failure the user just recovered from.
    if (this.sessionId !== null) {
      const id = this.sessionId;
      this.sessionId = null;
      await disconnectNativeSsh(id).catch(() => {});
    }
    await this.connect(this.cols, this.rows);
  }

  private async forgetHostKey(
    host: Pick<TerminalHostConfig, "id" | "ip" | "port">,
  ): Promise<void> {
    this.acceptedHostKey = null;
    const { clearStoredHostKeyFingerprint } = await import("./host-key-trust");
    await clearStoredHostKeyFingerprint(host);
  }

  sendInput(data: string): void {
    if (this.sessionId === null || this.destroyed) return;
    void writeNativeSsh(this.sessionId, data).catch(() => {
      // A write to a vanished channel is not worth surfacing; onClosed already
      // fired and the UI shows the disconnect.
    });
  }

  sendResize(cols: number, rows: number): void {
    this.cols = cols;
    this.rows = rows;
    if (this.sessionId === null || this.destroyed) return;
    void resizeNativeSsh(this.sessionId, cols, rows).catch(() => {});
  }

  /**
   * Drops the session and every listener it registered.
   *
   * The native teardown is fire-and-forget: awaiting it inside a React
   * unmount effect would block the UI thread on a socket close.
   */
  destroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    this.shouldNotReconnect = true;
    this.overriddenCredentials = null;
    this.acceptedHostKey = null;
    this.awaitingUserInput = false;
    this.awaitingUserInputSince = null;
    const id = this.sessionId;
    this.sessionId = null;
    this.disposeSubscription();
    if (id !== null) {
      void disconnectNativeSsh(id).catch(() => {});
    }
  }

  /** Reports a failure exactly once per connection attempt. */
  private notifyFailure(message: string): void {
    if (this.hasNotifiedFailure) return;
    this.hasNotifiedFailure = true;
    this.shouldNotReconnect = true;
    this.config.onConnectionFailed(`${this.config.hostConfig.name}: ${message}`);
  }

  private handleError(code: string, message: string): void {
    // The user is already looking at a dialog; do not close the terminal
    // underneath it before they answer.
    //
    // Bounded on purpose: if the dialog is dismissed without an answer (the
    // screen unmounts, the user backs out) this flag is never cleared, and every
    // failure after that would be swallowed — including the "connection is
    // really gone" one.
    if (this.awaitingUserInput) {
      this.awaitingUserInputSince ??= Date.now();
      if (Date.now() - this.awaitingUserInputSince < AUTH_DIALOG_GRACE_MS) {
        return;
      }
      this.awaitingUserInput = false;
    }

    if (isAuthFailure(code, message)) {
      this.awaitingUserInput = true;
      this.awaitingUserInputSince ??= Date.now();
      this.shouldNotReconnect = true;
      this.config.onAuthDialogNeeded("auth_failed");
      return;
    }

    this.notifyFailure(message);
  }

  private handleHostKey(event: {
    fingerprint: string;
    keyType: string;
    algorithm: string;
    scenario: "new" | "changed";
    /** The pin that did not match, when the event is a "changed" one. */
    trusted?: string | null;
  }): void {
    this.awaitingUserInput = true;
    this.awaitingUserInputSince ??= Date.now();
    this.shouldNotReconnect = true;
    const data: HostKeyData = {
      ip: this.config.hostConfig.ip,
      port: this.config.hostConfig.port,
      hostname: this.config.hostConfig.name,
      fingerprint: event.fingerprint,
      // Without the old value the dialog cannot show "this used to be X", which
      // is the whole point of telling the user the key changed.
      oldFingerprint: event.trusted ?? undefined,
      keyType: event.keyType,
      algorithm: event.algorithm,
    };
    this.config.onHostKeyVerificationRequired?.(event.scenario, data);
  }

  notifyBackgrounded(): void {
    // Nothing to do: a local dial has no server-side session or ping to pause.
    // The OS keeps the socket alive while the process lives.
  }

  notifyForegrounded(): void {
    // Reconnects, when needed, are driven by onClosed; nothing is buffered.
  }
}

/**
 * How long a failure may be suppressed because an interactive dialog is open.
 *
 * Long enough to cover the dialog being on screen (and a second event from the
 * same failure arriving), short enough that a dialog nobody answered stops
 * blocking the terminal's own disconnect notice.
 */
const AUTH_DIALOG_GRACE_MS = 60_000;

/** True when the failure means "ask the user for credentials". */
function isAuthFailure(code: string, message: string): boolean {
  if (code === "INVALID_CREDENTIALS") return true;
  const m = message.toLowerCase();
  return (
    m.includes("auth") ||
    m.includes("password") ||
    m.includes("permission denied")
  );
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}
