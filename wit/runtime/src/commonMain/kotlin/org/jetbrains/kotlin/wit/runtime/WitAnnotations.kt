package org.jetbrains.kotlin.wit.runtime

/**
 * Marker annotations and enums used by the WIT compiler plugin during FIR/IR processing.
 *
 * These are intentionally lightweight so the FIR extensions can materialise synthetic declarations
 * without depending on the eventual runtime implementation details. The IR pipeline reads them back
 * to build the intermediate model that later stages will lower into concrete glue code.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class WitWorld(
    val packageId: String,
    val worldName: String,
)

public enum class WitBindingDirection {
    IMPORT,
    EXPORT,
}

public enum class WitBindingKind {
    FUNCTION,
    INTERFACE,
    RESOURCE,
}

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
public annotation class WitBinding(
    val direction: WitBindingDirection,
    val kind: WitBindingKind,
    /**
     * Fully qualified interface name when [kind] is [WitBindingKind.INTERFACE], empty otherwise.
     */
    val interfaceName: String = "",
    /**
     * Resource name when [kind] is [WitBindingKind.RESOURCE], empty otherwise.
     */
    val resourceName: String = "",
    /**
     * WIT-level binding identifier (e.g. function name).
     */
    val bindingName: String,
    /**
     * Resolved runtime target identifier (function symbol, interface reference, etc.).
     *
     * This remains a string placeholder until the runtime surface is fully defined.
     */
    val runtimeTarget: String = "",
    val isAsync: Boolean = false,
    val usesStreams: Boolean = false,
    val parameterTypeRefs: Array<String> = arrayOf<String>(),
    val parameterLabels: Array<String> = arrayOf<String>(),
    val resultTypeRefs: Array<String> = arrayOf<String>(),
    val resultLabels: Array<String> = arrayOf<String>(),
)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
public annotation class WitResource(
    val interfaceName: String,
    val resourceName: String,
    val ownHandleType: String = "",
    val borrowHandleType: String = "",
)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
public annotation class WitConstructor(
    val bindingName: String,
    val direction: WitBindingDirection,
)
