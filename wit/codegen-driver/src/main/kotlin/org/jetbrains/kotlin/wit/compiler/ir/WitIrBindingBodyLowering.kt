@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irIfNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.defaultType
// Use driver-local enums for direction/kind

public class WitIrBindingBodyLowering(
    private val pluginContext: IrPluginContext,
    private val plan: WitIrPlan,
) {
    private val errorSymbol by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("kotlin"), Name.identifier("error")),
        ).singleOrNull { it.owner.valueParameters.size == 1 }
            ?: error("Unable to resolve kotlin.error(String) for WIT IR lowering")
    }

    private val pendingDelegateSymbol by lazy {
        pluginContext.referenceFunctions(PENDING_BINDING_DELEGATE_CALLABLE_ID)
            .singleOrNull { it.owner.valueParameters.size == 9 }
            ?: error("Unable to resolve pendingBindingDelegate helper for WIT IR lowering")
    }

    private val dispatchBindingSymbol by lazy {
        pluginContext.referenceFunctions(DISPATCH_BINDING_CALLABLE_ID)
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null &&
                    function.owner.valueParameters.size == 2 &&
                    function.owner.valueParameters[1].varargElementType != null
            } ?: error("Unable to resolve ComponentRuntime.dispatchBinding for WIT IR lowering")
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

    private val resourceFactoryCreateSymbol by lazy {
        pluginContext.referenceFunctions(CallableId(RESOURCE_FACTORY_CLASS_ID, Name.identifier("create")))
            .singleOrNull { function ->
                function.owner.dispatchReceiverParameter != null &&
                    function.owner.valueParameters.size == 1
            } ?: error("Unable to resolve ResourceFactory.create for WIT IR lowering")
    }

    private val allocateOwnedHandleSymbol by lazy {
        pluginContext.referenceFunctions(CallableId(RUNTIME_PACKAGE, Name.identifier("allocateOwnedHandle")))
            .singleOrNull { function ->
                function.owner.extensionReceiverParameter != null &&
                    function.owner.valueParameters.size == 1
            } ?: error("Unable to resolve ComponentRuntime.allocateOwnedHandle for WIT IR lowering")
    }

    private val resourceTypeConstructorSymbol by lazy {
        pluginContext.referenceConstructors(RESOURCE_TYPE_CLASS_ID)
            .singleOrNull { constructor -> constructor.owner.valueParameters.size == 3 }
            ?: error("Unable to resolve ResourceType constructor for WIT IR lowering")
    }

    private val resolveResourceSymbol by lazy {
        pluginContext.referenceFunctions(
            CallableId(COMPONENT_RUNTIME_CLASS_ID, Name.identifier("resolveResource")),
        ).singleOrNull { function ->
            function.owner.dispatchReceiverParameter != null &&
                function.owner.valueParameters.size == 1
        } ?: error("Unable to resolve ComponentRuntime.resolveResource for WIT IR lowering")
    }

    private val resourceFactoryClassSymbol by lazy {
        pluginContext.referenceClass(RESOURCE_FACTORY_CLASS_ID)
            ?: error("Unable to resolve ResourceFactory class for WIT IR lowering")
    }

    private val resourceFactoryType by lazy {
        resourceFactoryClassSymbol.owner.defaultType
    }

    private val unsupportedOperationExceptionStringConstructorSymbol by lazy {
        pluginContext.referenceConstructors(UNSUPPORTED_OPERATION_EXCEPTION_CLASS_ID)
            .firstOrNull { constructor ->
                constructor.owner.valueParameters.size == 1 &&
                    constructor.owner.valueParameters[0].type == pluginContext.irBuiltIns.stringType
            }
    }

    private val unsupportedOperationExceptionEmptyConstructorSymbol by lazy {
        pluginContext.referenceConstructors(UNSUPPORTED_OPERATION_EXCEPTION_CLASS_ID)
            .firstOrNull { constructor -> constructor.owner.valueParameters.isEmpty() }
            ?: error("Unable to resolve UnsupportedOperationException() constructor for WIT IR lowering")
    }


    fun apply() {
        plan.worlds.forEach { world ->
            val propertyLookup = world.bindings
                .mapNotNull { binding ->
                    val property = binding.declaration as? IrProperty ?: return@mapNotNull null
                    bindingKey(binding) to property
                }
                .toMap()
            world.bindings.forEach { binding ->
                when (val declaration = binding.declaration) {
                    is IrProperty -> lowerProperty(world, binding, declaration)
                    is IrSimpleFunction -> lowerFunction(world, binding, declaration, propertyLookup)
                    else -> Unit
                }
            }

            world.constructors.forEach { constructor ->
                val declaration = constructor.declaration
                if (declaration is IrSimpleFunction) {
                    lowerConstructorHelper(world, constructor, declaration)
                }
            }
        }
    }

    private fun lowerProperty(world: WitIrPlan.World, binding: WitIrPlan.Binding, property: IrProperty) {
        val getter = property.getter ?: return
        if (getter.body != null) return
        if (property.modality == Modality.ABSTRACT) property.modality = Modality.OPEN
        if (getter.modality == Modality.ABSTRACT) getter.modality = Modality.OPEN

        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = builder.irBlockBody {
            +irReturn(createPendingDelegateCall(builder, world, binding))
        }
    }

    private fun lowerFunction(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        function: IrSimpleFunction,
        propertyLookup: Map<BindingKey, IrProperty>,
    ) {
        if (function.body != null) return
        if (function.modality == Modality.ABSTRACT) function.modality = Modality.OPEN
        if (shouldLowerConstructorStub(binding) && lowerConstructorStub(world, binding, function)) return

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val receiver = function.dispatchReceiverParameter
        val bindingProperty = propertyLookup[bindingKey(binding)]
        function.body = builder.irBlockBody {
            val getter = bindingProperty?.getter
            val runtimeSlot = world.runtimeSlot
            if (receiver != null && getter != null && runtimeSlot != null) {
                val companion = runtimeSlot.property.parentAsClass
                val runtimeValue = runtimeSlot.property.getter?.let { runtimeGetter ->
                    irCall(runtimeGetter.symbol).apply {
                        dispatchReceiver = irGetObject(companion.symbol)
                    }
                } ?: builder.irGetField(
                    irGetObject(companion.symbol),
                    runtimeSlot.backingField,
                )
                val runtimeTemp = irTemporary(
                    value = runtimeValue,
                    nameHint = "runtime",
                )
                val argumentsVararg = irVararg(
                    pluginContext.irBuiltIns.anyNType,
                    function.valueParameters.map { parameter -> irGet(parameter) },
                )
                val thenPart = createRuntimeMissingCall(builder, world, binding)
                val elsePart = if (function.returnType.isUnit()) {
                    irBlock {
                        +irCall(dispatchBindingSymbol).apply {
                            dispatchReceiver = irGet(runtimeTemp)
                            putValueArgument(
                                0,
                                irCall(getter.symbol).apply { dispatchReceiver = irGet(receiver) },
                            )
                            putValueArgument(1, argumentsVararg)
                        }
                        +irUnit()
                    }
                } else {
                    irCall(dispatchBindingSymbol).apply {
                        dispatchReceiver = irGet(runtimeTemp)
                        putValueArgument(
                            0,
                            irCall(getter.symbol).apply { dispatchReceiver = irGet(receiver) },
                        )
                        putValueArgument(1, argumentsVararg)
                    }
                }
                +irReturn(
                    irIfNull(
                        function.returnType,
                        irGet(runtimeTemp),
                        thenPart,
                        elsePart,
                    ),
                )
            } else {
                +irReturn(createBindingNotLoweredCall(builder, world, binding))
            }
        }
    }

    private fun shouldLowerConstructorStub(binding: WitIrPlan.Binding): Boolean =
        binding.direction == WasmBindingDirection.EXPORT &&
            binding.bindingName.startsWith(CONSTRUCTOR_BINDING_PREFIX)

    private fun lowerConstructorStub(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        function: IrSimpleFunction,
    ): Boolean {
        val runtimeSlot = world.runtimeSlot ?: return emitConstructorFallback(world, binding, function)
        val helper = findConstructorHelper(world, binding.bindingName) ?: return emitConstructorFallback(world, binding, function)
        val resourceIdentity = resolveResourceIdentity(world, binding) ?: return emitConstructorFallback(world, binding, function)

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        if (function.modality == Modality.ABSTRACT) function.modality = Modality.OPEN
        val companion = runtimeSlot.property.parentAsClass
        if (isBorrowedConstructor(binding)) {
            function.body = builder.irBlockBody {
                +createBorrowedConstructorUnsupportedCall(
                    builder,
                    world,
                    binding,
                    resolveMessageResourceIdentity(world, binding),
                )
            }
            return true
        }
        function.body = builder.irBlockBody {
            val runtimeValue = runtimeSlot.property.getter?.let { runtimeGetter ->
                irCall(runtimeGetter.symbol).apply {
                    dispatchReceiver = irGetObject(companion.symbol)
                }
            } ?: irGetField(
                irGetObject(companion.symbol),
                runtimeSlot.backingField,
            )
            val runtimeTemp = irTemporary(
                value = runtimeValue,
                nameHint = "runtime",
            )
            val thenPart = createRuntimeMissingCall(builder, world, binding)
            val elsePart = builder.irBlock {
                val resourceTypeExpression = createResourceTypeExpression(
                    this,
                    world.packageId,
                    resourceIdentity.interfaceName,
                    resourceIdentity.resourceName,
                )
                val factoryTemp = irTemporary(
                    value = irCall(resolveResourceSymbol).apply {
                        dispatchReceiver = irGet(runtimeTemp)
                        putValueArgument(0, resourceTypeExpression)
                    },
                    nameHint = "factory",
                )
                val helperCall = irCall(helper.symbol).apply {
                    dispatchReceiver = irGetObject(helper.parentAsClass.symbol)
                    putValueArgument(0, irGet(runtimeTemp))
                    putValueArgument(1, irAs(irGet(factoryTemp), resourceFactoryType))
                    function.valueParameters.forEachIndexed { index, parameter ->
                        putValueArgument(index + 2, irGet(parameter))
                    }
                }
                +irIfNull(
                    function.returnType,
                    irGet(factoryTemp),
                    createResourceFactoryMissingCall(this, world, binding, resourceIdentity),
                    helperCall,
                )
            }
            +irReturn(
                irIfNull(
                    function.returnType,
                    irGet(runtimeTemp),
                    thenPart,
                    elsePart,
                ),
            )
        }
        return true
    }

    private fun emitConstructorFallback(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        function: IrSimpleFunction,
    ): Boolean {
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        if (function.modality == Modality.ABSTRACT) function.modality = Modality.OPEN
        function.body = builder.irBlockBody {
            +irReturn(createBindingNotLoweredCall(builder, world, binding))
        }
        return true
    }

    private fun isBorrowedConstructor(binding: WitIrPlan.Binding): Boolean =
        binding.resultTypes.any { prototype ->
            val shape = prototype.shape
            shape is WitTypeShape.ResourceHandle &&
                shape.ownership == WitTypeShape.ResourceHandle.Ownership.BORROWED
        }

    private fun resolveMessageResourceIdentity(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ): ResourceIdentity =
        resolveResourceIdentity(world, binding) ?: ResourceIdentity(
            interfaceName = binding.interfaceName.takeIf { it.isNotBlank() } ?: "<unknown>",
            resourceName = binding.resourceName.takeIf { it.isNotBlank() }
                ?: binding.bindingName.removePrefix(CONSTRUCTOR_BINDING_PREFIX).ifBlank { binding.bindingName },
        )

    private fun createBorrowedConstructorUnsupportedCall(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        identity: ResourceIdentity,
    ): IrExpression {
        val exceptionExpression = unsupportedOperationExceptionStringConstructorSymbol?.let { constructor ->
            builder.irCall(constructor).apply {
                putValueArgument(0, builder.irString(borrowedConstructorMessage(world, binding, identity)))
            }
        } ?: builder.irCall(unsupportedOperationExceptionEmptyConstructorSymbol)
        return IrThrowImpl(
            builder.startOffset,
            builder.endOffset,
            pluginContext.irBuiltIns.nothingType,
            exceptionExpression,
        )
    }

    private fun borrowedConstructorMessage(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        identity: ResourceIdentity,
    ): String = buildString {
        append("Borrowed resource constructors are not supported yet: ")
        append(world.packageId)
        append('/')
        append(world.worldName)
        append(" -> ")
        append(binding.bindingName)
        append(" (resource=")
        append(identity.interfaceName)
        append('/')
        append(identity.resourceName)
        append(')')
    }

    private fun findConstructorHelper(
        world: WitIrPlan.World,
        bindingName: String,
    ): IrSimpleFunction? =
        world.constructors.firstOrNull { constructor ->
            constructor.direction == WasmBindingDirection.EXPORT &&
                constructor.bindingName == bindingName &&
                constructor.declaration is IrSimpleFunction
        }?.declaration as? IrSimpleFunction

    private fun resolveResourceIdentity(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ): ResourceIdentity? {
        val bindingResourceName = binding.resourceName.takeIf { it.isNotBlank() }
            ?: binding.bindingName.removePrefix(CONSTRUCTOR_BINDING_PREFIX).takeIf { it.isNotBlank() }

        val candidate = world.resources.firstOrNull { resource ->
            val resourceMatches = bindingResourceName?.let { it == resource.resourceName } ?: false
            val interfaceMatches = when {
                binding.interfaceName.isNotBlank() -> binding.interfaceName == resource.interfaceName
                else -> true
            }
            resourceMatches && interfaceMatches
        }

        val interfaceName = when {
            binding.interfaceName.isNotBlank() -> binding.interfaceName
            candidate != null -> candidate.interfaceName
            else -> ""
        }
        val resourceName = bindingResourceName ?: candidate?.resourceName.orEmpty()

        if (interfaceName.isBlank() || resourceName.isBlank()) {
            return candidate?.let { ResourceIdentity(it.interfaceName, it.resourceName) }
        }
        return ResourceIdentity(interfaceName, resourceName)
    }

    private fun createResourceTypeExpression(
        builder: IrBuilderWithScope,
        packageId: String,
        interfaceName: String,
        resourceName: String,
    ): IrExpression = builder.irCall(resourceTypeConstructorSymbol).apply {
        putValueArgument(0, builder.irString(packageId))
        putValueArgument(1, builder.irString(interfaceName))
        putValueArgument(2, builder.irString(resourceName))
    }

    private fun createResourceFactoryMissingCall(
        builder: IrBuilderWithScope,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        identity: ResourceIdentity,
    ): IrExpression = builder.irCall(errorSymbol).apply {
        putValueArgument(0, builder.irString(resourceFactoryMissingMessage(world, binding, identity)))
    }

    private fun resourceFactoryMissingMessage(
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
        identity: ResourceIdentity,
    ): String = buildString {
        append("WIT resource factory not registered: ")
        append(world.packageId)
        append('/')
        append(world.worldName)
        append(" -> ")
        append(binding.bindingName)
        append(" (resource=")
        append(identity.interfaceName)
        append('/')
        append(identity.resourceName)
        append(')')
    }

    private data class ResourceIdentity(
        val interfaceName: String,
        val resourceName: String,
    )

    private fun createBindingNotLoweredCall(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ) = builder.irCall(errorSymbol).apply {
        putValueArgument(0, builder.irString(todoMessage(world, binding)))
    }

    private fun createRuntimeMissingCall(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ) = builder.irCall(errorSymbol).apply {
        putValueArgument(0, builder.irString(runtimeMissingMessage(world, binding)))
    }

    private fun createConstructorDelegateAccess(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        constructor: WitIrPlan.Constructor,
    ): IrExpression {
        val binding = world.bindings.firstOrNull { it.bindingName == constructor.bindingName && it.direction == constructor.direction }
            ?: return builder.irCall(errorSymbol).apply {
                putValueArgument(0, builder.irString("Constructor delegate not found: ${constructor.bindingName}"))
            }
        val property = binding.declaration as? IrProperty
        val getter = property?.getter
        val receiver = world.runtimeSlot?.property?.parentAsClass
        return if (property != null && getter != null && receiver != null) {
            builder.irCall(getter.symbol).apply {
                dispatchReceiver = builder.irGetObject(receiver.symbol)
            }
        } else {
            createBindingNotLoweredCall(builder, world, binding)
        }
    }

    private fun lowerConstructorHelper(
        world: WitIrPlan.World,
        constructor: WitIrPlan.Constructor,
        function: IrSimpleFunction,
    ) {
        if (constructor.direction != WasmBindingDirection.EXPORT) return
       if (function.body != null) return
       if (function.valueParameters.size < 2) return

        val runtimeParam = function.valueParameters[0]
        val factoryParam = function.valueParameters[1]
        val userParams = function.valueParameters.drop(2)
        val binding = world.bindings.firstOrNull { candidate ->
            candidate.bindingName == constructor.bindingName && candidate.direction == constructor.direction
        }

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        if (binding != null && isBorrowedConstructor(binding)) {
            function.body = builder.irBlockBody {
                +createBorrowedConstructorUnsupportedCall(
                    builder,
                    world,
                    binding,
                    resolveMessageResourceIdentity(world, binding),
                )
            }
            return
        }
        function.body = builder.irBlockBody {
            val runtimeTemp = irTemporary(irGet(runtimeParam), "runtime")
            val factoryTemp = irTemporary(irGet(factoryParam), "factory")

            val delegate = createConstructorDelegateAccess(builder, world, constructor)
            val argumentsVararg = builder.irVararg(
                pluginContext.irBuiltIns.anyNType,
                userParams.map { parameter -> irGet(parameter) },
            )
            +builder.irCall(dispatchBindingSymbol).apply {
                dispatchReceiver = irGet(runtimeTemp)
                putValueArgument(0, delegate)
                putValueArgument(1, argumentsVararg)
            }

            val adapter = irCall(resourceFactoryCreateSymbol).apply {
                dispatchReceiver = irGet(factoryTemp)
                putValueArgument(0, irGet(runtimeTemp))
            }
            val handle = irCall(allocateOwnedHandleSymbol).apply {
                extensionReceiver = irGet(runtimeTemp)
                putValueArgument(0, adapter)
            }
            +irReturn(handle)
        }
    }


    private fun todoMessage(world: WitIrPlan.World, binding: WitIrPlan.Binding): String {
        val base = buildString {
            append("WIT binding not lowered: ")
            append(world.packageId)
            append('/')
            append(world.worldName)
            append(" -> ")
            append(binding.bindingName)
        }
        val extras = mutableListOf<String>()
        extras += binding.direction.name.lowercase()
        extras += binding.kind.name.lowercase()
        if (binding.runtimeTarget.isNotEmpty()) extras += "target=${binding.runtimeTarget}"
        if (binding.isAsync) extras += "async"
        if (binding.usesStreams) extras += "streams"
        val directionHint = when (binding.direction) {
            WasmBindingDirection.IMPORT -> "call Companion.registerImports"
            WasmBindingDirection.EXPORT -> "call Companion.registerExports"
        }
        if (extras.isNotEmpty()) {
            extras += directionHint
        }
        return if (extras.isEmpty()) "$base; $directionHint" else "$base (${extras.joinToString()})"
    }

    private fun runtimeMissingMessage(world: WitIrPlan.World, binding: WitIrPlan.Binding): String {
        val base = buildString {
            append("WIT runtime not bound: ")
            append(world.packageId)
            append('/')
            append(world.worldName)
            append(" -> ")
            append(binding.bindingName)
        }
        val extras = mutableListOf<String>()
        extras += binding.direction.name.lowercase()
        extras += binding.kind.name.lowercase()
        extras += "bind(runtime)"
        extras += when (binding.direction) {
            WasmBindingDirection.IMPORT -> "registerImports"
            WasmBindingDirection.EXPORT -> "registerExports"
        }
        return "$base (${extras.joinToString()})"
    }

    private fun createPendingDelegateCall(
        builder: DeclarationIrBuilder,
        world: WitIrPlan.World,
        binding: WitIrPlan.Binding,
    ) = builder.irCall(pendingDelegateSymbol).apply {
        putValueArgument(0, builder.irString(world.packageId))
        putValueArgument(1, builder.irString(world.worldName))
        putValueArgument(2, builder.irString(binding.bindingName))
        putValueArgument(3, enumValue(builder, BINDING_DIRECTION_CLASS_ID, binding.direction.name))
        putValueArgument(4, enumValue(builder, BINDING_KIND_CLASS_ID, binding.kind.name))
        putValueArgument(5, builder.irString(binding.runtimeTarget))
        putValueArgument(6, builder.irBoolean(binding.isAsync))
        putValueArgument(7, builder.irBoolean(binding.usesStreams))
        putValueArgument(8, createBindingSignatureExpression(builder, binding))
    }

    private fun createBindingSignatureExpression(
        builder: DeclarationIrBuilder,
        binding: WitIrPlan.Binding,
    ): IrExpression {
        val parameterList = createTypeRefListExpression(builder, binding.parameterTypes)
        val resultList = createTypeRefListExpression(builder, binding.resultTypes)
        return builder.irCall(bindingSignatureConstructorSymbol).apply {
            putValueArgument(0, parameterList)
            putValueArgument(1, resultList)
        }
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

    private fun enumValue(
        builder: DeclarationIrBuilder,
        enumClassId: ClassId,
        entryName: String,
    ): IrExpression {
        val enumClass = pluginContext.referenceClass(enumClassId)
            ?: error("Unable to resolve enum class for $enumClassId")
        val entry = enumClass.owner.declarations
            .filterIsInstance<IrEnumEntry>()
            .firstOrNull { it.name.asString() == entryName }
            ?: error("Unable to resolve enum entry $entryName for $enumClassId")
        val enumType = entry.parentAsClass.thisReceiver?.type
            ?: error("Enum class ${entry.parentAsClass.name} missing this receiver type")
        return IrGetEnumValueImpl(builder.startOffset, builder.endOffset, enumType, entry.symbol)
    }

    private fun bindingKey(binding: WitIrPlan.Binding): BindingKey =
        BindingKey(binding.bindingName, binding.direction, binding.kind)

    private fun createBindingValueShapeExpression(
        builder: DeclarationIrBuilder,
        shape: WitTypeShape,
    ): IrExpression = when (shape) {
        is WitTypeShape.Unknown -> builder.irGetObject(bindingValueShapeUnknownSymbol)
        is WitTypeShape.Scalar -> builder.irCall(bindingValueShapeScalarConstructorSymbol).apply {
            putValueArgument(0, builder.irString(shape.name))
        }
        is WitTypeShape.ResourceHandle -> builder.irCall(bindingValueShapeResourceHandleConstructorSymbol).apply {
            val ownershipName = shape.ownership.name
            putValueArgument(0, enumValue(builder, RESOURCE_HANDLE_OWNERSHIP_CLASS_ID, ownershipName))
        }
    }

    private companion object {
        private val RUNTIME_PACKAGE = FqName("org.jetbrains.kotlin.wit.runtime")
        private val PENDING_BINDING_DELEGATE_CALLABLE_ID =
            CallableId(RUNTIME_PACKAGE, Name.identifier("pendingBindingDelegate"))
        private val BINDING_DIRECTION_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("WitBindingDirection"))
        private val BINDING_KIND_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("WitBindingKind"))
        private val COMPONENT_RUNTIME_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ComponentRuntime"))
        private val DISPATCH_BINDING_CALLABLE_ID =
            CallableId(COMPONENT_RUNTIME_CLASS_ID, Name.identifier("dispatchBinding"))
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
        private val RESOURCE_FACTORY_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ResourceFactory"))
        private val RESOURCE_TYPE_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ResourceType"))
        private val RESOURCE_HANDLE_OWNERSHIP_CLASS_ID =
            ClassId(RUNTIME_PACKAGE, Name.identifier("ResourceHandleOwnership"))
        private val LIST_OF_CALLABLE_ID =
            CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
        private const val CONSTRUCTOR_BINDING_PREFIX: String = "[constructor]"
        private val UNSUPPORTED_OPERATION_EXCEPTION_CLASS_ID =
            ClassId(FqName("kotlin"), Name.identifier("UnsupportedOperationException"))
    }

    private data class BindingKey(
        val name: String,
        val direction: WasmBindingDirection,
        val kind: WasmBindingKind,
    )
}
