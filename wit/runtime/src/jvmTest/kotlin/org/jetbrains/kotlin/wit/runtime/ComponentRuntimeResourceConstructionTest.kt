package org.jetbrains.kotlin.wit.runtime

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull

private val SAMPLE_RESOURCE_TYPE = ResourceType("pkg", "iface", "resource")

class ComponentRuntimeResourceConstructionTest {
    private lateinit var runtime: ComponentRuntime

    @BeforeTest
    fun resetRuntime() {
        runtime = DefaultComponentRuntime()
    }

    @Test
    fun instantiateResourceDelegatesToRegisteredFactoryAndConstructor() {
        val adapter = RecordingAdapter()
        val factory = ResourceFactory { adapter }
        val collectedArguments = mutableListOf<Array<out Any?>>()

        runtime.registerResourceFactory(SAMPLE_RESOURCE_TYPE, factory) { rt, registeredFactory, arguments ->
            collectedArguments += arguments
            assertSame(runtime, rt)
            assertSame(factory, registeredFactory)
            val created = registeredFactory.create(runtime)
            assertSame(adapter, created)
            runtime.allocateOwnedHandle(created)
        }

        val handle = runtime.instantiateResource(SAMPLE_RESOURCE_TYPE, arrayOf<Any?>("value", 17))

        assertEquals(1, collectedArguments.size)
        assertEquals(listOf("value", 17), collectedArguments.single().toList())
        assertIs<OwnHandle<*>>(handle)

        val registeredAdapter = runtime.resolveResourceAdapter(handle)
        assertSame(adapter, registeredAdapter)

        handle.close()
        assertEquals(1, adapter.closedOwned)
    }

    @Test
    fun withOwnedHandleAutomaticallyReleases() {
        val adapter = RecordingAdapter()
        var capturedId = -1

        runtime.withOwnedHandle(adapter) { handle ->
            capturedId = handle.id
            assertSame(adapter, runtime.resolveResourceAdapter(handle))
        }

        assertEquals(1, adapter.closedOwned)
        assertNull(runtime.resolveResourceAdapter(ComponentHandle(capturedId)))
    }

    @Test
    fun withBorrowedHandleAutomaticallyReleases() {
        val adapter = RecordingAdapter()
        var capturedId = -1

        runtime.withBorrowedHandle(adapter) { handle ->
            capturedId = handle.id
            assertSame(adapter, runtime.resolveResourceAdapter(handle))
        }

        assertEquals(1, adapter.closedBorrowed)
        assertNull(runtime.resolveResourceAdapter(ComponentHandle(capturedId)))
    }

    @Test
    fun missingFactoryFailsWithHelpfulMessage() {
        runtime.registry.registerConstructor(SAMPLE_RESOURCE_TYPE) { _, _, _ ->
            error("should not be invoked")
        }

        val error = assertFailsWith<IllegalStateException> {
            runtime.instantiateResource(SAMPLE_RESOURCE_TYPE, emptyArray())
        }
        assertEquals(
            "WIT resource factory not registered for pkg/iface#resource (driver should call registerResource/registerResourceFactory).",
            error.message,
        )
    }

    @Test
    fun missingConstructorFailsWithHelpfulMessage() {
        runtime.registerResource(SAMPLE_RESOURCE_TYPE) {
            RecordingAdapter()
        }

        val error = assertFailsWith<IllegalStateException> {
            runtime.instantiateResource(SAMPLE_RESOURCE_TYPE, emptyArray())
        }
        assertEquals(
            "WIT resource constructor not registered for pkg/iface#resource (factory must call registerResourceFactory).",
            error.message,
        )
    }

    private class RecordingAdapter : ResourceAdapter {
        var closedOwned: Int = 0
        var closedBorrowed: Int = 0

        override val type: ResourceType = SAMPLE_RESOURCE_TYPE

        override fun ownHandlePrototype(): Handle<Resource>? = null

        override fun borrowHandlePrototype(): Handle<Resource>? = null

        override fun onCloseOwned() {
            closedOwned += 1
        }

        override fun onCloseBorrowed() {
            closedBorrowed += 1
        }
    }
}
