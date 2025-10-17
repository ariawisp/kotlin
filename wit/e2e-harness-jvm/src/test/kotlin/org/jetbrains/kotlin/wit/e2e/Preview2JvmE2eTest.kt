package org.jetbrains.kotlin.wit.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.wit.runtime.*

/**
 * Minimal end-to-end harness on JVM for preview-2.
 * Validates import dispatch, resource registration + constructor path, and cleanup.
 */
class Preview2JvmE2eTest {
    @Test
    fun runPreview2Loop() {
        val runtime = createDefaultRuntime()

        // 1) Register imports using generated driver contract
        wit.generated.e2e.preview.demo.Companion.registerImports(
            runtime,
            object : wit.generated.e2e.preview.demo.Companion.__WitDriver.Imports {
                override fun call(value: Any?): Any? = "ok:${(value as Number).toInt()}"
            },
        )

        // 2) Register resources using generated driver contract
        wit.generated.e2e.preview.demo.Companion.registerResources(
            runtime,
            object : wit.generated.e2e.preview.demo.Companion.__WitDriver.Resources {
                override fun resource_api_widget(): ResourceFactory = ResourceFactory {
                    object : ResourceAdapter {
                        override val type: ResourceType = ResourceType("e2e:preview", "api", "widget")
                        override fun ownHandlePrototype(): Handle<Resource>? = null
                        override fun borrowHandlePrototype(): Handle<Resource>? = null
                    }
                }
            },
        )

        // 3) Dispatch an import binding via runtime
        val importDelegate = pendingBindingDelegate(
            packageId = "e2e:preview",
            worldName = "demo",
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val result = runtime.dispatchBinding(importDelegate, 5)
        assertEquals("ok:5", result)

        // 4) Instantiate exported resource using factory + constructor path
        val handle = runtime.instantiateResource(ResourceType("e2e:preview", "api", "widget"), arrayOf(123))
        assertTrue(handle is OwnHandle<*>)
        val adapter = runtime.resolveResourceAdapter(handle)
        assertNotNull(adapter)
        assertEquals("widget", adapter.type.resourceName)
        handle.close()
    }
}

