package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.wit.codegen.core.plan.BindingPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.BindingTargetPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan
import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.wit.model.WitFunction

internal enum class WasmBindingDirection(val prefix: String) {
    IMPORT("Import"),
    EXPORT("Export"),
}

internal data class WasmBindingEntry(
    val bindingName: String,
    val direction: WasmBindingDirection,
    val kind: org.jetbrains.kotlin.wit.runtime.WitBindingKind,
    val propertyName: String,
    val functionName: String?,
    val interfaceName: String,
    val resourceName: String,
    val runtimeTarget: String,
    val isAsync: Boolean,
    val usesStreams: Boolean,
    val signature: WitFunction?,
)

internal data class WasmResourceEntry(
    val interfaceName: String,
    val resourceName: String,
    val propertyName: String,
    val ownHandleType: String,
    val borrowHandleType: String,
)

internal fun buildBindingEntries(world: WorldPlan): List<WasmBindingEntry> = buildList {
    world.imports.forEach { add(it.toEntry(WasmBindingDirection.IMPORT)) }
    world.exports.forEach { add(it.toEntry(WasmBindingDirection.EXPORT)) }
}

internal fun buildResourceEntries(world: WorldPlan): List<WasmResourceEntry> {
    val seen = linkedSetOf<Pair<String, String>>()
    val result = mutableListOf<WasmResourceEntry>()
    (world.imports + world.exports)
        .filter { it.kind == BindingKind.RESOURCE }
        .forEach { binding ->
            val raw = (binding.target as? BindingTargetPlan.Resource)?.name ?: binding.name
            val (iface, resource) = splitResourceTarget(raw)
            if (seen.add(iface to resource)) {
                result += WasmResourceEntry(
                    interfaceName = iface,
                    resourceName = resource,
                    propertyName = resourcePropertyName(iface, resource),
                    ownHandleType = handleType("own", iface, resource),
                    borrowHandleType = handleType("borrow", iface, resource),
                )
            }
        }
    return result
}

private fun BindingPlan.toEntry(direction: WasmBindingDirection): WasmBindingEntry {
    val (interfaceName, resourceName, runtimeTarget) = when (val target = target) {
        is BindingTargetPlan.Interface -> Triple(target.name, "", target.name)
        is BindingTargetPlan.Resource -> Triple("", target.name, target.name)
        is BindingTargetPlan.Function -> Triple("", "", target.name)
    }
    return WasmBindingEntry(
        bindingName = name,
        direction = direction,
        kind = when (kind) {
            BindingKind.FUNCTION -> org.jetbrains.kotlin.wit.runtime.WitBindingKind.FUNCTION
            BindingKind.INTERFACE -> org.jetbrains.kotlin.wit.runtime.WitBindingKind.INTERFACE
            BindingKind.RESOURCE -> org.jetbrains.kotlin.wit.runtime.WitBindingKind.RESOURCE
        },
        propertyName = bindingPropertyName(direction.prefix, name),
        functionName = signature?.let { bindingFunctionName(direction.prefix, name) },
        interfaceName = interfaceName,
        resourceName = resourceName,
        runtimeTarget = runtimeTarget,
        isAsync = signature?.isAsync == true,
        usesStreams = signature?.usesStreams == true,
        signature = signature,
    )
}
