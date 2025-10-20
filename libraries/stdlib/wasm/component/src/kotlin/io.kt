package kotlin.io

import org.jetbrains.kotlin.wit.runtime.*

// Component stdlib: provide no-ops by default, but attempt to route through
// wasi:io streams when the Preview-2 runtime is active (i.e., under wasmWasi).

private object WasiIoPrinting {
    private val runtime: ComponentRuntime? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            createDefaultRuntime().also { it.installGeneratedWorlds() }
        } catch (_: Throwable) {
            null
        }
    }

    fun tryWriteStdout(bytes: ByteArray): Boolean = tryWrite("wasi:cli", "run", "stdout.write", bytes)
    fun tryWriteStderr(bytes: ByteArray): Boolean = tryWrite("wasi:cli", "run", "stderr.write", bytes)

    private fun tryWrite(pkg: String, world: String, binding: String, bytes: ByteArray): Boolean {
        val rt = runtime ?: return false
        return try {
            val delegate = pendingBindingDelegate(
                packageId = pkg,
                worldName = world,
                bindingName = binding,
                direction = WitBindingDirection.IMPORT,
                kind = WitBindingKind.FUNCTION,
                runtimeTarget = "",
                isAsync = false,
                usesStreams = true,
            )
            rt.dispatchBinding(delegate, bytes)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

@Suppress("UNUSED_PARAMETER")
internal actual fun printError(error: String?) {
    val msg = (error ?: "") + "\n"
    val bytes = msg.encodeToByteArray()
    // Try stderr first; fall back to stdout; drop silently if unavailable
    if (WasiIoPrinting.tryWriteStderr(bytes)) return
    @Suppress("UNUSED_EXPRESSION")
    run { WasiIoPrinting.tryWriteStdout(bytes) }
}

public actual fun print(message: Any?) {
    val text = message?.toString() ?: "null"
    @Suppress("UNUSED_EXPRESSION")
    run { WasiIoPrinting.tryWriteStdout(text.encodeToByteArray()) }
}

public actual fun println() {
    @Suppress("UNUSED_EXPRESSION")
    run { WasiIoPrinting.tryWriteStdout("\n".encodeToByteArray()) }
}

public actual fun println(message: Any?) {
    val text = message?.toString() ?: "null"
    @Suppress("UNUSED_EXPRESSION")
    run { WasiIoPrinting.tryWriteStdout((text + "\n").encodeToByteArray()) }
}

public actual fun readln(): String = throw UnsupportedOperationException("readln is not supported in Kotlin/Wasm component stdlib")

public actual fun readlnOrNull(): String? = null
