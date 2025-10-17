package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DriverLifecycleTest {
    private class TestDriver(
        override val packageId: String,
        override val worldName: String,
        private val onBind: (ComponentRuntime, TestDriver) -> Unit,
    ) : WorldDriver {
        var binds = 0
        override fun bind(runtime: ComponentRuntime) {
            binds++
            onBind(runtime, this)
        }
    }

    @Test
    fun requireDriverProvidesHelpfulMessage() {
        val runtime = createDefaultRuntime()
        val error = assertFailsWith<IllegalStateException> {
            runtime.requireDriver("pkg", "world")
        }
        val message = error.message ?: ""
        // Must include package/world and guidance
        assert(message.contains("pkg/world"))
        assert(message.contains("registerDriver"))
    }

    @Test
    fun registerDriverIsReentrantSafe() {
        val runtime = createDefaultRuntime()
        lateinit var driver: TestDriver
        driver = TestDriver("pkg", "world") { r, d ->
            // Re-register during bind should not cause nested bind
            r.registerDriver(d)
        }
        runtime.registerDriver(driver)
        assertEquals(1, driver.binds)
    }

    @Test
    fun registerDriverBindsOncePerCallChain() {
        val runtime = createDefaultRuntime()
        val driver = TestDriver("pkg", "world") { _, _ -> /* no re-register */ }

        runtime.registerDriver(driver)
        runtime.registerDriver(driver)
        // Two top-level calls -> two binds
        assertEquals(2, driver.binds)
    }

    @Test
    fun bindSeesDriverInRegistry() {
        val runtime = createDefaultRuntime()
        val driver = TestDriver("pkg", "world") { r, d ->
            // Driver is visible in registry before bind is invoked
            assertSame(d, r.findDriver("pkg", "world"))
        }
        runtime.registerDriver(driver)
    }
}

