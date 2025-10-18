package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

class WitIrGenerationExtension(
    private val debugLogging: Boolean,
    private val schemaIndex: WitSchemaIndex,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        WitBindingGenerationPipeline.generate(
            moduleFragment = moduleFragment,
            pluginContext = pluginContext,
            schemaIndex = schemaIndex,
            debugLogging = debugLogging,
        )
    }
}
