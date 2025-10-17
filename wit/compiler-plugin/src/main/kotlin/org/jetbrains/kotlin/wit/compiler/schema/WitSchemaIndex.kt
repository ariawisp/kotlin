@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.wit.compiler.schema

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.wit.ast.syntax.AstJsonEmitter
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions

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
            options: WitPluginOptions,
            messageCollector: MessageCollector,
        ): WitSchemaIndex? {
            if (options.rootPaths.isEmpty() && options.jsonSchemas.isEmpty()) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "WIT compiler plugin enabled but no --root or --json options were provided; skipping.",
                )
                return null
            }

            val featureSet = options.features

            val packages = linkedMapOf<Path, WitPackage>()
            for (root in options.rootPaths) {
                val payload = emitMetadata(root, options.includePaths, featureSet, messageCollector)
                if (payload != null) {
                    packages.putIfAbsent(
                        root,
                        WitPackage(
                            entry = root,
                            metadataJson = payload.metadata,
                            includeRoots = payload.includeRoots,
                            sourceFiles = payload.sourceFiles,
                        ),
                    )
                }
            }

            val jsonSchemas = if (options.jsonSchemas.isNotEmpty() && !options.debug) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Ignoring ${options.jsonSchemas.size} WIT JSON schema(s) because debug mode is disabled. " +
                        "JSON inputs are intended for wasm-tools parity tests; enable --debug to ingest them.",
                )
                emptyList()
            } else {
                options.jsonSchemas.mapNotNull { schemaPath ->
                    try {
                        JsonSchema(schemaPath, schemaPath.readText())
                    } catch (ioe: IOException) {
                        messageCollector.report(
                            CompilerMessageSeverity.ERROR,
                            "Failed to read WIT JSON schema at $schemaPath: ${ioe.message}",
                        )
                        null
                    }
                }
            }

            if (packages.isEmpty() && jsonSchemas.isEmpty()) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "WIT compiler plugin did not load any schemas.",
                )
                return null
            }

            val metadataByName = linkedMapOf<String, WitPackageMetadata>()
            packages.values.forEach { pkg ->
                WitSchemaMetadataParser.parsePackages(pkg.metadataJson, featureSet).forEach { metadata ->
                    metadataByName.putIfAbsent(metadata.name, metadata)
                }
            }
            jsonSchemas.forEach { schema ->
                WitSchemaMetadataParser.parsePackages(schema.contents, featureSet).forEach { metadata ->
                    metadataByName.putIfAbsent(metadata.name, metadata)
                }
            }

            val runtimeInputs = buildList {
                packages.values.forEach { pkg ->
                    val source = if (Files.isDirectory(pkg.entry)) {
                        WitSchemaSource.Directory(pkg.entry, pkg.includeRoots)
                    } else {
                        WitSchemaSource.File(pkg.entry)
                    }
                    val origin = when (source) {
                        is WitSchemaSource.Directory -> WitRuntimeSchemaBuilder.SchemaOrigin.WIT_DIRECTORY
                        is WitSchemaSource.File -> WitRuntimeSchemaBuilder.SchemaOrigin.WIT_FILE
                        is WitSchemaSource.Json -> WitRuntimeSchemaBuilder.SchemaOrigin.JSON
                    }
                    add(
                        WitRuntimeSchemaBuilder.Input(
                            source = source,
                            origin = origin,
                            metadataJson = pkg.metadataJson,
                            sourceFiles = pkg.sourceFiles,
                        ),
                    )
                }
                jsonSchemas.forEach { schema ->
                    add(
                        WitRuntimeSchemaBuilder.Input(
                            source = WitSchemaSource.Json(schema.path),
                            origin = WitRuntimeSchemaBuilder.SchemaOrigin.JSON,
                            metadataJson = schema.contents,
                            sourceFiles = emptyList(),
                        ),
                    )
                }
            }

            val runtimePackages = WitRuntimeSchemaBuilder.build(
                runtimeInputs,
                metadataByName,
                options.features,
                messageCollector,
            )

            val runtimeSchema = WitRuntimeSchema(
                packages = runtimePackages,
                features = options.features,
                sources = runtimeInputs.map { it.source }.distinct(),
            )

            return WitSchemaIndex(
                witPackages = packages.values.toList(),
                jsonSchemas = jsonSchemas,
                enabledFeatures = options.features,
                packageMetadata = metadataByName.values.toList(),
                runtimeSchema = runtimeSchema,
            )
        }

        private val cache = ConcurrentHashMap<CacheKey, CachedMetadata>()

        private fun emitMetadata(
            entry: Path,
            includePaths: List<Path>,
            features: Set<String>,
            messageCollector: MessageCollector,
        ): MetadataPayload? {
            val normalizedRoot = entry.normalize().toAbsolutePath()
            val normalizedIncludes = includePaths.map { it.normalize().toAbsolutePath() }
            val key = CacheKey(normalizedRoot, normalizedIncludes, features.toSortedSet())
            val cached = cache[key]
            if (cached?.isFresh() == true) {
                return cached.payload
            }

            val payload = try {
                when {
                    normalizedRoot.isDirectory() -> emitDirectory(normalizedRoot, includePaths, features, messageCollector)
                    normalizedRoot.isRegularFile() -> emitFile(normalizedRoot, features)
                    else -> {
                        messageCollector.report(
                            CompilerMessageSeverity.WARNING,
                            "WIT root $entry does not exist; skipping.",
                        )
                        null
                    }
                }
            } catch (t: Throwable) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Exception while loading WIT schema at $entry: ${t.message}",
                )
                null
            }
            if (payload == null && Files.exists(normalizedRoot)) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "WIT schema generation produced no metadata for $entry. Check parser logs for details.",
                )
            }

            if (payload != null) {
                cache[key] = CachedMetadata(payload)
                return payload
            }
            return null
        }

        private fun emitDirectory(
            root: Path,
            includePaths: List<Path>,
            features: Set<String>,
            messageCollector: MessageCollector,
        ): MetadataPayload? {
            val primaryEntries = collectWitFiles(root)
            if (primaryEntries.isEmpty()) {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "No '.wit' files discovered under $root; skipping.",
                )
                return null
            }

            val resolvedIncludes = resolveIncludeDirectories(root, includePaths)
            val includeEntries = resolvedIncludes.flatMap { collectWitFiles(it, relativeTo = root) }

            val allEntries = (primaryEntries + includeEntries)
                .distinctBy { it.displayPath }

            val rootIndex = allEntries.indexOfFirst { entry ->
                entry.displayPath == "root.wit"
            }.let { index -> if (index >= 0) index else 0 }

            val metadata = AstJsonEmitter.emitFiles(
                allEntries.map { it.dataPath to it.contents },
                rootIndex,
                features = features,
            ) ?: run {
                messageCollector.report(
                    CompilerMessageSeverity.WARNING,
                    "Failed to emit WIT metadata for $root. Files considered: ${allEntries.map { it.displayPath }}",
                )
                return null
            }

            val fingerprints = mutableMapOf<Path, Long>()
            if (Files.exists(root)) {
                fingerprints[root] = Files.getLastModifiedTime(root).toMillis()
            }
            resolvedIncludes.forEach { includeDir ->
                if (Files.exists(includeDir)) {
                    fingerprints[includeDir] = Files.getLastModifiedTime(includeDir).toMillis()
                }
            }
            allEntries.forEach { entry ->
                fingerprints[entry.path] = entry.lastModified
            }

            val sourceFiles = allEntries.map { it.path }

            return MetadataPayload(metadata, fingerprints, resolvedIncludes, sourceFiles)
        }

        private fun emitFile(path: Path, features: Set<String>): MetadataPayload? {
            val contents = path.readText()
            val metadata = AstJsonEmitter.emit(path.toString(), contents, features = features) ?: return null
            val fingerprints = mapOf(path to Files.getLastModifiedTime(path).toMillis())
            val sourceFiles = listOf(path)
            return MetadataPayload(metadata, fingerprints, includeRoots = emptyList(), sourceFiles = sourceFiles)
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
                            lastModified = Files.getLastModifiedTime(normalized).toMillis(),
                            contents = candidate.readText(),
                            displayPath = relativeTo.relativize(candidate).toString().replace('\\', '/'),
                            dataPath = normalized.toString(),
                        )
                    }
                    .collect(Collectors.toList())
            }
        }

        private fun resolveIncludeDirectories(root: Path, includes: List<Path>): List<Path> {
            if (includes.isEmpty()) return emptyList()
            val resolved = mutableListOf<Path>()
            for (candidate in includes) {
                val paths = listOf(candidate, root.resolve(candidate))
                for (path in paths) {
                    if (Files.exists(path) && path.isDirectory()) {
                        resolved.add(path.normalize().toAbsolutePath())
                        break
                    }
                }
            }
            return resolved.distinct()
        }

        private data class CacheKey(val root: Path, val includes: List<Path>, val features: Set<String>)

        private data class CachedMetadata(val payload: MetadataPayload) {
            fun isFresh(): Boolean {
                for ((path, timestamp) in payload.fingerprints) {
                    if (!Files.exists(path)) return false
                    val current = Files.getLastModifiedTime(path).toMillis()
                    if (current != timestamp) return false
                }
                return true
            }
        }

        private data class WitFile(
            val path: Path,
            val lastModified: Long,
            val contents: String,
            val displayPath: String = path.fileName?.toString().orEmpty(),
            val dataPath: String = path.toString(),
        )

        private data class MetadataPayload(
            val metadata: String,
            val fingerprints: Map<Path, Long>,
            val includeRoots: List<Path>,
            val sourceFiles: List<Path>,
        )
    }
}
