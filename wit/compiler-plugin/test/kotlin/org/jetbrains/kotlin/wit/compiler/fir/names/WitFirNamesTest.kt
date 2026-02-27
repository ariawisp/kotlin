package org.jetbrains.kotlin.wit.compiler.fir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jetbrains.kotlin.wit.compiler.schema.BindingKind
import org.jetbrains.kotlin.wit.compiler.schema.FunctionKind
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeFunction
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType

class WitFirNamesTest {

    @Test
    fun sanitizeIdentifier_replacesInvalidCharacters() {
        assertEquals("_hello", sanitizeIdentifier("123hello"))
        assertEquals("foo_bar", sanitizeIdentifier("foo-bar"))
        assertEquals("wit_component", sanitizeIdentifier("wit component"))
    }

    @Test
    fun functionStubName_supportsSyncMethods() {
        val binding = runtimeBinding(
            name = "do-thing",
            kind = FunctionKind.METHOD,
            isAsync = false,
            usesStreams = false,
        )

        val stubName = functionStubName(binding, BindingDirection.IMPORT)
        assertNotNull(stubName)
        assertEquals("__witImportFn_do_thing", stubName.asString())
    }

    @Test
    fun functionStubName_ignoresAsyncFunctions() {
        val binding = runtimeBinding(
            name = "launch",
            kind = FunctionKind.METHOD,
            isAsync = true,
            usesStreams = false,
        )

        assertNull(functionStubName(binding, BindingDirection.EXPORT))
    }

    @Test
    fun allocateHostFunctionName_addsNumericSuffixes() {
        val used = mutableSetOf<String>()
        val first = allocateHostFunctionName("operate", used)
        val second = allocateHostFunctionName("operate", used)
        val third = allocateHostFunctionName("operate", used)

        assertEquals("operate", first)
        assertEquals("operate_1", second)
        assertEquals("operate_2", third)
    }

    private fun runtimeBinding(
        name: String,
        kind: FunctionKind,
        isAsync: Boolean,
        usesStreams: Boolean,
    ): WitRuntimeBinding {
        val signature = WitRuntimeFunction(
            name = name,
            parameters = listOf(WitRuntimeType(label = "value", typeRef = "string")),
            results = emptyList(),
            kind = kind,
            isAsync = isAsync,
            usesStreams = usesStreams,
        )
        return WitRuntimeBinding(
            name = name,
            target = "",
            bindingKind = BindingKind.FUNCTION,
            signature = signature,
        )
    }
}
