package expo.modules.nativessh

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/**
 * Parameters for one native SSH dial.
 *
 * `privateKey`/`keyPassphrase`/`password` arrive as plaintext over the app's
 * own bootstrap channel (the Termix server, authenticated as the same user).
 * They are held only for the duration of the handshake: the Kotlin side keeps
 * them in a `CharArray` that is zeroed on disconnect and never writes them to
 * disk, SharedPreferences or logs.
 *
 * A Record so the Expo bridge can map a plain JS object onto it.
 *
 * Every field is `@Field` and nullable-with-default: JS omits the credentials
 * that do not apply (a password host sends no `privateKey`), and a missing
 * property must land as Kotlin null rather than fail the conversion.
 */
internal data class ConnectOptions(
  @Field val host: String,
  @Field val port: Int,
  @Field val username: String,
  /** "password" | "key" | "credential" | "none". */
  @Field val authType: String,
  @Field val password: String? = null,
  @Field val privateKey: String? = null,
  @Field val keyPassphrase: String? = null,
  @Field val cols: Int = 80,
  @Field val rows: Int = 24,
  /** Server-pinned host key fingerprint, when the host has one. */
  @Field val knownHostKey: String? = null
) : Record
