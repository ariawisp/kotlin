package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ResourceFactoryRegistrationTest {
    private class RecordingAdapter(
        private val expectedArgs: List<Any?>,
    ) : ResourceAdapter {
        var closedOwned = false

        override val type: ResourceType = ResourceType("pkg", "iface", "resource")

        override fun ownHandlePrototype(): Handle<Resource>? = null

        override fun borrowHandlePrototype(): Handle<Resource>? = null

        override fun onCloseOwned() {
            closedOwned = true
        }

        fun assertArgs(args: Array<out Any?>) {
            assertEquals(expectedArgs, args.asList())
        }
    }

    @Test
    fun registerConstructorAlongsideFactory() {
        val runtime = DefaultComponentRuntime()
        val resourceType = ResourceType("pkg", "iface", "resource")
        val expectedArgs = listOf<Any?>("value", 42)
        val adapter = RecordingAdapter(expectedArgs)
        val factory = ResourceFactory { adapter }
        var lambdaInvocations = 0

        runtime.registerResourceFactory(resourceType, factory) { rt, registeredFactory, arguments ->
            lambdaInvocations += 1
            assertSame(runtime, rt)
            assertSame(factory, registeredFactory)
            adapter.assertArgs(arguments)
            val createdAdapter = registeredFactory.create(rt)
            assertSame(adapter, createdAdapter)
            rt.allocateOwnedHandle(createdAdapter)
        }

        val constructor = runtime.resolveResourceConstructor(resourceType)
        val registeredFactory = runtime.resolveResource(resourceType)

        assertNotNull(constructor, "constructor should be registered")
        assertSame(factory, registeredFactory)

        val argsArray = expectedArgs.toTypedArray()
        val handle = constructor.invoke(runtime, factory, argsArray)

        // Closing the handle should trigger adapter cleanup.
        handle.close()

        assertEquals(1, lambdaInvocations)
        assertEquals(true, adapter.closedOwned)
    }
}
