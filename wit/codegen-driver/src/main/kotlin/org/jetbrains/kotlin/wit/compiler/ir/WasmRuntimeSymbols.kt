@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.types.classifierOrNull
// Avoid compile-time dependency on runtime classes; use FQNs at runtime

internal class WasmRuntimeSymbols(private val context: IrPluginContext) {
    val builtIns: IrBuiltIns = context.irBuiltIns

    val stringType: IrType = builtIns.stringType
    val booleanType: IrType = builtIns.booleanType
    val unitType: IrType = builtIns.unitType
    val nothingType: IrType = builtIns.nothingType
    val anyNullableType: IrType = builtIns.anyNType
    val stringArrayType: IrType = builtIns.arrayClass.typeWith(stringType)
    val arrayAnyNullableType: IrType = builtIns.arrayClass.typeWith(anyNullableType)

    private val componentRuntimeClass: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.ComponentRuntime")
    val componentRuntimeType: IrType = componentRuntimeClass.owner.defaultType

    val worldDriverClass: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WorldDriver")
    val worldDriverType: IrType = worldDriverClass.owner.defaultType
    val worldDriverProperties = worldDriverClass.owner.properties.associateBy { it.name.asString() }
    val worldDriverFunctions = worldDriverClass.owner.functions.associateBy { it.name.asString() }

    val resourceFactoryType: IrType = referenceClass("org.jetbrains.kotlin.wit.runtime.ResourceFactory").owner.defaultType
    val bindingDelegateType: IrType = referenceClass("org.jetbrains.kotlin.wit.runtime.BindingDelegate").owner.defaultType

    val pendingBindingDelegate: IrSimpleFunctionSymbol = referenceFunction(
        "org.jetbrains.kotlin.wit.runtime.pendingBindingDelegate"
    ) { it.owner.valueParameters.size >= 8 }

    private val function1Class: IrClassSymbol = referenceClass("kotlin.Function1")
    val bindingHandlerType: IrType = function1Class.owner.typeWith(arrayAnyNullableType, anyNullableType)
    private val illegalStateExceptionClass: IrClassSymbol = referenceClass("kotlin.IllegalStateException")
    val illegalStateExceptionConstructor: IrConstructor =
        illegalStateExceptionClass.owner.declarations.filterIsInstance<IrConstructor>().singleOrNull { constructor ->
            if (constructor.valueParameters.size != 1) return@singleOrNull false
            val classifier = constructor.valueParameters[0].type.classifierOrNull
            classifier is IrClassSymbol && classifier.owner.fqNameWhenAvailable?.asString() == "kotlin.String"
        } ?: error(
            "Expected IllegalStateException(String) constructor; available=" +
                illegalStateExceptionClass.owner.declarations.filterIsInstance<IrConstructor>()
                    .joinToString { ctor ->
                        ctor.valueParameters.joinToString(prefix = "(", postfix = ")") { param -> param.type.toString() }
                    },
        )

    val witBindingAnnotation: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitBinding")
    val witBindingConstructor: IrConstructor = singleConstructor(witBindingAnnotation)

    val witResourceAnnotation: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitResource")
    val witResourceConstructor: IrConstructor = singleConstructor(witResourceAnnotation)

    val witConstructorAnnotation: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitConstructor")
    val witConstructorConstructor: IrConstructor = singleConstructor(witConstructorAnnotation)

    val witWorldAnnotation: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitWorld")
    val witWorldConstructor: IrConstructor = singleConstructor(witWorldAnnotation)

    val bindingDirectionEnum: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitBindingDirection")
    val bindingKindEnum: IrClassSymbol = referenceClass("org.jetbrains.kotlin.wit.runtime.WitBindingKind")

    private fun referenceClass(fqName: String): IrClassSymbol {
        val classId = ClassId.topLevel(FqName(fqName))
        return context.referenceClass(classId) ?: error("Unable to resolve class '$fqName'")
    }

    private fun referenceFunction(
        fqName: String,
        predicate: (IrSimpleFunctionSymbol) -> Boolean = { true },
    ): IrSimpleFunctionSymbol {
        val packageName = fqName.substringBeforeLast('.', missingDelimiterValue = "")
        val callableName = fqName.substringAfterLast('.')
        val callableId = CallableId(
            if (packageName.isEmpty()) FqName.ROOT else FqName(packageName),
            Name.identifier(callableName),
        )
        val allCandidates = context.referenceFunctions(callableId)
        val matching = allCandidates.filter(predicate)
        if (matching.isEmpty()) {
            val available = allCandidates.mapNotNull { it.owner.fqNameWhenAvailable?.asString() }
            error("Unable to resolve function '$fqName' (available candidates=$available)")
        }
        if (matching.size > 1) {
            val available = matching.mapNotNull { it.owner.fqNameWhenAvailable?.asString() }
            error("Multiple candidates matched function '$fqName': $available")
        }
        return matching.single()
    }

    private fun singleConstructor(symbol: IrClassSymbol): IrConstructor =
        symbol.owner.declarations.filterIsInstance<IrConstructor>().singleOrNull()
            ?: error("Expected single constructor for ${symbol.owner.name}")
}
