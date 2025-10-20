package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.compiler.schema.WitInterfaceMetadata
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata

internal data class WorldMetadata(
    val packageId: String,
    val packageFqName: FqName,
    val runtimeWorld: WitRuntimeWorld,
    val worldSchema: WitWorldMetadata?,
    val interfaceBindings: Map<Name, InterfaceBindingMetadata>,
    val importBindings: Map<Name, BindingMetadata>,
    val exportBindings: Map<Name, BindingMetadata>,
    val resourceBindings: Map<Name, ResourceBindingMetadata>,
    val constructorHelpers: Map<Name, ConstructorHelperMetadata>,
    val driverCompanionClassId: ClassId,
    val driverClassId: ClassId,
    val bindCallableId: CallableId,
    val driverImportsClassId: ClassId?,
    val driverExportsClassId: ClassId?,
    val registerImportsCallableId: CallableId?,
    val registerExportsCallableId: CallableId?,
    val driverResourcesClassId: ClassId?,
    val registerResourcesCallableId: CallableId?,
) {
    fun findBindingByFunctionName(name: Name): BindingMetadata? {
        importBindings.values.firstOrNull { it.matchesFunctionName(name) }?.let { return it }
        return exportBindings.values.firstOrNull { it.matchesFunctionName(name) }
    }

    fun findResourceByFunctionName(name: Name): ResourceBindingMetadata? =
        resourceBindings.values.firstOrNull { it.hostFunctionName == name }

    fun findConstructorHelper(name: Name): ConstructorHelperMetadata? =
        constructorHelpers[name]

    fun constructorDirection(bindingName: String): BindingDirection? {
        importBindings.values.firstOrNull { it.binding.name == bindingName }?.let { return it.direction }
        exportBindings.values.firstOrNull { it.binding.name == bindingName }?.let { return it.direction }
        return null
    }
}

internal data class InterfaceBindingMetadata(
    val runtime: WitRuntimeInterface?,
    val schema: WitInterfaceMetadata?,
)

internal enum class BindingDirection {
    IMPORT,
    EXPORT,
}

internal data class BindingMetadata(
    val binding: WitRuntimeBinding,
    val direction: BindingDirection,
    val functionStubName: Name?,
    val hostFunctionName: Name?,
    val interfaceName: String?,
    val resourceName: String?,
) {
    fun matchesFunctionName(name: Name): Boolean {
        if (functionStubName == name) return true
        if (hostFunctionName == name) return true
        return false
    }
}

internal data class ResourceBindingMetadata(
    val runtimeInterface: WitRuntimeInterface,
    val resource: WitRuntimeResource,
    val hostFunctionName: Name,
)

internal data class ConstructorHelperMetadata(
    val functionName: Name,
    val constructor: WitRuntimeConstructor,
    val binding: BindingMetadata,
    val direction: BindingDirection,
)

internal enum class WitBindingIssue {
    ASYNC_UNSUPPORTED,
    STREAM_UNSUPPORTED,
    UNKNOWN_KIND,
}

internal object WitWorldDeclarationKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDeclaration"
}

internal object WitWorldDriverCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverCompanion"
}

internal object WitWorldDriverClassKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriver"
}

internal object WitWorldDriverResourcesKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverResources"
}

internal object WitWorldDriverImportsKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverImports"
}

internal object WitWorldDriverExportsKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverExports"
}

private object WitWorldClassMetadataKey : FirDeclarationDataKey()

private var FirRegularClass.witWorldMetadataInternal: WorldMetadata? by FirDeclarationDataRegistry.data(
    WitWorldClassMetadataKey,
)

internal var FirRegularClass.witWorldMetadata: WorldMetadata?
    get() = witWorldMetadataInternal
    set(value) {
        witWorldMetadataInternal = value
    }

private object WitWorldPropertyMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witWorldMetadata: WorldMetadata? by FirDeclarationDataRegistry.data(WitWorldPropertyMetadataKey)

internal val FirPropertySymbol.witWorldMetadata: WorldMetadata? by FirDeclarationDataRegistry.symbolAccessor(WitWorldPropertyMetadataKey)

private object WitWorldFunctionMetadataKey : FirDeclarationDataKey()

internal var FirSimpleFunction.witWorldFunctionMetadata: WorldMetadata? by FirDeclarationDataRegistry.data(WitWorldFunctionMetadataKey)

private object WitInterfaceMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witInterfaceMetadata: WitInterfaceMetadata? by FirDeclarationDataRegistry.data(WitInterfaceMetadataKey)

internal val FirPropertySymbol.witInterfaceMetadata: WitInterfaceMetadata? by FirDeclarationDataRegistry.symbolAccessor(
    WitInterfaceMetadataKey,
)

private object WitRuntimeInterfaceMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witRuntimeInterface: WitRuntimeInterface? by FirDeclarationDataRegistry.data(WitRuntimeInterfaceMetadataKey)

internal val FirPropertySymbol.witRuntimeInterface: WitRuntimeInterface? by FirDeclarationDataRegistry.symbolAccessor(
    WitRuntimeInterfaceMetadataKey,
)

private object WitBindingMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witBindingMetadata: BindingMetadata? by FirDeclarationDataRegistry.data(WitBindingMetadataKey)

internal val FirPropertySymbol.witBindingMetadata: BindingMetadata? by FirDeclarationDataRegistry.symbolAccessor(
    WitBindingMetadataKey,
)

private object WitResourceMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witResourceMetadata: ResourceBindingMetadata? by FirDeclarationDataRegistry.data(
    WitResourceMetadataKey,
)

internal val FirPropertySymbol.witResourceMetadata: ResourceBindingMetadata? by FirDeclarationDataRegistry.symbolAccessor(
    WitResourceMetadataKey,
)

private object WitBindingIssuesKey : FirDeclarationDataKey()

internal var FirProperty.witBindingIssuesOrNull: Set<WitBindingIssue>? by FirDeclarationDataRegistry.data(
    WitBindingIssuesKey,
)

internal var FirProperty.witBindingIssues: Set<WitBindingIssue>
    get() = witBindingIssuesOrNull ?: emptySet()
    set(value) {
        witBindingIssuesOrNull = if (value.isEmpty()) null else value
    }

private val FirPropertySymbol.witBindingIssuesOrNull: Set<WitBindingIssue>? by FirDeclarationDataRegistry.symbolAccessor(
    WitBindingIssuesKey,
)

internal val FirPropertySymbol.witBindingIssues: Set<WitBindingIssue>
    get() = witBindingIssuesOrNull ?: emptySet()

private object WitConstructorMetadataKey : FirDeclarationDataKey()

internal var FirConstructor.witConstructorMetadata: WitRuntimeConstructor? by FirDeclarationDataRegistry.data(
    WitConstructorMetadataKey,
)

internal val FirConstructorSymbol.witConstructorMetadata: WitRuntimeConstructor? by FirDeclarationDataRegistry.symbolAccessor(
    WitConstructorMetadataKey,
)
