package org.jetbrains.kotlin.wit.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal JVM implementation of [ComponentRuntime] that stores registrations in-memory.
 *
 * This is intentionally simple: the goal is to provide a safe surface for generated glue and tests
 * without introducing threading or lifetime semantics yet. Future iterations will replace the
 * mutable maps with lifecycle-aware storage and asynchronous handle management.
 */
public class DefaultComponentRuntime(
    override val host: ComponentHost = DefaultComponentHost(),
    override val registry: ComponentRegistry = ComponentRegistry(),
    override val dispatcher: BindingDispatcher = DefaultBindingDispatcher(),
    override val marshaller: BindingValueMarshaller = IdentityBindingValueMarshaller(),
    override val handles: ResourceHandleManager = DefaultResourceHandleManager(),
) : ComponentRuntime {
    private val bindingLock = Any()
    private val bindingWorlds: MutableSet<Pair<String, String>> = mutableSetOf()

    init {
        GeneratedModuleRegistry.registerRuntime(this)
    }

    override fun registerDriver(driver: WorldDriver) {
        val key = driver.packageId to driver.worldName
        val shouldBind = synchronized(bindingLock) {
            bindingWorlds.add(key)
        }
        try {
            registry.registerDriver(driver)
            if (shouldBind) {
                driver.bind(this)
            }
        } finally {
            if (shouldBind) {
                synchronized(bindingLock) {
                    bindingWorlds.remove(key)
                }
            }
        }
    }
}

private class DefaultComponentHost : ComponentHost {
    private val nextId = AtomicInteger(1)
    private val instances = ConcurrentHashMap<Int, Any>()

    override fun register(instance: Any): ComponentHandle {
        val id = nextId.getAndIncrement()
        instances[id] = instance
        return ComponentHandle(id)
    }

    override fun <T : Any> lookup(handle: ComponentHandle, type: Class<T>): T? {
        val value = instances[handle.id] ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (type.isInstance(value)) {
            value as T
        } else {
            null
        }
    }
}

/**
 * Convenience factory for tests; hosts can compose their own runtime by wiring custom host/registry
 * implementations if needed.
 */
public fun createDefaultRuntime(
    host: ComponentHost = DefaultComponentHost(),
    registry: ComponentRegistry = ComponentRegistry(),
    dispatcher: BindingDispatcher = DefaultBindingDispatcher(),
    marshaller: BindingValueMarshaller = IdentityBindingValueMarshaller(),
    handles: ResourceHandleManager = DefaultResourceHandleManager(),
): ComponentRuntime = DefaultComponentRuntime(host, registry, dispatcher, marshaller, handles)
