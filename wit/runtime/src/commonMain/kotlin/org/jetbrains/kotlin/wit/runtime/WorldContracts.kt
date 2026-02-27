package org.jetbrains.kotlin.wit.runtime

/**
 * Represents a generated world driver. Implementations are produced by the compiler plugin and
 * registered with [ComponentRuntime] so host code can discover them.
 */
public interface WorldDriver {
    public val packageId: String
    public val worldName: String

    /**
     * Invoked during registration so the driver can perform any eager wiring against the runtime.
     * The default generated implementation is expected to forward to runtime helpers only; hosts are
     * free to subclass or decorate the behaviour.
     */
    public fun bind(runtime: ComponentRuntime)
}

/**
 * Marker for generated binding delegates (imports/exports). These will be surfaced alongside
 * drivers once bindings are modelled in IR.
 *
 * TODO: widen the contract when async functions and streaming bindings gain runtime support.
 */
public interface BindingDelegate {
    public val packageId: String
    public val worldName: String
    public val bindingName: String
    public val direction: WitBindingDirection
    public val kind: WitBindingKind
    public val runtimeTarget: String
    public val isAsync: Boolean
    public val usesStreams: Boolean
    public val signature: BindingSignature
        get() = BindingSignature.EMPTY

    public fun attach(runtime: ComponentRuntime)
}

/**
 * Base contract for generated resource adapters. These wrap runtime handles and translate them into
 * strongly typed Kotlin interactions.
 *
 * TODO: surface async acquisition helpers and streaming primitives once the runtime surface lands.
 */
public interface ResourceAdapter : Resource {
    /**
     * Provides a representative handle instance for owned resources when available. Until handle
     * lifetimes are modelled this returns `null` by default.
     */
    public fun ownHandlePrototype(): Handle<Resource>?

    /**
     * Provides a representative handle instance for borrowed resources when available. Until handle
     * lifetimes are modelled this returns `null` by default.
     */
    public fun borrowHandlePrototype(): Handle<Resource>?

    /** Called when an owned handle is released by the runtime. */
    public fun onCloseOwned() {}

    /** Called when a borrowed handle is released by the runtime. */
    public fun onCloseBorrowed() {}
}
