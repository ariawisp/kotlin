package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

class WitIrGenerationExtension(
    private val options: WitPluginOptions,
    private val schemaIndex: WitSchemaIndex,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val codegenPlan = WasmPlanBuilder.build(schemaIndex.runtimeSchema)
        WasmIrGenerator(pluginContext).generate(moduleFragment, codegenPlan)

        val plan = WitIrPlanBuilder(pluginContext).build(moduleFragment)
        if (options.debug) {
            println(
                buildString {
                    append("WIT IR extension invoked for module ${moduleFragment.name} with ")
                    append(schemaIndex.witPackages.size)
                    append(" resolved package(s)")
                    if (schemaIndex.packageMetadata.isNotEmpty()) {
                        append(" (metadata packages=")
                        append(schemaIndex.packageMetadata.joinToString { it.name })
                        append(')')
                    }
                    if (schemaIndex.enabledFeatures.isNotEmpty()) {
                        append(" and features=")
                        append(schemaIndex.enabledFeatures.joinToString(prefix = "[", postfix = "]"))
                    }
                },
            )
            println(plan.render())
        }
        // TODO: add IR text tests ensuring [plan] extraction matches FIR generation previews.
        WitIrModuleProcessor(options, schemaIndex, pluginContext, plan).process(moduleFragment)
    }
}
