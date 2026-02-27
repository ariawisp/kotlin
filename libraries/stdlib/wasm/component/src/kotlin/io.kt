/*
 * Component-only wasm stdlib: no WASI imports.
 */

package kotlin.io

@Suppress("UNUSED_PARAMETER")
internal actual fun printError(error: String?): Unit = Unit

public actual fun print(message: Any?) {
    // No-op: component-only stdlib does not perform I/O
}

public actual fun println() {
    // No-op
}

public actual fun println(message: Any?) {
    // No-op
}

public actual fun readln(): String = throw UnsupportedOperationException("readln is not supported in Kotlin/Wasm component stdlib")

public actual fun readlnOrNull(): String? = null
