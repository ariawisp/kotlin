@file:OptIn(
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_BIND_FUNCTION_NAME
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME
import org.jetbrains.kotlin.wit.runtime.WitBindingDirection
import org.jetbrains.kotlin.wit.runtime.WitBindingKind

internal class WitIrPlanBuilder(
    @Suppress("UNUSED_PARAMETER") private val pluginContext: IrPluginContext,
) {
    private val worldAnnotationFqName = FqName("org.jetbrains.kotlin.wit.runtime.WitWorld")
    private val bindingAnnotationFqName = FqName("org.jetbrains.kotlin.wit.runtime.WitBinding")
    private val resourceAnnotationFqName = FqName("org.jetbrains.kotlin.wit.runtime.WitResource")
    private val constructorAnnotationFqName = FqName("org.jetbrains.kotlin.wit.runtime.WitConstructor")
    private val driverRegisterImportName = "registerImportHandler"
    private val driverRegisterExportName = "registerExportHandler"
    private val driverRegisterResourcesName = "registerResources"
    private val driverResourcesInterfaceName = "Resources"
    private val driverRegisterImportsName = "registerImports"
    private val driverRegisterExportsName = "registerExports"
    private val driverImportsInterfaceName = "Imports"
    private val driverExportsInterfaceName = "Exports"

    fun build(module: IrModuleFragment): WitIrPlan {
        val worlds = mutableListOf<WitIrPlan.World>()

        module.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach { irClass ->
                val worldAnnotation = irClass.findAnnotation(worldAnnotationFqName) ?: return@forEach
                val packageId = worldAnnotation.stringArgument(0) ?: return@forEach
                val worldName = worldAnnotation.stringArgument(1) ?: return@forEach

                val bindings = mutableListOf<WitIrPlan.Binding>()
                val resources = mutableListOf<WitIrPlan.Resource>()
                val constructors = mutableListOf<WitIrPlan.Constructor>()

                irClass.declarations.forEach { declaration ->
                    when (declaration) {
                        is IrProperty -> {
                            declaration.findAnnotation(bindingAnnotationFqName)?.let { annotation ->
                                createBindingPlanEntry(declaration, annotation)?.let { bindings += it }
                            }
                            declaration.findAnnotation(resourceAnnotationFqName)?.let { annotation ->
                                createResourcePlanEntry(declaration, annotation)?.let { resources += it }
                            }
                        }
                        is IrSimpleFunction -> {
                            declaration.findAnnotation(bindingAnnotationFqName)?.let { annotation ->
                                createBindingPlanEntry(declaration, annotation)?.let { bindings += it }
                            }
                        }
                        is IrConstructor -> {
                            declaration.findAnnotation(constructorAnnotationFqName)?.let { annotation ->
                                createConstructorPlanEntry(declaration, annotation)?.let { constructors += it }
                            }
                        }
                    }
                }

                val companionClass = irClass.declarations
                    .filterIsInstance<IrClass>()
                    .firstOrNull { it.name.asString() == "Companion" }
                companionClass?.declarations?.forEach { declaration ->
                    if (declaration is IrSimpleFunction) {
                        declaration.findAnnotation(constructorAnnotationFqName)?.let { annotation ->
                            createConstructorPlanEntry(declaration, annotation)?.let { constructors += it }
                        }
                    }
                }

                val driver = createDriverPlan(irClass)
                val runtimeSlot = createRuntimeSlotPlan(irClass)

                worlds += WitIrPlan.World(
                    irClass = irClass,
                    packageId = packageId,
                    worldName = worldName,
                    bindings = bindings,
                    resources = resources,
                    constructors = constructors,
                    driver = driver,
                    runtimeSlot = runtimeSlot,
                )
            }
        }

        return WitIrPlan(worlds)
    }

    private fun createDriverPlan(worldClass: IrClass): WitIrPlan.Driver? {
        val companion = worldClass.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.name.asString() == "Companion" }
            ?: return null
        val driverClass =
            companion.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == WIT_DRIVER_OBJECT_SIMPLE_NAME }
                ?: return null
        val bindFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == WIT_DRIVER_BIND_FUNCTION_NAME && it.valueParameters.size == 1
            } ?: return null
        val registerImportFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == driverRegisterImportName && it.valueParameters.size == 3
            }
        val registerExportFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == driverRegisterExportName && it.valueParameters.size == 3
            }
        val registerImportsFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == driverRegisterImportsName && it.valueParameters.size == 2
            }
        val registerExportsFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == driverRegisterExportsName && it.valueParameters.size == 2
            }
        val registerResourcesFunction =
            companion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull {
                it.name.asString() == driverRegisterResourcesName && it.valueParameters.size == 2
            }
        val importsContractClass =
            driverClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == driverImportsInterfaceName }
        val exportsContractClass =
            driverClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == driverExportsInterfaceName }
        val resourcesContractClass =
            driverClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.name.asString() == driverResourcesInterfaceName }
        val importsContract = importsContractClass?.let(::createDriverContract)
        val exportsContract = exportsContractClass?.let(::createDriverContract)
        val resourcesContract = resourcesContractClass?.let(::createDriverResourcesContract)
        return WitIrPlan.Driver(
            companion = companion,
            driverClass = driverClass,
            bindFunction = bindFunction,
            registerImportHandler = registerImportFunction,
            registerExportHandler = registerExportFunction,
            registerImports = registerImportsFunction,
            registerExports = registerExportsFunction,
            registerResources = registerResourcesFunction,
            importsContract = importsContract,
            exportsContract = exportsContract,
            resourcesContract = resourcesContract,
        )
    }

    private fun createRuntimeSlotPlan(worldClass: IrClass): WitIrPlan.RuntimeSlot? {
        val companion = worldClass.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.name.asString() == "Companion" }
            ?: return null

        val property = companion.declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == "__witRuntime" }
            ?: return null
        val backingField = property.backingField ?: return null

        return WitIrPlan.RuntimeSlot(
            property = property,
            backingField = backingField,
        )
    }

    private fun createBindingPlanEntry(
        declaration: IrDeclaration,
        annotation: IrConstructorCall,
    ): WitIrPlan.Binding? {
        val declarationName = (declaration as? IrDeclarationWithName)?.fqNameWhenAvailable?.shortName()?.asString()
            ?: declaration.safeName()
        val directionName = annotation.enumArgumentName(0) ?: return null
        val kindName = annotation.enumArgumentName(1) ?: return null
        val direction = runCatching { enumValueOf<WitBindingDirection>(directionName) }.getOrNull() ?: return null
        val kind = runCatching { enumValueOf<WitBindingKind>(kindName) }.getOrNull() ?: return null
        val bindingName = annotation.stringArgument(4) ?: return null
        var interfaceName = annotation.stringArgumentOrDefault(2)
        var resourceName = annotation.stringArgumentOrDefault(3)
        val runtimeTarget = annotation.stringArgumentOrDefault(5)
        val isAsync = annotation.booleanArgumentOrDefault(6)
        val usesStreams = annotation.booleanArgumentOrDefault(7)
        val parameterTypes = annotation.stringArrayArgument(8)
        val parameterLabels = annotation.stringArrayArgument(9)
        val resultTypes = annotation.stringArrayArgument(10)
        val resultLabels = annotation.stringArrayArgument(11)
        val parameters = parameterTypes.mapIndexed { index, typeRef ->
            WitIrPlan.TypeRefPrototype(
                label = parameterLabels.getOrNull(index)?.takeIf { it.isNotEmpty() },
                typeRef = typeRef,
                shape = inferTypeShape(typeRef),
            )
        }
        val results = resultTypes.mapIndexed { index, typeRef ->
            WitIrPlan.TypeRefPrototype(
                label = resultLabels.getOrNull(index)?.takeIf { it.isNotEmpty() },
                typeRef = typeRef,
                shape = inferTypeShape(typeRef),
            )
        }

        if (interfaceName.isEmpty()) {
            if (kind == WitBindingKind.INTERFACE && runtimeTarget.isNotEmpty()) {
                interfaceName = runtimeTarget
            }
        }
        if (resourceName.isEmpty()) {
            if (kind == WitBindingKind.RESOURCE && runtimeTarget.isNotEmpty()) {
                resourceName = runtimeTarget
            } else {
                val inferred = parseRuntimeResourceName(runtimeTarget)
                if (inferred.isNotEmpty()) {
                    resourceName = inferred
                }
            }
        }

        return WitIrPlan.Binding(
            declaration = declaration,
            declarationName = declarationName,
            direction = direction,
            kind = kind,
            interfaceName = interfaceName,
            resourceName = resourceName,
            bindingName = bindingName,
            runtimeTarget = runtimeTarget,
            isAsync = isAsync,
            usesStreams = usesStreams,
            parameterTypes = parameters,
            resultTypes = results,
        )
    }

    private fun createDriverContract(contractClass: IrClass): WitIrPlan.DriverContract? {
        val bindings = buildMap<String, WitIrPlan.DriverContract.ContractBinding> {
            contractClass.declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
                val annotation = function.findAnnotation(bindingAnnotationFqName) ?: return@forEach
                val bindingPlan = createBindingPlanEntry(function, annotation) ?: return@forEach
                put(
                    bindingPlan.bindingName,
                    WitIrPlan.DriverContract.ContractBinding(
                        function = function,
                        direction = bindingPlan.direction,
                        kind = bindingPlan.kind,
                    ),
                )
            }
        }
        if (bindings.isEmpty()) return null
        return WitIrPlan.DriverContract(
            irClass = contractClass,
            bindings = bindings,
        )
    }

    private fun createDriverResourcesContract(contractClass: IrClass): WitIrPlan.ResourcesContract? {
        val bindings = buildMap<String, WitIrPlan.ResourcesContract.ResourceBinding> {
            contractClass.declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
                val annotation = function.findAnnotation(resourceAnnotationFqName) ?: return@forEach
                val resourcePlan = createResourcePlanEntry(function, annotation) ?: return@forEach
                val key = "${resourcePlan.interfaceName}/${resourcePlan.resourceName}"
                put(
                    key,
                    WitIrPlan.ResourcesContract.ResourceBinding(
                        function = function,
                        interfaceName = resourcePlan.interfaceName,
                        resourceName = resourcePlan.resourceName,
                    ),
                )
            }
        }
        if (bindings.isEmpty()) return null
        return WitIrPlan.ResourcesContract(
            irClass = contractClass,
            bindings = bindings,
        )
    }

    private fun parseRuntimeResourceName(runtimeTarget: String): String {
        if (!runtimeTarget.startsWith("[")) return ""
        val closingIndex = runtimeTarget.indexOf(']')
        if (closingIndex <= 0) return ""
        val remainder = runtimeTarget.substring(closingIndex + 1)
        val scope = remainder.substringBefore('.', remainder)
        return scope
    }

    private fun createResourcePlanEntry(
        declaration: IrDeclaration,
        annotation: IrConstructorCall,
    ): WitIrPlan.Resource? {
        val declarationName = (declaration as? IrDeclarationWithName)?.fqNameWhenAvailable?.shortName()?.asString()
            ?: declaration.safeName()
        val interfaceName = annotation.stringArgument(0) ?: return null
        val resourceName = annotation.stringArgument(1) ?: return null
        val ownHandleType = annotation.stringArgumentOrDefault(2)
        val borrowHandleType = annotation.stringArgumentOrDefault(3)

        return WitIrPlan.Resource(
            declaration = declaration,
            declarationName = declarationName,
            interfaceName = interfaceName,
            resourceName = resourceName,
            ownHandleType = ownHandleType,
            borrowHandleType = borrowHandleType,
        )
    }

    private fun createConstructorPlanEntry(
        declaration: IrDeclaration,
        annotation: IrConstructorCall,
    ): WitIrPlan.Constructor? {
        val bindingName = annotation.stringArgument(0) ?: return null
        val directionName = annotation.enumArgumentName(1) ?: return null
        val direction = runCatching { enumValueOf<WitBindingDirection>(directionName) }.getOrNull() ?: return null

        return WitIrPlan.Constructor(
            declaration = declaration,
            bindingName = bindingName,
            direction = direction,
        )
    }
}

private fun inferTypeShape(typeRef: String): WitTypeShape = when {
    typeRef.startsWith("borrow:", ignoreCase = true) ->
        WitTypeShape.ResourceHandle(WitTypeShape.ResourceHandle.Ownership.BORROWED)
    typeRef.startsWith("own:", ignoreCase = true) ->
        WitTypeShape.ResourceHandle(WitTypeShape.ResourceHandle.Ownership.OWNED)
    typeRef in scalarTypeNames ->
        WitTypeShape.Scalar(typeRef)
    else -> WitTypeShape.Unknown
}

private val scalarTypeNames: Set<String> = setOf(
    "u8", "u16", "u32", "u64",
    "s8", "s16", "s32", "s64",
    "float32", "float64",
    "char", "string", "bool",
)

private fun IrDeclaration.safeName(): String = when (this) {
    is IrProperty -> name.asString()
    is IrSimpleFunction -> name.asString()
    is IrConstructor -> "<init>"
    else -> toString()
}

private fun IrDeclaration.findAnnotation(fqName: FqName): IrConstructorCall? =
    annotations.firstOrNull { annotation ->
        annotation.symbol.owner.parentClassOrNull?.fqNameWhenAvailable == fqName
    }

private fun IrConstructorCall.argumentAt(index: Int): IrExpression? =
    arguments.getOrNull(index)

private fun IrConstructorCall.stringArgument(index: Int): String? =
    argumentAt(index)?.constStringValue()

private fun IrConstructorCall.stringArgumentOrDefault(index: Int, default: String = ""): String =
    stringArgument(index) ?: default

private fun IrConstructorCall.booleanArgument(index: Int): Boolean? =
    argumentAt(index)?.constBooleanValue()

private fun IrConstructorCall.booleanArgumentOrDefault(index: Int, default: Boolean = false): Boolean =
    booleanArgument(index) ?: default

private fun IrConstructorCall.stringArrayArgument(index: Int): List<String> =
    when (val expression = argumentAt(index)) {
        is IrVararg -> expression.elements.mapNotNull { element ->
            when (element) {
                is IrSpreadElement -> element.expression.constStringValue()
                is IrExpression -> element.constStringValue()
                else -> null
            }
        }
        else -> emptyList()
    }

private fun IrConstructorCall.enumArgumentName(index: Int): String? =
    when (val expression = argumentAt(index)) {
        is IrGetEnumValue -> expression.symbol.owner.name.asString()
        else -> expression?.enumNameOrNull()
    }

private fun IrExpression.constStringValue(): String? =
    (this as? IrConst)?.value as? String

private fun IrExpression.constBooleanValue(): Boolean? =
    (this as? IrConst)?.value as? Boolean

private fun IrExpression.enumNameOrNull(): String? =
    (this as? IrGetEnumValue)?.symbol?.owner?.name?.asString()
