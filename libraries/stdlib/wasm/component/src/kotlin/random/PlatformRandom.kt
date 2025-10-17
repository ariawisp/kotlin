/*
 * Component-only wasm stdlib: deterministic seeded PRNG without imports.
 */

package kotlin.random

internal actual fun defaultPlatformRandom(): Random = Random(0)

