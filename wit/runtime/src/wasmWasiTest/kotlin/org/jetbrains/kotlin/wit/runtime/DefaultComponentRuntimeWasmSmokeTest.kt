package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DefaultComponentRuntimeWasmSmokeTest {
    @Test
    fun registersDrivers() {
        val runtime = DefaultComponentRuntime()
        val driver = object : WorldDriver {
            override val packageId: String = "test"
            override val worldName: String = "demo"
            override fun bind(runtime: ComponentRuntime) {}
        }

        runtime.registerDriver(driver)

        val resolved = runtime.requireDriver("test", "demo")
        assertSame(driver, resolved)
    }

    @Test
    fun allocatesResourceHandles() {
        val runtime = DefaultComponentRuntime()
        val adapter = object : ResourceAdapter {
            override val type: ResourceType = ResourceType("test", "demo", "res")
            override fun ownHandlePrototype(): Handle<Resource>? = null
            override fun borrowHandlePrototype(): Handle<Resource>? = null
        }

        val handle = runtime.allocateOwnedHandle(adapter)
        try {
            val resolved = runtime.resolveResourceAdapter(handle)
            assertNotNull(resolved)
        } finally {
            handle.close()
        }
    }
}
