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
        // Skip IR generation if runtime symbols are not available on the compilation classpath
        val runtimeClassId = org.jetbrains.kotlin.name.ClassId.topLevel(org.jetbrains.kotlin.name.FqName("org.jetbrains.kotlin.wit.runtime.ComponentRuntime"))
        val hasRuntime = pluginContext.referenceClass(runtimeClassId) != null
        if (!hasRuntime) {
            if (debugLogging) println("WIT IR extension: runtime not found on classpath; skipping IR glue generation")
            return
        }
        WitBindingGenerationPipeline.generate(
            moduleFragment = moduleFragment,
            pluginContext = pluginContext,
            schemaIndex = schemaIndex,
            debugLogging = debugLogging,
        )
    }
}
