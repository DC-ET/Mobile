import * as SecureStore from "expo-secure-store";

/**
 * The minimum host identity a trust decision is about. Deliberately not the
 * full `SSHHost`: the trust store must also work for a transient host created
 * by Quick Connect, which never exists server-side.
 */
export interface HostIdentity {
  id: number;
  ip: string;
  port: number;
}

/** A host identity plus the fingerprint the server pins for it, if any. */
export interface HostIdentityWithServerPin extends HostIdentity {
  hostKeyFingerprint?: string | null;
}

/**
 * Local host key trust store.
 *
 * When the phone dials SSH itself, the server-side verifier is out of the
 * picture: nothing checks the key unless this does. It persists accepted
 * fingerprints in the Android Keystore-backed SecureStore (never
 * AsyncStorage, which would write plaintext to disk).
 *
 * Precedence when connecting:
 *   1. a fingerprint the Termix server already pinned
 *   2. a fingerprint the user accepted in-app before
 *   3. nothing → the terminal asks the user
 */

const KEY_PREFIX = "termix.hostkey.";

function hostKeyStoreKey(host: HostIdentity): string {
  // Quick Connect hosts get a fresh negative id per attempt, so keying on it
  // would make every connection to the same address look like a first one and
  // re-ask. Those are transient by definition, so trust them by endpoint.
  const id = host.id > 0 ? `${host.id}` : "transient";
  // Keying on a real id alone would silently carry trust across a host that was
  // deleted and recreated with the same row id after a sync.
  return `${KEY_PREFIX}${id}:${host.ip}:${host.port}`;
}

/** Reads a fingerprint the user previously accepted for this host. */
export async function getStoredHostKeyFingerprint(
  host: HostIdentity,
): Promise<string | null> {
  try {
    const value = await SecureStore.getItemAsync(hostKeyStoreKey(host));
    return value && value.length > 0 ? value : null;
  } catch {
    // A Keystore failure must not turn into a silent "trust everything" fallback.
    return null;
  }
}

/** Persists a fingerprint the user just accepted. */
export async function storeHostKeyFingerprint(
  host: HostIdentity,
  fingerprint: string,
): Promise<void> {
  // Nothing usable to remember: storing an empty value would make the next
  // `getStoredHostKeyFingerprint` silently treat it as "no trust either way".
  if (!fingerprint) return;
  try {
    await SecureStore.setItemAsync(hostKeyStoreKey(host), fingerprint, {
      // Device-only: the key is bound to this phone's Keystore, and a restored
      // backup would otherwise hand a stranger's trust store to a new device.
      requireAuthentication: false,
      keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    });
  } catch {
    // Trust is a convenience, not a requirement: if it cannot be stored the
    // user is simply asked again next time.
  }
}

/** Forgets a stored fingerprint, e.g. after the user rejects a new key. */
export async function clearStoredHostKeyFingerprint(
  host: HostIdentity,
): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(hostKeyStoreKey(host));
  } catch {}
}

/**
 * Decides which fingerprint to hand to the native dialer.
 *
 * Returns null when neither the server nor the app has ever pinned a key, which
 * is the signal to prompt the user.
 *
 * The local decision wins over the server's pin. Both are "a fingerprint this
 * host was trusted with", but only the local one can be as fresh as the moment
 * the user just looked at the key: the server's pin is written by the last
 * WebSocket connection (which may have been from another device, or months
 * ago). Preferring the server would make a host whose key legitimately changed
 * permanently undialable from the phone — the pin would keep losing to whatever
 * the phone just accepted, forever.
 */
export async function resolveKnownHostKey(
  host: HostIdentityWithServerPin,
): Promise<string | null> {
  const accepted = await getStoredHostKeyFingerprint(host);
  if (accepted) return accepted;
  return host.hostKeyFingerprint ?? null;
}
