package org.jetbrains.kotlin.wit.compiler.tests

import org.junit.jupiter.api.Test

class WitFirDiagnosticTest : AbstractWitFirDiagnosticTest() {
    @Test
    fun testWorldWithResources() {
        runTest("testData/fir/worldWithResources.kt")
    }

    @Test
    fun testMultiConstructorDiagnostic() {
        runTest("testData/diagnostics/multiConstructor.kt")
    }

    @Test
    fun testAsyncStreamDiagnostic() {
        runTest("testData/diagnostics/asyncStream.kt")
    }

    @Test
    fun testUnknownKindDiagnostic() {
        runTest("testData/diagnostics/unknownKind.kt")
    }

    @Test
    fun testRuntimeSlotIr() {
        runTest("testData/ir/runtimeSlot.kt")
    }
}
