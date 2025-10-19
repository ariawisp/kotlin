package org.jetbrains.kotlin.wit.codegen.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.jetbrains.kotlin.wit.codegen.core.schema.BindingKind
import org.jetbrains.kotlin.wit.codegen.core.schema.FunctionKind
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimePackage
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeSchema
import org.jetbrains.kotlin.wit.codegen.core.schema.WitSchemaSource
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeFunction
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.codegen.core.schema.WitRuntimeType
import org.jetbrains.kotlin.wit.codegen.core.schema.Stability
import org.jetbrains.kotlin.wit.resolve.Wit
import org.jetbrains.kotlin.wit.model.*

class WitAstSchemaLoader(
    private val logger: Logger = Logger.NONE,
) {
    data class Options(
        val rootPaths: List<Path>,
        val includePaths: List<Path> = emptyList(),
        val features: Set<String> = emptySet(),
        val jsonSchemas: List<Path> = emptyList(), // for parity tests only
    )

    fun load(options: Options): WitRuntimeSchema? {
        if (options.rootPaths.isEmpty()) {
            logger.warn("No WIT roots provided; skipping.")
            return null
        }
        val featureSet = options.features
        if (options.rootPaths.isEmpty()) {
            logger.warn("No WIT roots provided; skipping.")
            return null
        }

        val collectedPackages = mutableListOf<WitRuntimePackage>()
        val collectedSources = mutableListOf<WitSchemaSource>()

        val distinctRoots = options.rootPaths.distinct()
        for (root in distinctRoots) {
            val includeCandidates = buildList<Path> {
                addAll(options.includePaths)
                distinctRoots.filter { it != root }.forEach { add(it) }
            }
            val result = loadSingleRoot(root, includeCandidates, featureSet) ?: continue
            collectedPackages += result.packages
            collectedSources += result.sources
        }

        if (collectedPackages.isEmpty()) return null

        val dedupedPackages = collectedPackages.distinctBy { it.id }
        val dedupedSources = collectedSources.distinctBy { it.path to when (it) {
            is WitSchemaSource.Directory -> it.includeRoots
            else -> emptyList()
        } }

        return WitRuntimeSchema(
            packages = dedupedPackages,
            features = featureSet,
            sources = dedupedSources,
        )
    }

    private fun collectWitFiles(directory: Path, relativeTo: Path = directory): List<WitFile> {
        if (!Files.exists(directory)) return emptyList()
        Files.walk(directory).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".wit") }
                .sorted(compareBy { relativeTo.relativize(it).toString() })
                .map { candidate ->
                    val normalized = candidate.normalize().toAbsolutePath()
                    WitFile(
                        path = normalized,
                        contents = candidate.readText(),
                        displayPath = relativeTo.relativize(candidate).toString().replace('\\', '/'),
                        dataPath = normalized.toString(),
                    )
                }
                .collect(Collectors.toList())
        }
    }

    data class Logger(
        val warn: (String) -> Unit,
        val error: (String) -> Unit,
    ) {
        companion object {
            val NONE = Logger(warn = {}, error = {})
        }
    }

    private data class WitFile(
        val path: Path,
        val contents: String,
        val displayPath: String = path.fileName?.toString().orEmpty(),
        val dataPath: String = path.toString(),
    )

    private fun loadSingleRoot(root: Path, includePaths: List<Path>, features: Set<String>): SingleRootResult? {
        val roots = listOf(
            if (root.isDirectory()) Wit.SourceRoot.Directory(root) else Wit.SourceRoot.File(root)
        )
        val includes = includePaths.map { path ->
            if (path.isDirectory()) Wit.SourceRoot.Directory(path) else Wit.SourceRoot.File(path)
        }
        val schema = Wit.load(Wit.Options(roots = roots, includes = includes, features = features)) ?: return null

        val source = if (root.isDirectory()) {
            WitSchemaSource.Directory(root, includePaths)
        } else {
            WitSchemaSource.File(root)
        }
        val sourceFiles = if (root.isDirectory()) {
            collectWitFiles(root).map { it.path }
        } else {
            listOf(root)
        }

        val runtimePackages = schema.packages.map { pkg ->
            val importCount = pkg.worlds.sumOf { it.imports.size }
            val exportCount = pkg.worlds.sumOf { it.exports.size }
            val constructorCount = pkg.worlds.sumOf { it.constructors.size }
            val worldNames = pkg.worlds.joinToString(",") { it.name }
            println(
                "[WIT] package ${pkg.id.namespace}:${pkg.id.name} " +
                    "interfaces=${pkg.interfaces.size} resources=${pkg.interfaces.sumOf { it.resources.size }} " +
                    "worlds=${pkg.worlds.size} imports=$importCount exports=$exportCount constructors=$constructorCount names=[$worldNames]"
            )

            val pkgId = buildString {
                append(pkg.id.namespace)
                append(":")
                append(pkg.id.name)
            }
            WitRuntimePackage(
                id = pkgId,
                source = source,
                includes = includePaths,
                sourceFiles = sourceFiles,
                metadata = null,
                interfaces = pkg.interfaces.map { iface ->
                    WitRuntimeInterface(
                        name = iface.name,
                        stability = iface.stability?.toRuntime(),
                        resources = iface.resources.map { res ->
                            WitRuntimeResource(
                                name = res.name,
                                stability = res.stability?.toRuntime(),
                                ownHandleType = res.ownHandle?.let { "own:${it.resource}" },
                                borrowHandleType = res.borrowHandle?.let { "borrow:${it.resource}" },
                            )
                        },
                        functions = iface.functions.map { fn -> fn.toRuntimeFunction() },
                    )
                },
                worlds = pkg.worlds.map { w ->
                    WitRuntimeWorld(
                        name = w.name,
                        stability = w.stability?.toRuntime(),
                        imports = w.imports.map { it.toRuntimeBinding() },
                        exports = w.exports.map { it.toRuntimeBinding() },
                        constructors = w.constructors.map { c ->
                            WitRuntimeConstructor(c.bindingName, c.signature.toRuntimeFunction())
                        },
                    )
                },
            )
        }

        return SingleRootResult(
            packages = runtimePackages,
            sources = listOf(source),
        )
    }

    private data class SingleRootResult(
        val packages: List<WitRuntimePackage>,
        val sources: List<WitSchemaSource>,
    )

    // Mapping helpers
    private fun org.jetbrains.kotlin.wit.model.Stability?.toRuntime(): Stability? = when (this) {
        null -> null
        is org.jetbrains.kotlin.wit.model.Stability.Stable -> Stability.Stable
        is org.jetbrains.kotlin.wit.model.Stability.Unstable -> Stability.Unstable(this.feature)
    }

    private fun WitFunction.toRuntimeFunction(): WitRuntimeFunction =
        WitRuntimeFunction(
            name = name,
            parameters = parameters.map { p -> WitRuntimeType(p.label, p.type.asString()) },
            results = results.map { t -> WitRuntimeType(null, t.asString()) },
            kind = when (kind) {
                org.jetbrains.kotlin.wit.model.FuncKind.FUNCTION -> FunctionKind.FUNCTION
                org.jetbrains.kotlin.wit.model.FuncKind.METHOD -> FunctionKind.METHOD
                org.jetbrains.kotlin.wit.model.FuncKind.STATIC -> FunctionKind.STATIC
                org.jetbrains.kotlin.wit.model.FuncKind.LIFT -> FunctionKind.LIFT
                org.jetbrains.kotlin.wit.model.FuncKind.LOWER -> FunctionKind.LOWER
                org.jetbrains.kotlin.wit.model.FuncKind.CONSTRUCTOR -> FunctionKind.CONSTRUCTOR
            },
            isAsync = isAsync,
            usesStreams = usesStreams,
        )

    private fun WitBinding.toRuntimeBinding(): WitRuntimeBinding =
        WitRuntimeBinding(
            name = name,
            target = when (val t = target) {
                is BindingTarget.Interface -> t.name
                is BindingTarget.Resource -> t.name
                is BindingTarget.Function -> t.name
            },
            bindingKind = when (kind) {
                org.jetbrains.kotlin.wit.model.BindingKind.FUNCTION -> BindingKind.FUNCTION
                org.jetbrains.kotlin.wit.model.BindingKind.INTERFACE -> BindingKind.INTERFACE
                org.jetbrains.kotlin.wit.model.BindingKind.RESOURCE -> BindingKind.RESOURCE
            },
            signature = signature?.toRuntimeFunction(),
        )

    private fun TypeRef.asString(): String = when (this) {
        is TypeRef.Primitive -> name
        is TypeRef.Identifier -> name
        is TypeRef.Tuple -> elements.joinToString(prefix = "[", postfix = "]") { it.asString() }
        is TypeRef.ListT -> "list<${element.asString()}>"
        is TypeRef.FixedList -> "list<${element.asString()},$size>"
        is TypeRef.Option -> "option<${element.asString()}>"
        is TypeRef.Result -> {
            val okStr = ok?.asString()
            val errStr = err?.asString()
            when {
                okStr == null && errStr == null -> "result"
                errStr == null -> "result<${okStr}>"
                else -> "result<${okStr ?: "_"},${errStr}>"
            }
        }
        is TypeRef.Future -> {
            val out = this.output
            if (out == null) "future" else "future<${out.asString()}>"
        }
        is TypeRef.Stream -> {
            val s: TypeRef.Stream = this
            buildString {
                append("stream")
                val el = s.element
                val en = s.end
                if (el != null || en != null) {
                    append('<')
                    append(el?.asString() ?: "_")
                    if (en != null) {
                        append(',')
                        append(en.asString())
                    }
                    append('>')
                }
            }
        }
        is TypeRef.ErrorContext -> "error-context"
        is TypeRef.Record -> "record{" + fields.joinToString { f ->
            val ft = f.type
            f.name + ":" + ft.asString()
        } + "}"
        is TypeRef.Flags -> "flags{${names.joinToString()}}"
        is TypeRef.EnumT -> "enum{${names.joinToString()}}"
        is TypeRef.Variant -> "variant{" + cases.joinToString { c ->
            val ct = c.type
            if (ct != null) c.name + ":" + ct.asString() else c.name
        } + "}"
        is TypeRef.Handle -> when (ownership) {
            TypeRef.Handle.Ownership.OWN -> "own:$resource"
            TypeRef.Handle.Ownership.BORROW -> "borrow:$resource"
        }
    }
}
