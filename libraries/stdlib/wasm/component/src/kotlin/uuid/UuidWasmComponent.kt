/*
 * Component-only WASM actuals for UUID helpers.
 * Provides a deterministic, seeded fallback for secureRandomBytes with no WASI imports.
 */

package kotlin.uuid

internal actual fun secureRandomBytes(destination: ByteArray): Unit {
    // Simple xorshift64* PRNG with a fixed seed to avoid WASI imports.
    // This is a placeholder suitable for component-only builds where consumers
    // are expected to supply their own entropy if needed.
    var state = 0x9E3779B97F4A7C15UL
    fun nextULong(): ULong {
        var x = state
        x = x xor (x shr 12)
        x = x xor (x shl 25)
        x = x xor (x shr 27)
        state = x
        return x * 0x2545F4914F6CDD1DUL
    }
    var buffer = 0UL
    var remaining = 0
    for (i in destination.indices) {
        if (remaining == 0) {
            buffer = nextULong()
            remaining = 8
        }
        destination[i] = (buffer and 0xFFUL).toByte()
        buffer = buffer shr 8
        remaining--
    }
}

