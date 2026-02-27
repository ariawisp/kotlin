package org.jetbrains.kotlin.wit.compiler.fir

import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.compiler.schema.WitInterfaceMetadata
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeFunction
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata

class WitFirDebugTraceTest {

    @Test
    fun renderDebugTrace_includesWorldMetadataSummary() {
        val classId = ClassId(FqName("wit.generated.sample"), Name.identifier("Sample"))
        val metadata = sampleWorldMetadata()
        val trace = renderDebugTrace(mapOf(classId to metadata))

        assertTrue(trace.contains("wit/generated/sample"))
        assertTrue(trace.contains("imports"))
        assertTrue(trace.contains("SampleWorld"))
    }

    private fun sampleWorldMetadata(): WorldMetadata {
        val runtimeFunction = WitRuntimeFunction(
            name = "perform",
            parameters = listOf(WitRuntimeType("input", "string")),
            results = emptyList(),
            kind = org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.FUNCTION,
        )
        val runtimeBinding = WitRuntimeBinding(
            name = "perform",
            target = "",
            bindingKind = org.jetbrains.kotlin.wit.compiler.schema.BindingKind.FUNCTION,
            signature = runtimeFunction,
        )
        val runtimeInterface = WitRuntimeInterface(
            name = "Iface",
            stability = null,
            resources = emptyList(),
            functions = listOf(runtimeFunction),
        )
        val runtimeResource = WitRuntimeResource(
            name = "Thing",
            stability = null,
            ownHandleType = "i32",
            borrowHandleType = null,
        )
        val runtimeWorld = WitRuntimeWorld(
            name = "SampleWorld",
            stability = null,
            imports = listOf(runtimeBinding),
            exports = emptyList(),
            constructors = listOf(WitRuntimeConstructor("perform", runtimeFunction)),
        )
        val interfaceBindings = mapOf(Name.identifier("Iface") to InterfaceBindingMetadata(runtimeInterface, WitInterfaceMetadata("Iface", null)))
        val importBindings = mapOf(
            Name.identifier("perform") to BindingMetadata(
                binding = runtimeBinding,
                direction = BindingDirection.IMPORT,
                functionStubName = Name.identifier("__witImportFn_perform"),
                hostFunctionName = Name.identifier("perform"),
                interfaceName = "Iface",
                resourceName = null,
            ),
        )
        val resourceBindings = mapOf(
            Name.identifier("Thing") to ResourceBindingMetadata(
                runtimeInterface = runtimeInterface,
                resource = runtimeResource,
                hostFunctionName = Name.identifier("resource_iface_Thing"),
            ),
        )
        val constructorHelper = ConstructorHelperMetadata(
            functionName = Name.identifier("__witConstruct_perform"),
            constructor = runtimeWorld.constructors.first(),
            binding = importBindings.values.first(),
            direction = BindingDirection.IMPORT,
        )
        val classId = ClassId(FqName("wit.generated.sample"), Name.identifier("Sample"))
        return WorldMetadata(
            packageId = "sample@1.0.0",
            packageFqName = classId.packageFqName,
            runtimeWorld = runtimeWorld,
            worldSchema = WitWorldMetadata("SampleWorld", null),
            interfaceBindings = interfaceBindings,
            importBindings = importBindings,
            exportBindings = emptyMap(),
            resourceBindings = resourceBindings,
            constructorHelpers = mapOf(constructorHelper.functionName to constructorHelper),
            driverCompanionClassId = classId.createNestedClassId(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT),
            driverClassId = classId,
            bindCallableId = CallableId(classId.packageFqName, classId.relativeClassName, Name.identifier("bind")),
            driverImportsClassId = null,
            driverExportsClassId = null,
            registerImportsCallableId = null,
            registerExportsCallableId = null,
            driverResourcesClassId = null,
            registerResourcesCallableId = null,
        )
    }
}
