/*
 * Mirrors the WASI-internal callback for exported function exit, without introducing WASI imports.
 */
package kotlin.wasm.internal

@RequiresOptIn
private annotation class InternalWasmApi

@InternalWasmApi
public var onExportedFunctionExit: (() -> Unit)? = null

internal fun invokeOnExportedFunctionExit() {
    @OptIn(InternalWasmApi::class)
    onExportedFunctionExit?.invoke()
}

