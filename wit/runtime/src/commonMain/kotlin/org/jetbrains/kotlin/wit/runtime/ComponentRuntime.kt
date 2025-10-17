package org.jetbrains.kotlin.wit.runtime

/**
 * Entry point that the generated IR calls to access runtime services.
 *
 * The implementation is expected to provide a mutable [registry] that tracks discovered drivers,
 * resource adapters, and other binding delegates. Generated driver code uses the helper methods
 * exposed here to register its findings without needing to understand the underlying storage model.
 */
public interface ComponentRuntime {
    public val host: ComponentHost
    public val registry: ComponentRegistry
    public val dispatcher: BindingDispatcher
    public val marshaller: BindingValueMarshaller
    public val handles: ResourceHandleManager

    /**
     * Registers a generated driver with the runtime so it can be discovered later. Default
     * implementation simply forwards to [registry]; runtimes may override to add custom behaviour
     * (logging, instrumentation, etc).
     */
    public fun registerDriver(driver: WorldDriver) {
        registry.registerDriver(driver)
    }

    /**
     * Registers a WIT resource adapter factory keyed by its [ResourceType]. Returns the previous
     * factory for the same type, if any.
     */
    /**
     * Installs a [ResourceFactory] for the given [ResourceType]. Generated drivers call this from
     * `registerResources` helpers so hosts can wire their adapter implementations in a single step.
     *
     * @return the previously registered factory for [type], if any.
     */
    public fun registerResource(type: ResourceType, factory: ResourceFactory): ResourceFactory? {
        return registry.registerResource(type, factory)
    }

    public fun registerResourceFactory(
        type: ResourceType,
        factory: ResourceFactory,
        constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>,
    ) {
        registry.registerResource(type, factory)
        registry.registerConstructor(type, constructor)
    }

    /**
     * Looks up a previously registered resource factory. This is primarily used by generated glue
     * code and tests; production runtimes are expected to provide concrete implementations.
     */
    public fun resolveResource(type: ResourceType): ResourceFactory? =
        registry.resourceFactory(type)

    public fun resolveResourceConstructor(
        type: ResourceType,
    ): ((ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>)? =
        registry.resourceConstructor(type)

    /**
     * Installs a host handler for an imported binding.
     *
     * Generated driver companions call this from `registerImports` after wiring the typed host
     * surface. Hosts may also invoke it directly when they want fine-grained control.
     */
    public fun registerImportHandler(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {
        dispatcher.registerImport(packageId, worldName, bindingName, handler)
    }

    /**
     * Installs a host handler for an exported binding so wasm callbacks reach the host.
     *
     * Generated drivers call this from `registerExports`; hosts can use it to replace or augment
     * handlers dynamically.
     */
    public fun registerExportHandler(
        packageId: String,
        worldName: String,
        bindingName: String,
        handler: BindingHandler,
    ) {
        dispatcher.registerExport(packageId, worldName, bindingName, handler)
    }

    /**
     * Returns the driver registered for [packageId]/[worldName], or `null` if no driver has been
     * registered yet.
     */
    public fun findDriver(packageId: String, worldName: String): WorldDriver? =
        registry.driver(packageId, worldName)

    /**
     * Returns the driver registered for [packageId]/[worldName] or throws a descriptive error if
     * no driver has been registered. Generated glue uses this so hosts get a consistent diagnostic.
     */
    public fun requireDriver(packageId: String, worldName: String): WorldDriver =
        findDriver(packageId, worldName)
            ?: error("WIT driver not registered for $packageId/$worldName (call registerDriver first).")

    public fun dispatchBinding(delegate: BindingDelegate, vararg arguments: Any?): Any? =
        ensureSynchronous(delegate) {
            val encodedArguments = marshaller.encodeArguments(delegate.signature, arguments)
            val rawResult = dispatcher.dispatch(delegate, encodedArguments)
            marshaller.decodeResult(delegate.signature, rawResult)
        }

    /**
     * Placeholder async dispatch helper. Async lowering will replace this once the runtime supports
     * coroutine-aware marshalling; for now callers receive an explicit diagnostic.
     */
    public suspend fun dispatchAsyncBinding(
        delegate: BindingDelegate,
        vararg arguments: Any?,
    ): Any? {
        throw UnsupportedOperationException(unsupportedBindingMessage(delegate))
    }

    /**
     * Placeholder streaming helper. Streaming lowering will replace this once the runtime exposes
     * a concrete stream implementation; until then we raise an explicit diagnostic.
     */
    public fun <T> openBindingStream(
        delegate: BindingDelegate,
        vararg arguments: Any?,
    ): BindingStream<T> {
        throw UnsupportedOperationException(unsupportedBindingMessage(delegate))
    }

    /**
     * Invokes the registered resource constructor for [type] using the given [arguments]. Hosts can
     * use this to create handles without re-implementing the factory/constructor lookup logic.
     *
     * @throws IllegalStateException if either the factory or constructor has not been registered.
     */
    public fun instantiateResource(
        type: ResourceType,
        arguments: Array<out Any?>,
    ): Handle<Resource> {
        val constructor = resolveResourceConstructor(type)
            ?: error("WIT resource constructor not registered for ${type.render()} (factory must call registerResourceFactory).")
        val factory = resolveResource(type)
            ?: error("WIT resource factory not registered for ${type.render()} (driver should call registerResource/registerResourceFactory).")
        return constructor(this, factory, arguments)
    }
}

public interface ComponentHost {
    public fun register(instance: Any): ComponentHandle

    public fun <T : Any> lookup(handle: ComponentHandle, type: Class<T>): T?
}

/**
 * Builder-style registry that tracks the runtime-visible drivers and resource adapters. It is
 * intentionally lightweight so hosts can embed or wrap it as needed.
 */
public class ComponentRegistry {
    private val drivers: MutableMap<WorldKey, WorldDriver> = linkedMapOf()
    private val resources: MutableMap<ResourceType, ResourceFactory> = linkedMapOf()
    private val constructors: MutableMap<ResourceType, (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>> = linkedMapOf()

    public fun registerDriver(driver: WorldDriver) {
        drivers[WorldKey(driver.packageId, driver.worldName)] = driver
    }

    public fun driver(packageId: String, worldName: String): WorldDriver? =
        drivers[WorldKey(packageId, worldName)]

    public fun allDrivers(): Collection<WorldDriver> = drivers.values

    public fun registerResource(type: ResourceType, factory: ResourceFactory): ResourceFactory? =
        resources.put(type, factory)

    public fun resourceFactory(type: ResourceType): ResourceFactory? = resources[type]

    public fun registerConstructor(
        type: ResourceType,
        constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>,
    ) {
        constructors[type] = constructor
    }

    public fun resourceConstructor(
        type: ResourceType,
    ): ((ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>)? = constructors[type]

    private data class WorldKey(val packageId: String, val worldName: String)
}

public fun interface ResourceFactory {
    public fun create(runtime: ComponentRuntime): ResourceAdapter
}

@JvmInline
public value class ComponentHandle(public val id: Int)

public fun ComponentRuntime.allocateOwnedHandle(adapter: ResourceAdapter): OwnHandle<Resource> =
    handles.allocateOwned(adapter)

public fun ComponentRuntime.allocateBorrowedHandle(adapter: ResourceAdapter): BorrowHandle<Resource> =
    handles.allocateBorrowed(adapter)

public fun ComponentRuntime.releaseOwnedHandle(handle: OwnHandle<out Resource>) {
    handles.releaseOwned(handle)
}

public fun ComponentRuntime.releaseBorrowedHandle(handle: BorrowHandle<out Resource>) {
    handles.releaseBorrowed(handle)
}

public fun ComponentRuntime.resolveResourceAdapter(handle: ComponentHandle): ResourceAdapter? =
    handles.resolve(handle)

public fun ComponentRuntime.resolveResourceAdapter(handle: Handle<Resource>): ResourceAdapter? =
    handles.resolve(handle)

public inline fun <R> ComponentRuntime.withOwnedHandle(
    adapter: ResourceAdapter,
    block: (OwnHandle<Resource>) -> R,
): R {
    val handle = allocateOwnedHandle(adapter)
    return try {
        block(handle)
    } finally {
        handle.close()
    }
}

public inline fun <R> ComponentRuntime.withBorrowedHandle(
    adapter: ResourceAdapter,
    block: (BorrowHandle<Resource>) -> R,
): R {
    val handle = allocateBorrowedHandle(adapter)
    return try {
        block(handle)
    } finally {
        handle.close()
    }
}

public interface BindingStream<T> : AutoCloseable, Iterator<T>

private fun <R> ensureSynchronous(delegate: BindingDelegate, block: () -> R): R {
    if (delegate.isAsync || delegate.usesStreams) {
        throw UnsupportedOperationException(unsupportedBindingMessage(delegate))
    }
    return block()
}

private fun unsupportedBindingMessage(delegate: BindingDelegate): String = buildString {
    append("WIT runtime does not support ")
    when {
        delegate.isAsync && delegate.usesStreams -> append("async streaming bindings")
        delegate.isAsync -> append("async bindings")
        else -> append("streaming bindings")
    }
    append(": ")
    append(delegate.packageId)
    append('/')
    append(delegate.worldName)
    append('#')
    append(delegate.bindingName)
    append(" (async=")
    append(delegate.isAsync)
    append(", streams=")
    append(delegate.usesStreams)
    append(").")
}

private fun ResourceType.render(): String =
    buildString {
        append(packageName)
        append('/')
        append(interfaceName)
        append('#')
        append(resourceName)
    }
