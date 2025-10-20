/*
 * Minimal WASI actuals for Throwable to enable wasm-wasi stdlib compilation
 * in the preview2 component configuration. These implementations intentionally
 * avoid JS interop and provide basic message/cause semantics and placeholders
 * for stack and suppressed exception plumbing used by throwableExtensions.kt.
 */

package kotlin

public actual open class Throwable internal constructor(
    public actual open val message: String?,
    public actual open val cause: kotlin.Throwable?,
    // Hidden parameter to avoid signature clash with secondary actual constructor
    internal val _marker: Any? = null,
) {
    public actual constructor(message: String?, cause: Throwable?) : this(message, cause, null)
    public actual constructor(message: String?) : this(message, null)
    public actual constructor(cause: Throwable?) : this(cause?.toString(), cause)
    public actual constructor() : this(null, null)

    // WASI: no platform stack yet; keep a cached value for API compatibility
    internal var _stack: String? = null
    internal val stack: String
        get() = _stack ?: ""

    internal var suppressedExceptionsList: MutableList<Throwable>? = null

    public override fun toString(): String {
        val name = this::class.qualifiedName ?: "kotlin.Throwable"
        return if (message != null) "$name: $message" else name
    }
}

internal actual var Throwable.suppressedExceptionsList: MutableList<Throwable>?
    get() = this.suppressedExceptionsList
    set(value) { this.suppressedExceptionsList = value }

internal actual val Throwable.stack: String get() = this.stack
