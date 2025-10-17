package org.jetbrains.kotlin.wit.compiler.tests

import kotlin.test.Test

class WitIrLoweringTest : AbstractWitIrTest() {
    @Test
    fun testRuntimeSlotBinding() {
        runTest("testData/ir/runtimeSlot.kt")
    }

    @Test
    fun testBindingDispatchUsesRuntime() {
        runTest("testData/ir/bindingDispatch.kt")
    }

    @Test
    fun testResourceRegistrationUsesFactoryHelper() {
        runTest("testData/ir/resourceRegistration.kt")
    }

    @Test
    fun testMultiWorldSharedInterfaceLowering() {
        runTest("testData/ir/multiWorldSharedInterface.kt")
    }
}
