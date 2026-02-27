package org.jetbrains.kotlin.wit.runtime

/**
 * Translates between Kotlin-facing values (what generated bindings expose) and
 * the canonical ABI representation that the runtime dispatcher/handlers
 * operate on. The default implementation is an identity mapper so the legacy
 * `Any?` path keeps working until typed lowering lands.
 */
public interface BindingValueMarshaller {
    /**
     * Encodes the arguments provided by generated bindings before they reach
     * the runtime dispatcher. Implementations may reuse the incoming [arguments]
     * array, but callers must treat the returned array as immutable.
     */
    public fun encodeArguments(
        signature: BindingSignature,
        arguments: Array<out Any?>,
    ): Array<Any?>

    /**
     * Decodes arguments received from the runtime dispatcher back into the
     * Kotlin representation expected by host implementations.
     */
    public fun decodeArguments(
        signature: BindingSignature,
        arguments: Array<out Any?>,
    ): Array<Any?>

    /**
     * Decodes the result emitted by the runtime dispatcher back into the
     * Kotlin-facing representation expected by generated code.
     */
    public fun decodeResult(
        signature: BindingSignature,
        result: Any?,
    ): Any?

    /**
     * Encodes a host-implementation result so the runtime dispatcher can
     * forward it using canonical ABI types.
     */
    public fun encodeResult(
        signature: BindingSignature,
        result: Any?,
    ): Any?
}

public class IdentityBindingValueMarshaller : BindingValueMarshaller {
    override fun encodeArguments(
        signature: BindingSignature,
        arguments: Array<out Any?>,
    ): Array<Any?> = Array(arguments.size) { index -> arguments[index] }

    override fun decodeArguments(
        signature: BindingSignature,
        arguments: Array<out Any?>,
    ): Array<Any?> = Array(arguments.size) { index -> arguments[index] }

    override fun decodeResult(
        signature: BindingSignature,
        result: Any?,
    ): Any? = result

    override fun encodeResult(
        signature: BindingSignature,
        result: Any?,
    ): Any? = result
}
