package expo.modules.nativessh

import java.security.Provider
import java.security.Security
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Name the bundled BouncyCastle is published under.
 *
 * Deliberately not `"BC"`; see [installSecurityProviders].
 */
internal const val BC_PROVIDER_NAME = "TermixBC"

/**
 * Makes sshj's JCE lookups resolve to a provider that actually has the
 * algorithms an SSH handshake needs.
 *
 * Why this is necessary: Android ships a provider of its own registered under
 * the name `BC`, and it is an old, reduced build — since Android P it no longer
 * provides the algorithms that were duplicated by Conscrypt/AndroidOpenSSL.
 * BouncyCastle's provider is *also* called `BC` (`BouncyCastleProvider`'s
 * constructor passes that literal to `super`), so by the time our copy is
 * instantiated the name is already taken.
 *
 * sshj's `SecurityUtils.register()` then walks straight into the collision:
 *
 *  - `Security.getProvider("BC") != null` — the platform's, so our copy is never
 *    added to the provider list;
 *  - `securityProvider == null`, so it runs its capability check
 *    `MessageDigest.getInstance("MD5", platformBC)` /
 *    `KeyAgreement.getInstance("DH", platformBC)` — both succeed against the
 *    platform build, since those two are *not* among the removed algorithms;
 *  - `setSecurityProvider("BC")`, so every later
 *    `KeyAgreement.getInstance("X25519", "BC")` is answered by the platform's
 *    provider, which has no X25519.
 *
 * The result is `no such algorithm: X25519 for provider BC`, thrown from
 * `DHBase`'s constructor as sshj builds the default `Curve25519SHA256` key
 * exchange — i.e. before a single byte of the connection, and outside anything
 * `openConnection` or `classify` can turn into a useful error code.
 *
 * The fix is to publish the bundled provider under a name nothing else uses and
 * pin sshj to it. The platform's `BC` is left registered exactly as it was, so
 * anything else in the process that resolves it keeps working unchanged.
 *
 * Idempotent: `Security.addProvider` is a no-op for a name already registered.
 * Must run before the first sshj call, which is why it is wired to the module's
 * `OnCreate` rather than the connect path.
 */
internal fun installSecurityProviders() {
  val bundled: Provider = BouncyCastleProvider()

  if (Security.getProvider(BC_PROVIDER_NAME) == null) {
    // Populate after construction rather than in an `init` block: `Provider.put`
    // is only usable once the superclass constructor has finished.
    val renamed = RenamedProvider(BC_PROVIDER_NAME, bundled)
    renamed.adoptFrom(bundled)
    Security.addProvider(renamed)
  }

  // Only now is it safe to pin sshj. Left to itself, sshj would try to register
  // BouncyCastleProvider() as "BC" (blocked by the platform) and then pin
  // itself to the platform's under that same name — the bug above.
  SecurityUtils.setSecurityProvider(BC_PROVIDER_NAME)
}

/**
 * A republished copy of another provider's algorithm table under a different
 * provider name.
 *
 * A clone is unavoidable rather than a matter of preference:
 * `BouncyCastleProvider`'s constructor hard-codes its own name to `BC`, and
 * `Provider.putService` refuses a `Provider.Service` whose provider is not the
 * receiver ("service.getProvider() must match this Provider object"), so the
 * bundled provider's services cannot simply be handed to another provider.
 *
 * The algorithms, implementation classes and therefore the cryptography are the
 * source's; only the name it answers to differs.
 */
private class RenamedProvider(name: String, source: Provider) :
  Provider(name, source.version, source.info) {

  /**
   * Copies [source]'s entries, e.g. `"KeyAgreement.X25519"` and
   * `"Alg.Alias.KeyAgreement.1.3.101.110"`, onto this provider.
   *
   * The entry names are what `getInstance(algorithm, provider)` looks up, and
   * writing them through the legacy `put(String, String)` path is what rebuilds
   * the service table `getService` consults: `Provider.put` routes string
   * entries into the legacy map that is lazily parsed, on both OpenJDK and
   * Android's libcore implementation. Reading them back through `keys()`/`get()`
   * also carries the aliases, which `Provider.Service`'s surface on Android
   * offers no way to enumerate.
   */
  fun adoptFrom(source: Provider) {
    for (key in source.keys()) {
      put(key, source[key])
    }
  }
}
