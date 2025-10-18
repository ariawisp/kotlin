package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

/**
 * Temporary IR stub. The real implementation will arrive when Stage 3 wiring lands.
 */
internal class WitIrModuleProcessor(
    private val debugLogging: Boolean,
    private val schemaIndex: WitSchemaIndex,
    private val pluginContext: IrPluginContext,
    private val plan: WitIrPlan,
) {
    fun process(moduleFragment: IrModuleFragment) {
        if (debugLogging) {
            val bindingCount = plan.worlds.sumOf { it.bindings.size }
            val resourceCount = plan.worlds.sumOf { it.resources.size }
            val constructorCount = plan.worlds.sumOf { it.constructors.size }
            val driverCount = plan.worlds.count { it.driver != null }
            println(
                buildString {
                    append("WIT IR glue for module ${moduleFragment.name} ")
                    append("(worlds=${plan.worlds.size}")
                    append(", bindings=")
                    append(bindingCount)
                    append(", resources=")
                    append(resourceCount)
                    append(", constructors=")
                    append(constructorCount)
                    append(", drivers=")
                    append(driverCount)
                    append(", schemas=")
                    append(schemaIndex.packageMetadata.size)
                    append(')')
                    plan.worlds.firstOrNull()?.let { world ->
                        append(" [firstWorld=")
                        append(world.packageId)
                        append('/')
                        append(world.worldName)
                        append(']')
                    }
                    append('.')
                },
            )
        }
        WitIrCompanionNormalizationLowering(pluginContext).apply(moduleFragment)
        WitIrDriverRegistrationLowering(pluginContext, debugLogging, plan).apply()
        WitIrBindingBodyLowering(pluginContext, plan).apply()
        // TODO: Stage 3 will populate additional adapters here.
    }
}
