@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.wit.compiler.schema

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.wit.compiler.driver.WitSchemaConfig
import org.jetbrains.kotlin.wit.codegen.core.WitAstSchemaLoader
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimePackage as CorePackage
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeInterface as CoreInterface
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeWorld as CoreWorld
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeFunction as CoreFunction
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeBinding as CoreBinding
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeConstructor as CoreConstructor
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeType as CoreType
import org.jetbrains.kotlin.wit.codegen.core.schema.WitSchemaSource as CoreSource
import org.jetbrains.kotlin.wit.codegen.core.schema.Stability as CoreStability
import org.jetbrains.kotlin.wit.codegen.core.schema.FunctionKind as CoreFuncKind
import org.jetbrains.kotlin.wit.codegen.core.schema.BindingKind as CoreBindKind

class WitSchemaIndex(
    val witPackages: List<WitPackage>,
    val jsonSchemas: List<JsonSchema>,
    val enabledFeatures: Set<String>,
    val packageMetadata: List<WitPackageMetadata>,
    val runtimeSchema: WitRuntimeSchema,
) {
    data class WitPackage(
        val entry: Path,
        val metadataJson: String,
        val includeRoots: List<Path>,
        val sourceFiles: List<Path>,
    )
    data class JsonSchema(val path: Path, val contents: String)

    companion object {
        fun load(
            config: WitSchemaConfig,
            messageCollector: MessageCollector,
        ): WitSchemaIndex? {
            messageCollector.report(
                CompilerMessageSeverity.INFO,
                "WIT: loadSchema rootPaths=${config.rootPaths.size} includePaths=${config.includePaths.size} jsonSchemas=${config.jsonSchemas.size} features=${config.enabledFeatures}",
            )
            if (config.rootPaths.isEmpty() && config.jsonSchemas.isEmpty()) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "WIT compiler plugin enabled but no --root or --json options were provided; skipping.",
                )
                return null
            }

            val featureSet = config.enabledFeatures

            // Load WIT roots via codegen-core (AST-based path)
            val coreSchema = if (config.rootPaths.isNotEmpty()) {
                val loader = WitAstSchemaLoader()
                messageCollector.report(CompilerMessageSeverity.INFO, "WIT: invoking AST loader with ${config.rootPaths.size} roots")
                println("[WIT] AST load roots=${config.rootPaths}")
                loader.load(
                    WitAstSchemaLoader.Options(
                        rootPaths = config.rootPaths,
                        includePaths = config.includePaths,
                        features = featureSet,
                        jsonSchemas = emptyList(),
                    ),
                )
            } else null

            val corePackages: List<CorePackage> = coreSchema?.packages.orEmpty()
            messageCollector.report(CompilerMessageSeverity.INFO, "WIT: AST loader produced ${corePackages.size} package(s)")
            println("[WIT] AST packages=${corePackages.size}")

            // Load JSON (debug) paths using existing builder for parity testing
            val jsonInputs: List<WitRuntimeSchemaBuilder.Input> = if (config.jsonSchemas.isNotEmpty()) {
                if (!config.allowJsonSchemas) {
                    messageCollector.report(
                        CompilerMessageSeverity.WARNING,
                        "Ignoring ${config.jsonSchemas.size} WIT JSON schema(s) because debug mode is disabled. JSON inputs are intended for wasm-tools parity tests; enable --debug to ingest them.",
                    )
                    emptyList()
                } else {
                    config.jsonSchemas.mapNotNull { schemaPath ->
                        try {
                            val contents = schemaPath.readText()
                            WitRuntimeSchemaBuilder.Input(
                                source = WitSchemaSource.Json(schemaPath),
                                origin = WitRuntimeSchemaBuilder.SchemaOrigin.JSON,
                                metadataJson = contents,
                                sourceFiles = emptyList(),
                            )
                        } catch (ioe: IOException) {
                            messageCollector.report(
                                CompilerMessageSeverity.ERROR,
                                "Failed to read WIT JSON schema at $schemaPath: ${ioe.message}",
                            )
                            null
                        }
                    }
                }
            } else emptyList()

            if (corePackages.isEmpty() && jsonInputs.isEmpty()) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "WIT compiler plugin did not load any schemas.",
                )
                return null
            }

            val metadataByName = linkedMapOf<String, WitPackageMetadata>()
            // We don't currently propagate metadata from the AST path; keep empty for now.
            // JSON inputs still contribute metadata in debug mode for parity.
            jsonInputs.forEach { input ->
                WitSchemaMetadataParser.parsePackages(input.metadataJson, featureSet).forEach { metadata ->
                    metadataByName.putIfAbsent(metadata.name, metadata)
                }
            }

            val pluginPackagesFromCore: List<WitRuntimePackage> = corePackages.map { pkg -> pkg.toPlugin() }
            val listedPackagesFromCore: List<WitPackage> = corePackages.map { pkg ->
                val entryPath = when (val s = pkg.source) {
                    is CoreSource.Directory -> s.path
                    is CoreSource.File -> s.path
                    is CoreSource.Json -> s.path
                }
                val includeRoots = when (val s = pkg.source) {
                    is CoreSource.Directory -> s.includeRoots
                    else -> emptyList()
                }
                WitPackage(
                    entry = entryPath,
                    metadataJson = "",
                    includeRoots = includeRoots,
                    sourceFiles = pkg.sourceFiles,
                )
            }
            val pluginPackagesFromJson: List<WitRuntimePackage> = if (jsonInputs.isNotEmpty()) {
                WitRuntimeSchemaBuilder.build(jsonInputs, metadataByName, featureSet, messageCollector)
            } else emptyList()

            val runtimeSchema = WitRuntimeSchema(
                packages = pluginPackagesFromCore + pluginPackagesFromJson,
                features = featureSet,
                sources = buildList {
                    coreSchema?.sources?.forEach { src ->
                        when (src) {
                            is CoreSource.Directory -> add(WitSchemaSource.Directory(src.path, src.includeRoots))
                            is CoreSource.File -> add(WitSchemaSource.File(src.path))
                            is CoreSource.Json -> add(WitSchemaSource.Json(src.path))
                        }
                    }
                    jsonInputs.forEach { add(it.source) }
                }.distinct(),
            )

            val listedPackagesFromJson: List<WitPackage> = jsonInputs.filter { it.source is WitSchemaSource.Json }.map {
                val json = it.source as WitSchemaSource.Json
                WitPackage(entry = json.path, metadataJson = it.metadataJson, includeRoots = emptyList(), sourceFiles = emptyList())
            }

            return WitSchemaIndex(
                witPackages = listedPackagesFromCore + listedPackagesFromJson,
                jsonSchemas = jsonInputs.filter { it.source is WitSchemaSource.Json }.map { WitSchemaIndex.JsonSchema((it.source as WitSchemaSource.Json).path, it.metadataJson) },
                enabledFeatures = featureSet,
                packageMetadata = metadataByName.values.toList(),
                runtimeSchema = runtimeSchema,
            )
        }
        // Legacy helper methods removed: JSON emission path is handled above for debug parity only.
    }
}

private fun CorePackage.toPlugin(): WitRuntimePackage =
    WitRuntimePackage(
        id = this.id,
        source = when (val s = this.source) {
            is CoreSource.Directory -> WitSchemaSource.Directory(s.path, s.includeRoots)
            is CoreSource.File -> WitSchemaSource.File(s.path)
            is CoreSource.Json -> WitSchemaSource.Json(s.path)
        },
        includes = this.includes,
        sourceFiles = this.sourceFiles,
        metadata = null,
        interfaces = this.interfaces.map { iface ->
            WitRuntimeInterface(
                name = iface.name,
                stability = iface.stability.toPluginStability(),
                resources = iface.resources.map { res ->
                    WitRuntimeResource(
                        name = res.name,
                        stability = res.stability.toPluginStability(),
                        ownHandleType = res.ownHandleType,
                        borrowHandleType = res.borrowHandleType,
                    )
                },
                functions = iface.functions.map { fn: CoreFunction ->
                    WitRuntimeFunction(
                        name = fn.name,
                        parameters = fn.parameters.map { p: CoreType -> WitRuntimeType(p.label, p.typeRef) },
                        results = fn.results.map { r: CoreType -> WitRuntimeType(r.label, r.typeRef) },
                        kind = when (fn.kind) {
                            CoreFuncKind.FUNCTION -> FunctionKind.FUNCTION
                            CoreFuncKind.METHOD -> FunctionKind.METHOD
                            CoreFuncKind.STATIC -> FunctionKind.STATIC
                            CoreFuncKind.LIFT -> FunctionKind.LIFT
                            CoreFuncKind.LOWER -> FunctionKind.LOWER
                            CoreFuncKind.CONSTRUCTOR -> FunctionKind.CONSTRUCTOR
                        },
                        isAsync = fn.isAsync,
                        usesStreams = fn.usesStreams,
                    )
                },
            )
        },
        worlds = this.worlds.map { w ->
            WitRuntimeWorld(
                name = w.name,
                stability = w.stability.toPluginStability(),
                imports = w.imports.map { it.toPlugin() },
                exports = w.exports.map { it.toPlugin() },
                constructors = w.constructors.map { c ->
                    WitRuntimeConstructor(
                        bindingName = c.bindingName,
                        signature = WitRuntimeFunction(
                            name = c.signature.name,
                            parameters = c.signature.parameters.map { p -> WitRuntimeType(p.label, p.typeRef) },
                            results = c.signature.results.map { r -> WitRuntimeType(r.label, r.typeRef) },
                            kind = when (c.signature.kind) {
                                CoreFuncKind.FUNCTION -> FunctionKind.FUNCTION
                                CoreFuncKind.METHOD -> FunctionKind.METHOD
                                CoreFuncKind.STATIC -> FunctionKind.STATIC
                                CoreFuncKind.LIFT -> FunctionKind.LIFT
                                CoreFuncKind.LOWER -> FunctionKind.LOWER
                                CoreFuncKind.CONSTRUCTOR -> FunctionKind.CONSTRUCTOR
                            },
                            isAsync = c.signature.isAsync,
                            usesStreams = c.signature.usesStreams,
                        ),
                    )
                },
            )
        },
    )

private fun CoreBinding.toPlugin(): WitRuntimeBinding =
    WitRuntimeBinding(
        name = this.name,
        target = this.target,
        bindingKind = when (this.bindingKind) {
            CoreBindKind.FUNCTION -> BindingKind.FUNCTION
            CoreBindKind.INTERFACE -> BindingKind.INTERFACE
            CoreBindKind.RESOURCE -> BindingKind.RESOURCE
        },
        signature = this.signature?.let { fn ->
            WitRuntimeFunction(
                name = fn.name,
                parameters = fn.parameters.map { p -> WitRuntimeType(p.label, p.typeRef) },
                results = fn.results.map { r -> WitRuntimeType(r.label, r.typeRef) },
                kind = when (fn.kind) {
                    CoreFuncKind.FUNCTION -> FunctionKind.FUNCTION
                    CoreFuncKind.METHOD -> FunctionKind.METHOD
                    CoreFuncKind.STATIC -> FunctionKind.STATIC
                    CoreFuncKind.LIFT -> FunctionKind.LIFT
                    CoreFuncKind.LOWER -> FunctionKind.LOWER
                    CoreFuncKind.CONSTRUCTOR -> FunctionKind.CONSTRUCTOR
                },
                isAsync = fn.isAsync,
                usesStreams = fn.usesStreams,
            )
        },
    )

private fun CoreStability?.toPluginStability(): Stability? = when (this) {
    null -> null
    CoreStability.Stable -> Stability.Stable
    is CoreStability.Unstable -> Stability.Unstable(this.feature)
}
