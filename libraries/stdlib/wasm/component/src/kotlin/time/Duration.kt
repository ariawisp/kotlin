/*
 * Component-only wasm stdlib: duration helpers without imports.
 */

package kotlin.time

internal actual inline val durationAssertionsEnabled: Boolean get() = true

internal actual fun formatToExactDecimals(value: Double, decimals: Int): String {
    // Simple fixed-point formatter without locale or BigDecimal
    val d = decimals.coerceAtLeast(0)
    val factor = 10.0.pow(d)
    val rounded = kotlin.math.round(value * factor) / factor
    // Ensure trailing zeros up to decimals
    val s = rounded.toString()
    val dot = s.indexOf('.')
    return if (d == 0) s.substringBefore('.') else if (dot == -1) s + "." + "0".repeat(d)
    else {
        val frac = s.length - dot - 1
        if (frac >= d) s.substring(0, dot + 1 + d)
        else s + "0".repeat(d - frac)
    }
}

private fun Double.pow(n: Int): Double {
    var res = 1.0
    repeat(n) { res *= this }
    return res
}

