package org.jetbrains.kotlin.wit.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExportedConstructorStubTest {
    private val runtime = DefaultComponentRuntime()
    private val bindingInvocations = mutableListOf<List<Any?>>()

    @AfterTest
    fun tearDown() {
        SampleWorld.__witRuntime = null
        bindingInvocations.clear()
    }

    @Test
    fun stubAllocatesHandleViaHelper() {
        val factory = ResourceFactory {
            RecordingAdapter()
        }
        runtime.registerResourceFactory(SAMPLE_RESOURCE_TYPE, factory) { rt, registeredFactory, arguments ->
            SampleWorld.__witConstructor_helper(rt, registeredFactory, arguments[0])
        }
        runtime.registerExportHandler(PACKAGE_ID, WORLD_NAME, BINDING_NAME) { arguments ->
            bindingInvocations += arguments.asList()
            null
        }
        SampleWorld.__witRuntime = runtime

        val world = SampleWorld()
        val handleValue = world.__witExportFn__constructor_resource(42)

        if (handleValue !is Handle<*>) error("Expected Handle")
        val handle = handleValue
        assertTrue(handle is OwnHandle<*>)
        val adapter = runtime.resolveResourceAdapter(ComponentHandle(handle.id))
            ?: error("Expected adapter")
        if (adapter !is RecordingAdapter) error("Expected RecordingAdapter")
        val recordingAdapter = adapter
        assertSame(SAMPLE_RESOURCE_TYPE, recordingAdapter.type)
        assertEquals(1, bindingInvocations.size)
        assertEquals(listOf(42), bindingInvocations.single())

        handle.close()
        assertTrue(recordingAdapter.closedOwned)
    }

    private class RecordingAdapter : ResourceAdapter {
        var closedOwned: Boolean = false

        override val type: ResourceType = SAMPLE_RESOURCE_TYPE

        override fun ownHandlePrototype(): Handle<Resource>? = null

        override fun borrowHandlePrototype(): Handle<Resource>? = null

        override fun onCloseOwned() {
            closedOwned = true
        }
    }

    private class SampleWorld {
        companion object {
            var __witRuntime: ComponentRuntime? = null

            val __witExport__constructor_resource: BindingDelegate = pendingBindingDelegate(
                packageId = PACKAGE_ID,
                worldName = WORLD_NAME,
                bindingName = BINDING_NAME,
                direction = WitBindingDirection.EXPORT,
                kind = WitBindingKind.RESOURCE,
                runtimeTarget = "",
                isAsync = false,
                usesStreams = false,
            )

            fun __witConstructor_helper(
                runtime: ComponentRuntime,
                factory: ResourceFactory,
                size: Any?,
            ): Handle<Resource> {
                runtime.dispatchBinding(__witExport__constructor_resource, size)
                val adapter = factory.create(runtime)
                return runtime.allocateOwnedHandle(adapter)
            }
        }

        fun __witExportFn__constructor_resource(size: Any): Any {
            val runtime = __witRuntime
                ?: error(
                    "WIT runtime not bound: $PACKAGE_ID/$WORLD_NAME -> $BINDING_NAME (export, resource, bind(runtime), registerExports)",
                )
            val factory = runtime.resolveResource(SAMPLE_RESOURCE_TYPE)
                ?: error(
                    "WIT resource factory not registered: $PACKAGE_ID/$WORLD_NAME -> $BINDING_NAME (resource=$RESOURCE_INTERFACE/$RESOURCE_NAME)",
                )
            return __witConstructor_helper(runtime, factory, size)
        }
    }

    private companion object {
        private const val PACKAGE_ID = "pkg"
        private const val WORLD_NAME = "world"
        private const val BINDING_NAME = "[constructor]sample"
        private const val RESOURCE_INTERFACE = "world"
        private const val RESOURCE_NAME = "sample"
        private val SAMPLE_RESOURCE_TYPE = ResourceType(PACKAGE_ID, RESOURCE_INTERFACE, RESOURCE_NAME)
    }
}
