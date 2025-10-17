package org.jetbrains.kotlin.wit.runtime

/**
 * Placeholder binding delegate used while the compiler plugin lowers bindings into real runtime
 * glue. Generated worlds expose these via properties so metadata is available even before the host
 * installs a handler. Hosts should install real handlers via the generated
 * `registerImports`/`registerExports` helpers; calling [attach] now throws to steer users towards
 * those entry points.
 */
public class PendingBindingDelegate internal constructor(
    override val packageId: String,
    override val worldName: String,
    override val bindingName: String,
    override val direction: WitBindingDirection,
    override val kind: WitBindingKind,
    override val runtimeTarget: String,
    override val isAsync: Boolean,
    override val usesStreams: Boolean,
    override val signature: BindingSignature,
) : BindingDelegate {
    override fun attach(runtime: ComponentRuntime): Nothing {
        throw IllegalStateException(
            "PendingBindingDelegate.attach is deprecated; call Companion.registerImports/registerExports before invoking bindings.",
        )
    }

    override fun toString(): String = buildString {
        append("PendingBindingDelegate(")
        append(bindingName)
        append(", direction=")
        append(direction)
        append(", kind=")
        append(kind)
        if (runtimeTarget.isNotEmpty()) {
            append(", target=")
            append(runtimeTarget)
        }
        if (isAsync) append(", async=true")
        if (usesStreams) append(", streams=true")
        append(')')
    }
}

public fun pendingBindingDelegate(
    packageId: String,
    worldName: String,
    bindingName: String,
    direction: WitBindingDirection,
    kind: WitBindingKind,
    runtimeTarget: String,
    isAsync: Boolean,
    usesStreams: Boolean,
    signature: BindingSignature = BindingSignature.EMPTY,
): BindingDelegate = PendingBindingDelegate(
    packageId = packageId,
    worldName = worldName,
    bindingName = bindingName,
    direction = direction,
    kind = kind,
    runtimeTarget = runtimeTarget,
    isAsync = isAsync,
    usesStreams = usesStreams,
    signature = signature,
)

public typealias BindingHandler = (Array<out Any?>) -> Any?

public interface BindingDispatcher {
    public fun registerImport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    )

    public fun registerExport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    )

    public fun dispatch(delegate: BindingDelegate, arguments: Array<out Any?>): Any?
}

public class DefaultBindingDispatcher(
    public var hooks: BindingDispatcherHooks? = null,
) : BindingDispatcher {
    private val importHandlers: MutableMap<BindingKey, BindingHandler> = linkedMapOf()
    private val exportHandlers: MutableMap<BindingKey, BindingHandler> = linkedMapOf()

    override fun registerImport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {
        importHandlers[BindingKey(packageId, worldName, bindingName)] = handler
        hooks?.onRegisterImport(packageId, worldName, bindingName, handler)
    }

    override fun registerExport(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {
        exportHandlers[BindingKey(packageId, worldName, bindingName)] = handler
        hooks?.onRegisterExport(packageId, worldName, bindingName, handler)
    }

    override fun dispatch(delegate: BindingDelegate, arguments: Array<out Any?>): Any? {
        val key = BindingKey(delegate.packageId, delegate.worldName, delegate.bindingName)
        val handler = when (delegate.direction) {
            WitBindingDirection.IMPORT -> importHandlers[key]
            WitBindingDirection.EXPORT -> exportHandlers[key]
        }
        if (handler == null) {
            throw IllegalStateException(missingBindingHandlerMessage(delegate))
        }
        hooks?.onDispatchStart(delegate, arguments)
        val result = handler(arguments)
        hooks?.onDispatchEnd(delegate, arguments, result)
        return result
    }

    private data class BindingKey(
        val packageId: String,
        val worldName: String,
        val bindingName: String,
    )
}

public fun pendingBindingHandler(
    packageId: String,
    worldName: String,
    bindingName: String,
    direction: WitBindingDirection,
    kind: WitBindingKind,
    runtimeTarget: String,
    isAsync: Boolean,
    usesStreams: Boolean,
): BindingHandler = { _ ->
    val helper = when (direction) {
        WitBindingDirection.IMPORT -> "Companion.registerImports"
        WitBindingDirection.EXPORT -> "Companion.registerExports"
    }
    throw IllegalStateException(
        missingBindingHandlerMessage(
            packageId,
            worldName,
            bindingName,
            direction,
            kind,
            runtimeTarget,
            isAsync,
            usesStreams,
        ) + "; call $helper before invoking bindings",
    )
}

private fun pendingBindingHandler(delegate: BindingDelegate): BindingHandler =
    pendingBindingHandler(
        delegate.packageId,
        delegate.worldName,
        delegate.bindingName,
        delegate.direction,
        delegate.kind,
        delegate.runtimeTarget,
        delegate.isAsync,
        delegate.usesStreams,
    )

private fun missingBindingHandlerMessage(delegate: BindingDelegate): String =
    missingBindingHandlerMessage(
        delegate.packageId,
        delegate.worldName,
        delegate.bindingName,
        delegate.direction,
        delegate.kind,
        delegate.runtimeTarget,
        delegate.isAsync,
        delegate.usesStreams,
    )

private fun missingBindingHandlerMessage(
    packageId: String,
    worldName: String,
    bindingName: String,
    direction: WitBindingDirection,
    kind: WitBindingKind,
    runtimeTarget: String,
    isAsync: Boolean,
    usesStreams: Boolean,
): String = buildString {
    append("No binding handler registered: ")
    append(packageId)
    append('/')
    append(worldName)
    append('#')
    append(bindingName)
    append(" direction=")
    append(direction)
    append(" kind=")
    append(kind)
    if (runtimeTarget.isNotEmpty()) {
        append(" target=")
        append(runtimeTarget)
    }
    if (isAsync) append(" async")
    if (usesStreams) append(" streams")
    append("; call ")
    append(
        when (direction) {
            WitBindingDirection.IMPORT -> "Companion.registerImports"
            WitBindingDirection.EXPORT -> "Companion.registerExports"
        },
    )
    append(" before invoking bindings")
}

public fun dispatchBinding(
    delegate: BindingDelegate,
    vararg arguments: Any?,
): Any? = DefaultBindingDispatcher().dispatch(delegate, arguments)
