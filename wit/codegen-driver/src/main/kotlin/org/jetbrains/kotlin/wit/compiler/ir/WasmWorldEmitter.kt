package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan

internal class WasmWorldEmitter(
    pluginContext: IrPluginContext,
    symbols: WasmRuntimeSymbols,
) {
    private val context = WasmIrBuilderContext(pluginContext, symbols)
    private val classBuilder = WasmWorldClassBuilder(context)
    private val bindingsEmitter = WasmWorldBindingsEmitter(context)
    private val companionEmitter = WasmWorldCompanionEmitter(context)

    fun emit(file: IrFile, pkg: PackagePlan, world: WorldPlan): IrClass {
        val worldClass = classBuilder.build(file, pkg, world)
        bindingsEmitter.emit(worldClass, pkg, world)
        companionEmitter.emit(worldClass, pkg, world)
        return worldClass
    }
}
