package org.jetbrains.kotlin.wit.compiler.driver

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.wit.compiler.ir.WasmIrGenerator
import org.jetbrains.kotlin.wit.compiler.ir.WasmPlanBuilder
import org.jetbrains.kotlin.wit.compiler.ir.WitIrModuleProcessor
import org.jetbrains.kotlin.wit.compiler.ir.WitIrPlanBuilder
import org.jetbrains.kotlin.wit.compiler.ir.render
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

public object WitBindingGenerationPipeline {
    public fun loadSchema(
        config: WitSchemaConfig,
        messageCollector: MessageCollector,
    ): WitSchemaIndex? = WitSchemaIndex.load(config, messageCollector)

    public fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
        schemaIndex: WitSchemaIndex,
        debugLogging: Boolean = false,
    ) {
        val codegenPlan = WasmPlanBuilder.build(schemaIndex.runtimeSchema)
        WasmIrGenerator(pluginContext).generate(moduleFragment, codegenPlan)

        val irPlan = WitIrPlanBuilder(pluginContext).build(moduleFragment)
        if (debugLogging) {
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
            println(irPlan.render())
        }

        WitIrModuleProcessor(
            debugLogging = debugLogging,
            schemaIndex = schemaIndex,
            pluginContext = pluginContext,
            plan = irPlan,
        ).process(moduleFragment)
    }
}
