@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.wit.codegen.core.plan.WitCodegenPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan
import org.jetbrains.kotlin.wit.compiler.ir.sanitizeIdentifier
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

internal class WasmIrGenerator(
    private val pluginContext: IrPluginContext,
) {
    data class Config(val packagePrefix: FqName = FqName("wit.generated"))

    private val symbols = WasmRuntimeSymbols(pluginContext)
    private val emitter = WasmWorldEmitter(pluginContext, symbols)

    fun generate(moduleFragment: IrModuleFragment, plan: WitCodegenPlan, config: Config = Config()) {
        val processedWorlds = mutableSetOf<Pair<FqName, String>>()
        plan.packages.forEach { pkg ->
            val seenWorlds = mutableSetOf<String>()
            pkg.worlds.forEach { world ->
                val worldKey = sanitizeIdentifier(world.name)
                if (!seenWorlds.add(worldKey)) {
                    return@forEach
                }
                val packageFqName = buildPackageFqName(config.packagePrefix, pkg.id)
                if (!processedWorlds.add(packageFqName to worldKey)) {
                    return@forEach
                }
                moduleFragment.files.forEach { file ->
                    file.declarations
                        .filterIsInstance<IrClass>()
                        .filter { it.fqNameWhenAvailable?.asString() == "${packageFqName.asString()}.$worldKey" }
                        .forEach { existing ->
                            file.declarations.remove(existing)
                        }
                }
                val irFile = obtainFile(moduleFragment, packageFqName, pkg, world)
                irFile.declarations
                    .filterIsInstance<IrClass>()
                    .filter { it.name.asString() == worldKey }
                    .forEach { existing ->
                        irFile.declarations.remove(existing)
                    }
                if (irFile.declarations.filterIsInstance<IrClass>().none { it.name.asString() == worldKey }) {
                    val worldClass = emitter.emit(irFile, pkg, world)
                    irFile.declarations += worldClass
                }
            }
        }
    }

    private fun obtainFile(
        module: IrModuleFragment,
        packageFqName: FqName,
        pkg: org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan,
        world: WorldPlan,
    ): IrFile {
        return module.files.firstOrNull { it.packageFqName == packageFqName }
            ?: run {
                val entry = org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl(
                    buildString {
                        append(packageFqName.asString().replace('.', '/'))
                        append('/')
                        append(sanitizeIdentifier(world.name))
                        append(".kt")
                    },
                )
                val newFile = IrFileImpl(entry, org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl(), packageFqName, module)
                module.files += newFile
                newFile
            }
    }

    private fun buildPackageFqName(prefix: FqName, packageId: String): FqName {
        val withoutVersion = packageId.substringBefore('@')
        val pieces = withoutVersion.replace(':', '.').split('.').filter { it.isNotBlank() }
        val segments = prefix.pathSegments().map { it.asString() } + pieces.map(::sanitizeIdentifier)
        return FqName(segments.joinToString("."))
    }
}
