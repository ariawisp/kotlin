package org.jetbrains.kotlin.wit.runtime

import kotlin.reflect.KClass

/**
 * Component-friendly implementation of [ComponentRuntime] used by Wasm builds. The runtime remains
 * single-threaded today, so we keep the state in simple mutable collections while mirroring the JVM
 * behaviour for registration and driver binding.
 */
public class DefaultComponentRuntime(
    override val host: ComponentHost = DefaultComponentHost(),
    override val registry: ComponentRegistry = ComponentRegistry(),
    override val dispatcher: BindingDispatcher = DefaultBindingDispatcher(),
    override val marshaller: BindingValueMarshaller = IdentityBindingValueMarshaller(),
    override val handles: ResourceHandleManager = DefaultResourceHandleManager(),
) : ComponentRuntime {
    private val bindingWorlds: MutableSet<Pair<String, String>> = mutableSetOf()

    init {
        GeneratedModuleRegistry.registerRuntime(this)
    }

    override fun registerDriver(driver: WorldDriver) {
        val key = driver.packageId to driver.worldName
        val shouldBind = bindingWorlds.add(key)
        try {
            registry.registerDriver(driver)
            if (shouldBind) {
                driver.bind(this)
            }
        } finally {
            if (shouldBind) {
                bindingWorlds.remove(key)
            }
        }
    }
}

private class DefaultComponentHost : ComponentHost {
    private var nextId: Int = 1
    private val instances: MutableMap<Int, Any> = mutableMapOf()

    override fun register(instance: Any): ComponentHandle {
        val id = nextId++
        instances[id] = instance
        return ComponentHandle(id)
    }

    override fun <T : Any> lookup(handle: ComponentHandle, type: KClass<T>): T? {
        val value = instances[handle.id] ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (type.isInstance(value)) value as T else null
    }
}

public fun createDefaultRuntime(
    host: ComponentHost = DefaultComponentHost(),
    registry: ComponentRegistry = ComponentRegistry(),
    dispatcher: BindingDispatcher = DefaultBindingDispatcher(),
    marshaller: BindingValueMarshaller = IdentityBindingValueMarshaller(),
    handles: ResourceHandleManager = DefaultResourceHandleManager(),
): ComponentRuntime = DefaultComponentRuntime(host, registry, dispatcher, marshaller, handles)
