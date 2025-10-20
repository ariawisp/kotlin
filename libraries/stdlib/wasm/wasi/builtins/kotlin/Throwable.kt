/*
 * WASI actuals for Throwable mirroring JS parity where possible.
 * Provides message/cause, suppressed exceptions support used by
 * throwableExtensions.kt, and a cached stack string (empty for now).
 */

package kotlin

import kotlin.wasm.internal.getQualifiedName
import kotlin.wasm.internal.wasmGetObjectRtti

public actual open class Throwable internal constructor(
    public actual open val message: String?,
    public actual open val cause: kotlin.Throwable?,
    // Hidden marker to keep constructor shape aligned with other targets
    internal val _marker: Any? = null,
) {
    public actual constructor(message: String?, cause: Throwable?) : this(message, cause, null)
    public actual constructor(message: String?) : this(message, null)
    public actual constructor(cause: Throwable?) : this(cause?.toString(), cause)
    public actual constructor() : this(null, null)

    // Keep a cached stack representation for ExceptionTraceBuilder.
    // WASI does not currently provide a native stack string.
    private var _stackCache: String? = null
    internal val stack: String
        get() = _stackCache ?: "".also { _stackCache = it }

    internal var suppressedExceptionsList: MutableList<Throwable>? = null

    public override fun toString(): String {
        val qualified = getQualifiedName(wasmGetObjectRtti(this))
        return if (message != null) "$qualified: $message" else qualified
    }
}

internal actual var Throwable.suppressedExceptionsList: MutableList<Throwable>?
    get() = this.suppressedExceptionsList
    set(value) { this.suppressedExceptionsList = value }

internal actual val Throwable.stack: String get() = this.stack
