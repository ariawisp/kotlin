@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
internal class WitIrDriverRegistrationLowering(
    private val pluginContext: IrPluginContext,
    private val debugLogging: Boolean,
    private val plan: WitIrPlan,
) {
    private val registerDriverCallableId = CallableId(
        ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("ComponentRuntime")),
        Name.identifier("registerDriver"),
    )

    private val registerImportHandlerSymbol by lazy {
        pluginContext.referenceFunctions(REGISTER_IMPORT_HANDLER_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null && function.owner.valueParameters.size == 4
            } ?: error("Unable to resolve ComponentRuntime.registerImportHandler for WIT IR lowering")
    }

    private val registerExportHandlerSymbol by lazy {
        pluginContext.referenceFunctions(REGISTER_EXPORT_HANDLER_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null && function.owner.valueParameters.size == 4
            } ?: error("Unable to resolve ComponentRuntime.registerExportHandler for WIT IR lowering")
    }

    private val registerResourceSymbol by lazy {
        pluginContext.referenceFunctions(REGISTER_RESOURCE_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null && function.owner.valueParameters.size == 2
            } ?: error("Unable to resolve ComponentRuntime.registerResource for WIT IR lowering")
    }
    private val registerResourceFactorySymbol by lazy {
        pluginContext.referenceFunctions(REGISTER_RESOURCE_FACTORY_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null && function.owner.valueParameters.size == 3
            } ?: error("Unable to resolve ComponentRuntime.registerResourceFactory for WIT IR lowering")
    }

    private val pendingBindingHandlerSymbol by lazy {
        pluginContext.referenceFunctions(PENDING_BINDING_HANDLER_CALLABLE_ID)
            .singleOrNull { it.owner.valueParameters.size == 8 }
            ?: error("Unable to resolve pendingBindingHandler helper for WIT IR lowering")
    }

    private val arrayGetSymbol by lazy {
        pluginContext.irBuiltIns.arrayClass.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .first { it.name.asString() == "get" }
            .symbol
    }

    private val bindingArgumentsType by lazy {
        pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.anyNType)
    }

    private val bindingValueMarshallerClassSymbol by lazy {
        pluginContext.referenceClass(BINDING_VALUE_MARSHALLER_CLASS_ID)
            ?: error("Unable to resolve BindingValueMarshaller class for WIT IR lowering")
    }

    private val bindingValueMarshallerDecodeArgumentsSymbol by lazy {
        bindingValueMarshallerClassSymbol.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "decodeArguments" }
            ?.symbol ?: error("Unable to resolve BindingValueMarshaller.decodeArguments for WIT IR lowering")
    }

    private val bindingValueMarshallerEncodeResultSymbol by lazy {
        bindingValueMarshallerClassSymbol.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "encodeResult" }
            ?.symbol ?: error("Unable to resolve BindingValueMarshaller.encodeResult for WIT IR lowering")
    }

    private val componentRuntimeMarshallerPropertySymbol by lazy {
        pluginContext.referenceProperties(COMPONENT_RUNTIME_MARSHALLER_PROPERTY_ID)
            .singleOrNull()
            ?: error("Unable to resolve ComponentRuntime.marshaller property for WIT IR lowering")
    }

    private val componentRuntimeMarshallerGetterSymbol by lazy {
        componentRuntimeMarshallerPropertySymbol.owner.getter?.symbol
            ?: error("Unable to resolve ComponentRuntime.marshaller getter for WIT IR lowering")
    }

    private val bindingHandlerType by lazy {
        pluginContext.irBuiltIns.functionN(1).typeWith(bindingArgumentsType, pluginContext.irBuiltIns.anyNType)
    }

    private val resourceTypeConstructorSymbol by lazy {
        pluginContext.referenceConstructors(RESOURCE_TYPE_CLASS_ID)
            .singleOrNull { it.owner.valueParameters.size == 3 }
            ?: error("Unable to resolve ResourceType constructor for WIT IR lowering")
    }

    private val bindingSignatureConstructorSymbol by lazy {
        pluginContext.referenceConstructors(BINDING_SIGNATURE_CLASS_ID)
            .singleOrNull { it.owner.valueParameters.size == 2 }
            ?: error("Unable to resolve BindingSignature constructor for WIT IR lowering")
    }

    private val bindingTypeRefConstructorSymbol by lazy {
        pluginContext.referenceConstructors(BINDING_TYPE_REF_CLASS_ID)
            .singleOrNull { constructor -> constructor.owner.valueParameters.size == 3 }
            ?: error("Unable to resolve BindingTypeRef constructor for WIT IR lowering")
    }

    private val listOfBindingTypeRefSymbol by lazy {
        pluginContext.referenceFunctions(LIST_OF_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.typeParameters.size == 1 &&
                    function.owner.valueParameters.size == 1 &&
                    function.owner.valueParameters[0].varargElementType != null
            } ?: error("Unable to resolve kotlin.collections.listOf for WIT IR lowering")
    }

    private val bindingValueShapeUnknownSymbol by lazy {
        pluginContext.referenceClass(BINDING_VALUE_SHAPE_UNKNOWN_CLASS_ID)
            ?: error("Unable to resolve BindingValueShape.Unknown for WIT IR lowering")
    }

    private val bindingValueShapeScalarConstructorSymbol by lazy {
        pluginContext.referenceConstructors(BINDING_VALUE_SHAPE_SCALAR_CLASS_ID)
            .singleOrNull { constructor -> constructor.owner.valueParameters.size == 1 }
            ?: error("Unable to resolve BindingValueShape.Scalar constructor for WIT IR lowering")
    }

    private val bindingValueShapeResourceHandleConstructorSymbol by lazy {
        pluginContext.referenceConstructors(BINDING_VALUE_SHAPE_RESOURCE_HANDLE_CLASS_ID)
            .singleOrNull { constructor -> constructor.owner.valueParameters.size == 1 }
            ?: error("Unable to resolve BindingValueShape.ResourceHandle constructor for WIT IR lowering")
    }

    fun apply() {
        val registerDriverSymbol = pluginContext.referenceFunctions(registerDriverCallableId).singleOrNull() ?: return

        plan.worlds.forEach { world ->
            val driver = world.driver ?: return@forEach
            if (debugLogging) {
                System.err.println("WIT lowering companion fqName=${driver.companion.fqNameWhenAvailable?.asString()} parent=${driver.companion.parentClassOrNull?.fqNameWhenAvailable?.asString()}")
            }
            val bindFunction = driver.bindFunction
            val runtimeParameter = bindFunction.valueParameters.firstOrNull() ?: return@forEach
            val driverObjectSymbol: IrClassSymbol = driver.driverClass.symbol
            val runtimeSlot = world.runtimeSlot
            val receiverParameter = bindFunction.dispatchReceiverParameter

            val builder = DeclarationIrBuilder(pluginContext, bindFunction.symbol)
            bindFunction.body = builder.irBlockBody {
                val runtimeTemp = irTemporary(
                    value = irGet(runtimeParameter),
                    nameHint = "runtime",
                )
                if (runtimeSlot != null && receiverParameter != null) {
                    runtimeSlot.property.setter?.let { setter ->
                        +irCall(setter.symbol).apply {
                            dispatchReceiver = irGet(receiverParameter)
                            putValueArgument(0, irGet(runtimeTemp))
                        }
                    } ?: run {
                        +irSetField(
                            irGet(receiverParameter),
                            runtimeSlot.backingField,
                            irGet(runtimeTemp),
                        )
                    }
                }

                val driverObject = irGetObject(driverObjectSymbol)
                +irCall(registerDriverSymbol).apply {
                    dispatchReceiver = irGet(runtimeTemp)
                    putValueArgument(0, driverObject)
                }
                +irReturn(irUnit())
            }

            driver.registerImportHandler?.let { function ->
                if (function.body == null) {
                    val runtimeParam = function.valueParameters.getOrNull(0)
                    val bindingNameParam = function.valueParameters.getOrNull(1)
                    val handlerParam = function.valueParameters.getOrNull(2)
                    if (runtimeParam != null && bindingNameParam != null && handlerParam != null) {
                        val importBuilder = DeclarationIrBuilder(pluginContext, function.symbol)
                        function.body = importBuilder.irBlockBody {
                            +irCall(registerImportHandlerSymbol).apply {
                                dispatchReceiver = irGet(runtimeParam)
                                putValueArgument(0, importBuilder.irString(world.packageId))
                                putValueArgument(1, importBuilder.irString(world.worldName))
                                putValueArgument(2, irGet(bindingNameParam))
                                putValueArgument(3, irGet(handlerParam))
                            }
                            +irReturn(irUnit())
                        }
                    }
                }
            }

            driver.registerExportHandler?.let { function ->
                if (function.body == null) {
                    val runtimeParam = function.valueParameters.getOrNull(0)
                    val bindingNameParam = function.valueParameters.getOrNull(1)
                    val handlerParam = function.valueParameters.getOrNull(2)
                    if (runtimeParam != null && bindingNameParam != null && handlerParam != null) {
                        val exportBuilder = DeclarationIrBuilder(pluginContext, function.symbol)
                        function.body = exportBuilder.irBlockBody {
                            +irCall(registerExportHandlerSymbol).apply {
                                dispatchReceiver = irGet(runtimeParam)
                                putValueArgument(0, exportBuilder.irString(world.packageId))
                                putValueArgument(1, exportBuilder.irString(world.worldName))
                                putValueArgument(2, irGet(bindingNameParam))
                                putValueArgument(3, irGet(handlerParam))
                            }
                            +irReturn(irUnit())
                        }
                    }
                }
            }

            driver.registerImports?.let { function ->
                if (function.body == null) {
                    val runtimeParam = function.valueParameters.getOrNull(0)
                    val implParam = function.valueParameters.getOrNull(1)
                    if (runtimeParam != null && implParam != null) {
                        val contract = driver.importsContract
                        val imports = world.bindings.filter { it.direction == org.jetbrains.kotlin.wit.runtime.WitBindingDirection.IMPORT }
                        val importBuilder = DeclarationIrBuilder(pluginContext, function.symbol)
                        function.body = importBuilder.irBlockBody {
                            if (imports.isNotEmpty()) {
                                val seenBindings = linkedSetOf<BindingKey>()
                                imports.forEach { binding ->
                                    if (!seenBindings.add(bindingKey(binding))) return@forEach
                                    val handler = findContractBinding(contract, binding)?.let { contractBinding ->
                                        createHostBindingHandler(importBuilder, runtimeParam, implParam, binding, contractBinding)
                                    } ?: createPendingHandlerCall(importBuilder, world, binding)
                                    +irCall(registerImportHandlerSymbol).apply {
                                        dispatchReceiver = irGet(runtimeParam)
                                        putValueArgument(0, importBuilder.irString(world.packageId))
                                        putValueArgument(1, importBuilder.irString(world.worldName))
                                        putValueArgument(2, importBuilder.irString(binding.bindingName))
                                        putValueArgument(3, handler)
                                    }
                                }
                            }
                            +irReturn(irUnit())
                        }
                    }
                }
            }

            driver.registerExports?.let { function ->
                if (function.body == null) {
                    val runtimeParam = function.valueParameters.getOrNull(0)
                    val implParam = function.valueParameters.getOrNull(1)
                    if (runtimeParam != null && implParam != null) {
                        val contract = driver.exportsContract
                        val exports = world.bindings.filter { it.direction == org.jetbrains.kotlin.wit.runtime.WitBindingDirection.EXPORT }
                        val exportBuilder = DeclarationIrBuilder(pluginContext, function.symbol)
                        function.body = exportBuilder.irBlockBody {
                            if (exports.isNotEmpty()) {
                                val seenBindings = linkedSetOf<BindingKey>()
                                exports.forEach { binding ->
                                    if (!seenBindings.add(bindingKey(binding))) return@forEach
                                    val handler = findContractBinding(contract, binding)?.let { contractBinding ->
                                        createHostBindingHandler(exportBuilder, runtimeParam, implParam, binding, contractBinding)
                                    } ?: createPendingHandlerCall(exportBuilder, world, binding)
                                    +irCall(registerExportHandlerSymbol).apply {
                                        dispatchReceiver = irGet(runtimeParam)
                                        putValueArgument(0, exportBuilder.irString(world.packageId))
                                        putValueArgument(1, exportBuilder.irString(world.worldName))
                                        putValueArgument(2, exportBuilder.irString(binding.bindingName))
                                        putValueArgument(3, handler)
                                    }
                                }
                            }
                            +irReturn(irUnit())
                        }
                    }
                }
            }

            driver.registerResources?.let { function ->
                if (function.body == null) {
                    val runtimeParam = function.valueParameters.getOrNull(0)
                    val implParam = function.valueParameters.getOrNull(1)
                    if (runtimeParam != null && implParam != null) {
                        val resources = world.resources
                        val contractBindings = driver.resourcesContract?.bindings.orEmpty()
                        val resourcesBuilder = DeclarationIrBuilder(pluginContext, function.symbol)
                        function.body = resourcesBuilder.irBlockBody {
                            if (resources.isNotEmpty()) {
                                val registeredResources = linkedSetOf<String>()
                                resources.forEach { resource ->
                                    val key = "${resource.interfaceName}/${resource.resourceName}"
                                    if (!registeredResources.add(key)) return@forEach
                                    val contractBinding = contractBindings[key]
                                    val factoryExpression = contractBinding?.let { binding ->
                                        irCall(binding.function.symbol).apply {
                                            dispatchReceiver = irGet(implParam)
                                        }
                                    } ?: return@forEach
                                    val helperFunction = findConstructorHelper(world, resource)
                                    if (helperFunction != null) {
                                        val lambda = createConstructorLambda(resourcesBuilder, world, helperFunction)
                                        +irCall(registerResourceFactorySymbol).apply {
                                            dispatchReceiver = irGet(runtimeParam)
                                            putValueArgument(0, createResourceTypeExpression(resourcesBuilder, world, resource))
                                            putValueArgument(1, factoryExpression)
                                            putValueArgument(2, lambda)
                                        }
                                    } else {
                                        +irCall(registerResourceSymbol).apply {
                                            dispatchReceiver = irGet(runtimeParam)
                                            putValueArgument(0, createResourceTypeExpression(resourcesBuilder, world, resource))
                                            putValueArgument(1, factoryExpression)
                                        }
                                    }
                                }
                            }
                            +irReturn(irUnit())
                        }
                    }
                }
            }
        }
    }

    private fun findContractBinding(
        contract: WitIrPlan.DriverContract?,
        binding: WitIrPlan.Binding,
    ): WitIrPlan.DriverContract.ContractBinding? {
        val candidate = contract?.bindings?.get(binding.bindingName) ?: return null
        if (candidate.direction != binding.direction) return null
        if (candidate.kind != binding.kind) return null
        return candidate
    }

    private fun findConstructorHelper(
        world: WitIrPlan.World,
        resource: WitIrPlan.Resource,
    ): IrSimpleFunction? {
        val bindingName = "[constructor]${resource.resourceName}"
        return world.constructors.firstOrNull { constructor ->
            constructor.direction == org.jetbrains.kotlin.wit.runtime.WitBindingDirection.EXPORT &&
                constructor.bindingName == bindingName &&
                constructor.declaration is IrSimpleFunction
        }?.declaration as? IrSimpleFunction
    }

    private fun createConstructorLambda(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        helper: IrSimpleFunction,
    ): IrExpression {
        val helperParent = helper.parentAsClass
        val helperParams = helper.valueParameters
        val runtimeParameterType = helperParams.getOrNull(0)?.type
            ?: error("Constructor helper missing runtime parameter")
        val factoryParameterType = helperParams.getOrNull(1)?.type
            ?: error("Constructor helper missing factory parameter")
        val helperArgumentCount = helperParams.size - 2

        val lambdaFunction = pluginContext.irFactory.buildFun {
            startOffset = builder.startOffset
            endOffset = builder.endOffset
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            returnType = helper.returnType
            visibility = DescriptorVisibilities.LOCAL
        }.apply {
            parent = builder.parent
            val runtimeParameter = buildValueParameter(this) {
                name = Name.identifier("runtime")
                type = runtimeParameterType
            }
            valueParameters += runtimeParameter
            val factoryParameter = buildValueParameter(this) {
                name = Name.identifier("factory")
                type = factoryParameterType
            }
            valueParameters += factoryParameter
            val argsParameter = buildValueParameter(this) {
                name = Name.identifier("arguments")
                type = bindingArgumentsType
            }
            valueParameters += argsParameter
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val helperCall = irCall(helper.symbol).apply {
                    dispatchReceiver = irGetObject(helperParent.symbol)
                    putValueArgument(0, irGet(runtimeParameter))
                    putValueArgument(1, irGet(factoryParameter))
                    repeat(helperArgumentCount) { index ->
                        val rawArgument = irCall(arrayGetSymbol).apply {
                            dispatchReceiver = irGet(argsParameter)
                            putTypeArgument(0, pluginContext.irBuiltIns.anyNType)
                            putValueArgument(0, irInt(index))
                        }
                        val helperParam = helperParams[index + 2]
                        val argument = if (helperParam.type == pluginContext.irBuiltIns.anyNType) {
                            rawArgument
                        } else {
                            irAs(rawArgument, helperParam.type)
                        }
                        putValueArgument(index + 2, argument)
                    }
                }
                +irReturn(helperCall)
            }
        }

        val functionType = pluginContext.irBuiltIns.functionN(3).typeWith(
            runtimeParameterType,
            factoryParameterType,
            bindingArgumentsType,
            helper.returnType,
        )

        return IrFunctionExpressionImpl(
            builder.startOffset,
            builder.endOffset,
            functionType,
            lambdaFunction,
            IrStatementOrigin.LAMBDA,
        )
    }

    private fun createHostBindingHandler(
        builder: DeclarationIrBuilder,
        runtimeParameter: IrValueParameter,
        implParameter: IrValueParameter,
        binding: WitIrPlan.Binding,
        contractBinding: WitIrPlan.DriverContract.ContractBinding,
    ): IrExpression {
        val lambda = pluginContext.irFactory.buildFun {
            startOffset = builder.startOffset
            endOffset = builder.endOffset
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            returnType = pluginContext.irBuiltIns.anyNType
            visibility = DescriptorVisibilities.LOCAL
        }.apply {
            parent = builder.parent
            val argumentsParameter = buildValueParameter(this) {
                name = Name.identifier("arguments")
                type = bindingArgumentsType
            }
            valueParameters += argumentsParameter
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val runtimeTemp = irTemporary(
                    value = irGet(runtimeParameter),
                    nameHint = "runtime",
                )
                val marshallerTemp = irTemporary(
                    value = irCall(componentRuntimeMarshallerGetterSymbol).apply {
                        dispatchReceiver = irGet(runtimeTemp)
                    },
                    nameHint = "marshaller",
                )
                val signatureExpression = DeclarationIrBuilder(pluginContext, symbol).run {
                    createBindingSignatureExpression(this, binding)
                }
                val signatureTemp = irTemporary(
                    value = signatureExpression,
                    nameHint = "signature",
                )
                val decodedArguments = irTemporary(
                    nameHint = "decodedArguments",
                    value = irCall(bindingValueMarshallerDecodeArgumentsSymbol).apply {
                        dispatchReceiver = irGet(marshallerTemp)
                        putValueArgument(0, irGet(signatureTemp))
                        putValueArgument(1, irGet(argumentsParameter))
                    },
                )
                val call = irCall(contractBinding.function.symbol).apply {
                    dispatchReceiver = irGet(implParameter)
                    contractBinding.function.valueParameters.forEachIndexed { index, parameter ->
                        val rawArgument = irCall(arrayGetSymbol).apply {
                            dispatchReceiver = irGet(decodedArguments)
                            putTypeArgument(0, pluginContext.irBuiltIns.anyNType)
                            putValueArgument(0, irInt(index))
                        }
                        val argument = if (parameter.type == pluginContext.irBuiltIns.anyNType) {
                            rawArgument
                        } else {
                            irAs(rawArgument, parameter.type)
                        }
                        putValueArgument(index, argument)
                    }
                }
                if (contractBinding.function.returnType.isUnit()) {
                    +call
                    +irReturn(irUnit())
                } else {
                    val encodedResult = irCall(bindingValueMarshallerEncodeResultSymbol).apply {
                        dispatchReceiver = irGet(marshallerTemp)
                        putValueArgument(0, irGet(signatureTemp))
                        putValueArgument(1, call)
                    }
                    +irReturn(encodedResult)
                }
            }
        }
        return IrFunctionExpressionImpl(
            builder.startOffset,
            builder.endOffset,
            bindingHandlerType,
            lambda,
            IrStatementOrigin.LAMBDA,
        )
    }

    private fun createResourceTypeExpression(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        resource: WitIrPlan.Resource,
    ): IrExpression = builder.irCall(resourceTypeConstructorSymbol).apply {
        putValueArgument(0, builder.irString(world.packageId))
        putValueArgument(1, builder.irString(resource.interfaceName))
        putValueArgument(2, builder.irString(resource.resourceName))
    }

    private fun createPendingHandlerCall(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ) = builder.irCall(pendingBindingHandlerSymbol).apply {
        putValueArgument(0, builder.irString(world.packageId))
        putValueArgument(1, builder.irString(world.worldName))
        putValueArgument(2, builder.irString(binding.bindingName))
        putValueArgument(3, enumValue(builder, BINDING_DIRECTION_CLASS_ID, binding.direction.name))
        putValueArgument(4, enumValue(builder, BINDING_KIND_CLASS_ID, binding.kind.name))
        putValueArgument(5, builder.irString(binding.runtimeTarget))
        putValueArgument(6, builder.irBoolean(binding.isAsync))
        putValueArgument(7, builder.irBoolean(binding.usesStreams))
    }

    private fun createBindingSignatureExpression(
        builder: DeclarationIrBuilder,
        binding: WitIrPlan.Binding,
    ): IrExpression = builder.irCall(bindingSignatureConstructorSymbol).apply {
        putValueArgument(0, createTypeRefListExpression(builder, binding.parameterTypes))
        putValueArgument(1, createTypeRefListExpression(builder, binding.resultTypes))
    }

    private fun createTypeRefListExpression(
        builder: DeclarationIrBuilder,
        prototypes: List<WitIrPlan.TypeRefPrototype>,
    ): IrExpression {
        val bindingTypeRefType = bindingTypeRefConstructorSymbol.owner.returnType
        val elements = prototypes.map { prototype ->
            builder.irCall(bindingTypeRefConstructorSymbol).apply {
                putValueArgument(0, builder.irString(prototype.typeRef))
                putValueArgument(1, builder.irString(prototype.label ?: ""))
                putValueArgument(2, createBindingValueShapeExpression(builder, prototype.shape))
            }
        }
        val vararg = builder.irVararg(bindingTypeRefType, elements)
        return builder.irCall(listOfBindingTypeRefSymbol).apply {
            putTypeArgument(0, bindingTypeRefType)
            putValueArgument(0, vararg)
        }
    }

    private fun createBindingValueShapeExpression(
        builder: DeclarationIrBuilder,
        shape: WitTypeShape,
    ): IrExpression = when (shape) {
        is WitTypeShape.Unknown -> builder.irGetObject(bindingValueShapeUnknownSymbol)
        is WitTypeShape.Scalar -> builder.irCall(bindingValueShapeScalarConstructorSymbol).apply {
            putValueArgument(0, builder.irString(shape.name))
        }
        is WitTypeShape.ResourceHandle -> builder.irCall(bindingValueShapeResourceHandleConstructorSymbol).apply {
            putValueArgument(0, enumValue(builder, RESOURCE_HANDLE_OWNERSHIP_CLASS_ID, shape.ownership.name))
        }
    }

    private fun enumValue(
        builder: DeclarationIrBuilder,
        enumClassId: ClassId,
        entryName: String,
    ): org.jetbrains.kotlin.ir.expressions.IrExpression {
        val enumClass = pluginContext.referenceClass(enumClassId)
            ?: error("Unable to resolve enum class for $enumClassId")
        val enumEntry = enumClass.owner.declarations
            .filterIsInstance<IrEnumEntry>()
            .firstOrNull { it.name.asString() == entryName }
            ?: error("Unable to resolve enum entry $entryName for $enumClassId")
        val enumType = enumEntry.parentAsClass.thisReceiver?.type
            ?: error("Enum class ${enumEntry.parentAsClass.name} missing this receiver type")
        return IrGetEnumValueImpl(
            builder.startOffset,
            builder.endOffset,
            enumType,
            enumEntry.symbol,
        )
    }

    private fun bindingKey(binding: WitIrPlan.Binding): BindingKey =
        BindingKey(binding.bindingName, binding.direction, binding.kind)

    private data class BindingKey(
        val name: String,
        val direction: org.jetbrains.kotlin.wit.runtime.WitBindingDirection,
        val kind: org.jetbrains.kotlin.wit.runtime.WitBindingKind,
    )

    private companion object {
        private val RUNTIME_PACKAGE = FqName("org.jetbrains.kotlin.wit.runtime")
        private val REGISTER_IMPORT_HANDLER_CALLABLE_ID =
            CallableId(ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime")), Name.identifier("registerImportHandler"))
        private val REGISTER_EXPORT_HANDLER_CALLABLE_ID =
            CallableId(ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime")), Name.identifier("registerExportHandler"))
        private val REGISTER_RESOURCE_CALLABLE_ID =
            CallableId(ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime")), Name.identifier("registerResource"))
        private val REGISTER_RESOURCE_FACTORY_CALLABLE_ID =
            CallableId(ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime")), Name.identifier("registerResourceFactory"))
        private val PENDING_BINDING_HANDLER_CALLABLE_ID =
            CallableId(RUNTIME_PACKAGE, Name.identifier("pendingBindingHandler"))
        private val BINDING_DIRECTION_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("WitBindingDirection"))
        private val BINDING_KIND_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("WitBindingKind"))
        private val RESOURCE_TYPE_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ResourceType"))
        private val BINDING_SIGNATURE_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("BindingSignature"))
        private val BINDING_TYPE_REF_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("BindingTypeRef"))
        private val BINDING_VALUE_SHAPE_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("BindingValueShape"))
        private val BINDING_VALUE_SHAPE_SCALAR_CLASS_ID =
            BINDING_VALUE_SHAPE_CLASS_ID.createNestedClassId(Name.identifier("Scalar"))
        private val BINDING_VALUE_SHAPE_RESOURCE_HANDLE_CLASS_ID =
            BINDING_VALUE_SHAPE_CLASS_ID.createNestedClassId(Name.identifier("ResourceHandle"))
        private val BINDING_VALUE_SHAPE_UNKNOWN_CLASS_ID =
            BINDING_VALUE_SHAPE_CLASS_ID.createNestedClassId(Name.identifier("Unknown"))
        private val BINDING_VALUE_MARSHALLER_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("BindingValueMarshaller"))
        private val COMPONENT_RUNTIME_MARSHALLER_PROPERTY_ID =
            CallableId(ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime")), Name.identifier("marshaller"))
        private val LIST_OF_CALLABLE_ID =
            CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
        private val RESOURCE_HANDLE_OWNERSHIP_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ResourceHandleOwnership"))
    }
}
