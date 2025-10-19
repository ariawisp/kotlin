package org.jetbrains.kotlin.wit.runtime

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class RecordingDriver(
    override val packageId: String,
    override val worldName: String,
) : WorldDriver {
    val bindInvocations: MutableList<ComponentRuntime> = mutableListOf()

    override fun bind(runtime: ComponentRuntime) {
        bindInvocations += runtime
    }
}

class GeneratedModuleRegistryTest {
    @BeforeTest
    fun resetRegistryBefore() {
        GeneratedModuleRegistry.resetForTests()
    }

    @AfterTest
    fun resetRegistryAfter() {
        GeneratedModuleRegistry.resetForTests()
    }

    @Test
    fun registersDriversBeforeRuntimeStartup() {
        val driver = RecordingDriver("pkg.before", "world")
        GeneratedModuleRegistry.registerGeneratedWorlds(driver)

        val runtime = DefaultComponentRuntime()

        assertSame(driver, runtime.requireDriver(driver.packageId, driver.worldName))
        assertEquals(listOf(runtime), driver.bindInvocations.toList())
    }

    @Test
    fun registersDriversAfterRuntimeStartup() {
        val runtime = DefaultComponentRuntime()
        val driver = RecordingDriver("pkg.after", "world")

        GeneratedModuleRegistry.registerGeneratedWorlds(driver)

        assertSame(driver, runtime.requireDriver(driver.packageId, driver.worldName))
        assertEquals(listOf(runtime), driver.bindInvocations.toList())
    }

    @Test
    fun ignoresDuplicateDriverRegistrations() {
        val runtime = DefaultComponentRuntime()
        val driver = RecordingDriver("pkg.dup", "world")

        GeneratedModuleRegistry.registerGeneratedWorlds(driver)
        GeneratedModuleRegistry.registerGeneratedWorlds(driver)

        assertSame(driver, runtime.requireDriver(driver.packageId, driver.worldName))
        assertEquals(listOf(runtime), driver.bindInvocations.toList())
    }
}
