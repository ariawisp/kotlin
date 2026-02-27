package org.jetbrains.kotlin.wit.runtime

/**
 * Optional callbacks exposed by [DefaultBindingDispatcher] so hosts can observe
 * registration and dispatch lifecycles for diagnostics and instrumentation.
 * Implementations should be fast and avoid throwing.
 */
public interface BindingDispatcherHooks {
    /** Invoked after an import handler is registered or replaced. */
    public fun onRegisterImport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {}

    /** Invoked after an export handler is registered or replaced. */
    public fun onRegisterExport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {}

    /** Invoked immediately before a binding dispatch is performed. */
    public fun onDispatchStart(
        delegate: BindingDelegate,
        arguments: Array<out Any?>,
    ) {}

    /** Invoked after a binding dispatch completes (successfully) with the result. */
    public fun onDispatchEnd(
        delegate: BindingDelegate,
        arguments: Array<out Any?>,
        result: Any?,
    ) {}
}

