package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceInstantiationTest {
    private val type = ResourceType("pkg", "iface", "res")

    private class RecordingAdapter : ResourceAdapter {
        override val type: ResourceType = ResourceType("pkg", "iface", "res")
        var closedOwned = 0
        var closedBorrowed = 0
        override fun ownHandlePrototype(): Handle<Resource>? = null
        override fun borrowHandlePrototype(): Handle<Resource>? = null
        override fun onCloseOwned() { closedOwned++ }
        override fun onCloseBorrowed() { closedBorrowed++ }
    }

    @Test
    fun instantiateOwnedResourceHappyPath() {
        val runtime = createDefaultRuntime()
        val adapter = RecordingAdapter()
        val factory = ResourceFactory { _ -> adapter }
        val constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource> =
            { rt, f, _ -> rt.allocateOwnedHandle(f.create(rt)) }

        // Register both pieces through the convenience API
        runtime.registerResourceFactory(type, factory, constructor)

        val handle = runtime.instantiateResource(type, emptyArray())
        assertTrue(handle is OwnHandle<*>)
        assertNotNull(runtime.resolveResourceAdapter(handle))

        // Releasing the handle fires owned callback
        handle.close()
        assertEquals(1, adapter.closedOwned)
        assertEquals(0, adapter.closedBorrowed)
        assertNull(runtime.resolveResourceAdapter(ComponentHandle(handle.id)))
    }

    @Test
    fun instantiateResourceMissingConstructor() {
        val runtime = createDefaultRuntime()
        val factory = ResourceFactory { _ -> RecordingAdapter() }

        // Only factory is registered
        runtime.registerResource(type, factory)

        val error = assertFailsWith<IllegalStateException> {
            runtime.instantiateResource(type, emptyArray())
        }
        assertTrue(error.message?.contains("constructor") == true)
        assertTrue(error.message?.contains(type.resourceName) == true)
    }

    @Test
    fun instantiateResourceMissingFactory() {
        val runtime = createDefaultRuntime()
        val constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource> =
            { rt, f, _ -> rt.allocateOwnedHandle(f.create(rt)) }

        // Only constructor is registered by reaching into the registry (host might do this in tests)
        runtime.registry.registerConstructor(type, constructor)

        val error = assertFailsWith<IllegalStateException> {
            runtime.instantiateResource(type, emptyArray())
        }
        assertTrue(error.message?.contains("factory") == true)
        assertTrue(error.message?.contains(type.resourceName) == true)
    }
}
