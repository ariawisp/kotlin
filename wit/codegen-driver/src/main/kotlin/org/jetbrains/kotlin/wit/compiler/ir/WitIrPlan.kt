package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.wit.runtime.WitBindingDirection
import org.jetbrains.kotlin.wit.runtime.WitBindingKind

internal data class WitIrPlan(
    val worlds: List<World>,
) {
    internal data class World(
        val irClass: IrClass,
        val packageId: String,
        val worldName: String,
        val bindings: List<Binding>,
        val resources: List<Resource>,
        val constructors: List<Constructor>,
        val driver: Driver?,
        val runtimeSlot: RuntimeSlot?,
    )

    internal data class Binding(
        val declaration: IrDeclaration,
        val declarationName: String,
        val direction: WitBindingDirection,
        val kind: WitBindingKind,
        val interfaceName: String,
        val resourceName: String,
        val bindingName: String,
        val runtimeTarget: String,
        val isAsync: Boolean,
        val usesStreams: Boolean,
        val parameterTypes: List<TypeRefPrototype>,
        val resultTypes: List<TypeRefPrototype>,
    )

    internal data class Resource(
        val declaration: IrDeclaration,
        val declarationName: String,
        val interfaceName: String,
        val resourceName: String,
        val ownHandleType: String,
        val borrowHandleType: String,
    )

    internal data class Constructor(
        val declaration: IrDeclaration,
        val bindingName: String,
        val direction: WitBindingDirection,
    )

    internal data class Driver(
        val companion: IrClass,
        val driverClass: IrClass,
        val bindFunction: IrSimpleFunction,
        val registerImportHandler: IrSimpleFunction?,
        val registerExportHandler: IrSimpleFunction?,
        val registerImports: IrSimpleFunction?,
        val registerExports: IrSimpleFunction?,
        val registerResources: IrSimpleFunction?,
        val importsContract: DriverContract?,
        val exportsContract: DriverContract?,
        val resourcesContract: ResourcesContract?,
    )

    internal data class RuntimeSlot(
        val property: IrProperty,
        val backingField: IrField,
    )

    internal data class TypeRefPrototype(
        val label: String?,
        val typeRef: String,
        val shape: WitTypeShape,
    )

    internal data class DriverContract(
        val irClass: IrClass,
        val bindings: Map<String, ContractBinding>,
    ) {
        internal data class ContractBinding(
            val function: IrSimpleFunction,
            val direction: WitBindingDirection,
            val kind: WitBindingKind,
        )
    }

    internal data class ResourcesContract(
        val irClass: IrClass,
        val bindings: Map<String, ResourceBinding>,
    ) {
        internal data class ResourceBinding(
            val function: IrSimpleFunction,
            val interfaceName: String,
            val resourceName: String,
        )
    }
}

internal fun WitIrPlan.render(): String {
    if (worlds.isEmpty()) return "WIT IR plan: <empty>"
    return buildString {
        append("WIT IR plan: ")
        append(worlds.size)
        append(" world(s)")
        worlds.forEach { world ->
            appendLine()
            append("  • ${world.packageId}/${world.worldName}")
            world.irClass.fqNameWhenAvailable?.let { fqName ->
                append(" -> ")
                append(fqName.asString())
            }
            appendLine()
            append("    bindings (${world.bindings.size}):")
            if (world.bindings.isEmpty()) {
                append(" <none>")
            } else {
                world.bindings.forEach { binding ->
                    appendLine()
                    append("      - ")
                    append(binding.declarationName)
                    append(" :: ")
                    append(binding.direction)
                    append('/')
                    append(binding.kind)
                    append(" [")
                    append(binding.bindingName)
                    append("]")
                    if (binding.interfaceName.isNotEmpty()) {
                        append(" interface=")
                        append(binding.interfaceName)
                    }
                    if (binding.resourceName.isNotEmpty()) {
                        append(" resource=")
                        append(binding.resourceName)
                    }
                    if (binding.runtimeTarget.isNotEmpty()) {
                        append(" target=")
                        append(binding.runtimeTarget)
                    }
                    if (binding.isAsync) append(" async")
                    if (binding.usesStreams) append(" streams")
                    if (binding.parameterTypes.isNotEmpty()) {
                        append(" params=")
                        append(
                            binding.parameterTypes.joinToString(
                                prefix = "(",
                                postfix = ")",
                                separator = ", ",
                            ) { prototype ->
                                val label = prototype.label?.takeIf { it.isNotBlank() }
                                val base = if (label != null) "$label:${prototype.typeRef}" else prototype.typeRef
                                when (prototype.shape) {
                                    is WitTypeShape.Unknown -> base
                                    is WitTypeShape.Scalar -> "$base<scalar=${prototype.shape.name}>"
                                    is WitTypeShape.ResourceHandle ->
                                        "$base<handle=${prototype.shape.ownership.name.lowercase()}>"
                                }
                            },
                        )
                    }
                    if (binding.resultTypes.isNotEmpty()) {
                        append(" results=")
                        append(
                            binding.resultTypes.joinToString(
                                prefix = "(",
                                postfix = ")",
                                separator = ", ",
                            ) { prototype ->
                                val label = prototype.label?.takeIf { it.isNotBlank() }
                                val base = if (label != null) "$label:${prototype.typeRef}" else prototype.typeRef
                                when (prototype.shape) {
                                    is WitTypeShape.Unknown -> base
                                    is WitTypeShape.Scalar -> "$base<scalar=${prototype.shape.name}>"
                                    is WitTypeShape.ResourceHandle ->
                                        "$base<handle=${prototype.shape.ownership.name.lowercase()}>"
                                }
                            },
                        )
                    }
                }
            }
            appendLine()
            append("    resources (${world.resources.size}):")
            if (world.resources.isEmpty()) {
                append(" <none>")
            } else {
                world.resources.forEach { resource ->
                    appendLine()
                    append("      - ")
                    append(resource.declarationName)
                    append(" :: ")
                    append(resource.interfaceName)
                    append('/')
                    append(resource.resourceName)
                    if (resource.ownHandleType.isNotEmpty()) {
                        append(" own=")
                        append(resource.ownHandleType)
                    }
                    if (resource.borrowHandleType.isNotEmpty()) {
                        append(" borrow=")
                        append(resource.borrowHandleType)
                    }
                }
            }
            appendLine()
            append("    constructors (${world.constructors.size}):")
            if (world.constructors.isEmpty()) {
                append(" <none>")
            } else {
                world.constructors.forEach { constructor ->
                    appendLine()
                    append("      - ")
                    append(constructor.bindingName)
                    append(" :: ")
                    append(constructor.direction)
                }
            }
            appendLine()
            append("    driver:")
            val driver = world.driver
            if (driver == null) {
                append(" <none>")
            } else {
                appendLine()
                append("      companion=")
                driver.companion.fqNameWhenAvailable?.let { append(it.asString()) } ?: append(driver.companion.name)
                append(" class=")
                driver.driverClass.fqNameWhenAvailable?.let { append(it.asString()) } ?: append(driver.driverClass.name)
                append(" bind=")
                append(driver.bindFunction.name)
                append(" registerImports=")
                append(driver.registerImports?.name ?: "<none>")
                append(" registerExports=")
                append(driver.registerExports?.name ?: "<none>")
                append(" registerResources=")
                append(driver.registerResources?.name ?: "<none>")
                val importCount = driver.importsContract?.bindings?.size ?: 0
                val exportCount = driver.exportsContract?.bindings?.size ?: 0
                val resourceCount = driver.resourcesContract?.bindings?.size ?: 0
                append(" contracts(imports=")
                append(importCount)
                append(", exports=")
                append(exportCount)
                append(", resources=")
                append(resourceCount)
                append(')')
            }
        }
    }
}
