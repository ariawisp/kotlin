/*
 * Component-only wasm stdlib: no WASI imports.
 */

package kotlin.time

internal actual fun systemClockNow(): Instant = Instant.fromEpochMilliseconds(0)

internal actual fun serializedInstant(instant: Instant): Any =
    throw UnsupportedOperationException("Serialization is supported only in Kotlin/JVM")

