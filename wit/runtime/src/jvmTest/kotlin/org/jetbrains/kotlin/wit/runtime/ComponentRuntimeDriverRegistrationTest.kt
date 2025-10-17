package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private const val DRIVER_PACKAGE_ID = "pkg.driver"
private const val DRIVER_WORLD_NAME = "demo"

class ComponentRuntimeDriverRegistrationTest {
    private class RecordingDriver : WorldDriver {
        val bindInvocations: MutableList<ComponentRuntime> = mutableListOf()

        override val packageId: String = DRIVER_PACKAGE_ID
        override val worldName: String = DRIVER_WORLD_NAME

        override fun bind(runtime: ComponentRuntime) {
            bindInvocations += runtime
            // Simulate generated driver behaviour which re-registers itself while wiring
            runtime.registerDriver(this)
        }
    }

    @Test
    fun registerDriverBindsAndRegisters() {
        val runtime = DefaultComponentRuntime()
        val driver = RecordingDriver()

        runtime.registerDriver(driver)

        assertEquals(listOf(runtime), driver.bindInvocations.toList())
        assertSame(driver, runtime.registry.driver(DRIVER_PACKAGE_ID, DRIVER_WORLD_NAME))
        assertSame(driver, runtime.findDriver(DRIVER_PACKAGE_ID, DRIVER_WORLD_NAME))
        assertSame(driver, runtime.requireDriver(DRIVER_PACKAGE_ID, DRIVER_WORLD_NAME))
    }

    @Test
    fun driverCanRebind() {
        val runtime = DefaultComponentRuntime()
        val driver = RecordingDriver()

        runtime.registerDriver(driver)
        runtime.registerDriver(driver)

        assertEquals(2, driver.bindInvocations.size, "Driver should rebind on repeated registration")
        assertSame(driver, runtime.registry.driver(DRIVER_PACKAGE_ID, DRIVER_WORLD_NAME))
    }

    @Test
    fun requireDriverProducesHelpfulError() {
        val runtime = DefaultComponentRuntime()

        val error = kotlin.runCatching {
            runtime.requireDriver("missing", "world")
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IllegalStateException)
        assertTrue(error.message?.contains("missing/world") == true)
    }
}
