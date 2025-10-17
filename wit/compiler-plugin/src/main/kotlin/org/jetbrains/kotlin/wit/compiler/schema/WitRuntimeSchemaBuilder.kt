package org.jetbrains.kotlin.wit.compiler.schema

import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

internal object WitRuntimeSchemaBuilder {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun build(
        inputs: List<Input>,
        metadataByName: Map<String, WitPackageMetadata>,
        _enabledFeatures: Set<String>,
        messageCollector: MessageCollector,
    ): List<WitRuntimePackage> {
        val packagesById = linkedMapOf<String, PackageCandidate>()
        for (input in inputs) {
            val element = runCatching { json.parseToJsonElement(input.metadataJson) }.getOrElse { failure ->
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Failed to parse WIT metadata from ${input.source.path}: ${failure.message}",
                )
                continue
            }
            val root = element.jsonObject
            val context = RuntimeContext(root)
            root["packages"]?.jsonArray?.forEachIndexed { pkgIndex, pkgElement ->
                val pkgObject = pkgElement.jsonObject
                val packageName = pkgObject["name"]?.jsonPrimitive?.let { primitive ->
                    if (primitive is JsonNull) null else primitive.content
                }
                if (packageName.isNullOrBlank()) return@forEachIndexed

                val runtimePackage = buildPackage(
                    packageName = packageName,
                    packageIndex = pkgIndex,
                    pkgObject = pkgObject,
                    context = context,
                    input = input,
                    metadata = metadataByName[packageName],
                ) ?: return@forEachIndexed

                val existing = packagesById[packageName]
                if (existing == null || existing.origin.priority < input.origin.priority) {
                    packagesById[packageName] = PackageCandidate(runtimePackage, input.origin)
                } else if (existing.origin.priority == input.origin.priority) {
                    // Prefer the first package with a given priority to keep deterministic ordering.
                } else {
                    // Existing package has higher priority; keep it.
                }
            }
        }
        return packagesById.values.map { it.packageModel }
    }

    private fun buildPackage(
        packageName: String,
        packageIndex: Int,
        pkgObject: JsonObject,
        context: RuntimeContext,
        input: Input,
        metadata: WitPackageMetadata?,
    ): WitRuntimePackage? {
        val interfaceIndices = pkgObject["interfaces"]
            ?.jsonObject
            ?.values
            ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            .orEmpty()

        val interfaceMetadataByName = metadata?.interfaces?.associateBy { it.name }.orEmpty()
        val worldMetadataByName = metadata?.worlds?.associateBy { it.name }.orEmpty()

        val interfaces = interfaceIndices.mapNotNull { idx ->
            val interfaceObject = context.interfaces.getOrNull(idx) ?: return@mapNotNull null
            buildInterface(
                pkgIndex = packageIndex,
                interfaceIndex = idx,
                interfaceObject = interfaceObject,
                context = context,
                metadataByName = interfaceMetadataByName,
            )
        }

        val interfaceNamesByIndex = interfaceIndices.associateWith { idx ->
            context.interfaces.getOrNull(idx)?.getString("name") ?: "interface#$idx"
        }

        val worlds = context.worlds.mapIndexedNotNull { idx, worldObject ->
            val owningPackageIndex = worldObject["package"]?.jsonPrimitive?.content?.toIntOrNull()
            if (owningPackageIndex != packageIndex) return@mapIndexedNotNull null
            buildWorld(
                worldObject = worldObject,
                context = context,
                interfaceNamesByIndex = interfaceNamesByIndex,
                metadataByName = worldMetadataByName,
            )
        }

        val includes = when (val source = input.source) {
            is WitSchemaSource.Directory -> source.includeRoots
            else -> emptyList()
        }

        return WitRuntimePackage(
            id = packageName,
            source = input.source,
            includes = includes,
            sourceFiles = input.sourceFiles,
            metadata = metadata,
            interfaces = interfaces,
            worlds = worlds,
        )
    }

    private fun buildInterface(
        pkgIndex: Int,
        interfaceIndex: Int,
        interfaceObject: JsonObject,
        context: RuntimeContext,
        metadataByName: Map<String, WitInterfaceMetadata>,
    ): WitRuntimeInterface? {
        val owningPackage = interfaceObject["package"]?.jsonPrimitive?.content?.toIntOrNull()
        if (owningPackage != null && owningPackage != pkgIndex) return null
        val name = interfaceObject.getString("name") ?: return null
        if (metadataByName.isNotEmpty() && name !in metadataByName) return null
        val stability = metadataByName[name]?.stability
        val functions = interfaceObject["functions"]?.jsonObject?.values
            ?.mapNotNull { buildFunction(it.jsonObject, context) }
            .orEmpty()
        val resources = buildResources(interfaceObject, context, name)
        return WitRuntimeInterface(
            name = name,
            stability = stability,
            resources = resources,
            functions = functions,
        )
    }

    private fun buildResources(
        interfaceObject: JsonObject,
        context: RuntimeContext,
        ownerInterface: String,
    ): List<WitRuntimeResource> {
        val typeBindings = interfaceObject["types"]?.jsonObject ?: JsonObject(emptyMap())
        if (typeBindings.isEmpty()) return emptyList()
        return typeBindings.entries.mapNotNull { (_, value) ->
            val index = value.jsonPrimitive.content.toIntOrNull() ?: return@mapNotNull null
            val typeObject = context.types.getOrNull(index) ?: return@mapNotNull null
            if (typeObject.getString("kind") != "resource") return@mapNotNull null
            val typeName = typeObject.getString("name") ?: "resource#$index"
            WitRuntimeResource(
                name = typeName,
                stability = null,
                ownHandleType = "own:$ownerInterface/$typeName",
                borrowHandleType = "borrow:$ownerInterface/$typeName",
            )
        }
    }

    private fun buildWorld(
        worldObject: JsonObject,
        context: RuntimeContext,
        interfaceNamesByIndex: Map<Int, String>,
        metadataByName: Map<String, WitWorldMetadata>,
    ): WitRuntimeWorld? {
        val name = worldObject.getString("name") ?: return null
        if (metadataByName.isNotEmpty() && name !in metadataByName) return null
        val stability = metadataByName[name]?.stability
        val imports = buildBindings(worldObject["imports"]?.jsonObject, context, interfaceNamesByIndex)
        val exports = buildBindings(worldObject["exports"]?.jsonObject, context, interfaceNamesByIndex)
        val constructors = linkedMapOf<String, WitRuntimeConstructor>()
        (imports + exports).forEach { binding ->
            val signature = binding.signature ?: return@forEach
            if (signature.kind == FunctionKind.CONSTRUCTOR) {
                constructors.putIfAbsent(binding.name, WitRuntimeConstructor(binding.name, signature))
            }
        }
        return WitRuntimeWorld(
            name = name,
            stability = stability,
            imports = imports,
            exports = exports,
            constructors = constructors.values.toList(),
        )
    }

    private fun buildBindings(
        block: JsonObject?,
        context: RuntimeContext,
        interfaceNamesByIndex: Map<Int, String>,
    ): List<WitRuntimeBinding> {
        if (block == null || block.isEmpty()) return emptyList()
        val bindings = mutableListOf<WitRuntimeBinding>()
        for ((bindingName, element) in block) {
            val obj = element.jsonObject
            when {
                "interface" in obj -> {
                    val index = obj["interface"]?.jsonPrimitive?.content?.toIntOrNull()
                    val interfaceName = index?.let { interfaceNamesByIndex[it] ?: context.interfaceName(it) }
                        ?: "interface"
                    bindings += WitRuntimeBinding(
                        name = bindingName,
                        target = interfaceName,
                        bindingKind = BindingKind.INTERFACE,
                        signature = null,
                    )
                }
                "type" in obj -> {
                    val index = obj["type"]?.jsonPrimitive?.content?.toIntOrNull()
                    val typeName = index?.let { context.typeName(it) } ?: "type"
                    bindings += WitRuntimeBinding(
                        name = bindingName,
                        target = typeName,
                        bindingKind = BindingKind.RESOURCE,
                        signature = null,
                    )
                }
                "function" in obj -> {
                    val functionObject = obj["function"]?.jsonObject ?: continue
                    val signature = buildFunction(functionObject, context) ?: continue
                    bindings += WitRuntimeBinding(
                        name = bindingName,
                        target = signature.name,
                        bindingKind = BindingKind.FUNCTION,
                        signature = signature,
                    )
                }
            }
        }
        return bindings
    }

    private fun buildFunction(
        functionObject: JsonObject,
        context: RuntimeContext,
    ): WitRuntimeFunction? {
        val name = functionObject.getString("name") ?: return null
        var usesStreams = false
        val params = functionObject["params"]?.jsonArray?.mapNotNull { paramElement ->
            val paramObject = paramElement.jsonObject
            val label = paramObject.getString("name")
            val typeElement = paramObject["type"]
            if (context.usesStreams(typeElement)) {
                usesStreams = true
            }
            val typeRef = typeElement?.let { context.typeRef(it) } ?: return@mapNotNull null
            WitRuntimeType(label = label, typeRef = typeRef)
        }.orEmpty()
        val resultElement = functionObject["result"]
        val results = parseResults(resultElement, context)
        if (context.usesStreams(resultElement)) {
            usesStreams = true
        }
        val (kind, isAsync) = parseFunctionKind(functionObject["kind"])
        return WitRuntimeFunction(
            name = name,
            parameters = params,
            results = results,
            kind = kind,
            isAsync = isAsync,
            usesStreams = usesStreams,
        )
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun parseResults(
        resultElement: JsonElement?,
        context: RuntimeContext,
    ): List<WitRuntimeType> {
        return when (resultElement) {
            null, JsonNull -> emptyList()
            is JsonArray -> resultElement.mapNotNull { element ->
                val typeRef = context.typeRef(element) ?: return@mapNotNull null
                WitRuntimeType(label = null, typeRef = typeRef)
            }
            is JsonPrimitive -> {
                val typeRef = context.typeRef(resultElement) ?: return emptyList()
                listOf(WitRuntimeType(label = null, typeRef = typeRef))
            }
            is JsonObject -> when {
                "type" in resultElement -> {
                    val typeRef = resultElement["type"]?.let { context.typeRef(it) } ?: return emptyList()
                    listOf(WitRuntimeType(label = null, typeRef = typeRef))
                }
                "types" in resultElement -> {
                    resultElement["types"]?.jsonArray?.mapNotNull { element ->
                        val typeRef = context.typeRef(element) ?: return@mapNotNull null
                        WitRuntimeType(label = null, typeRef = typeRef)
                    }.orEmpty()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun parseFunctionKind(kindElement: JsonElement?): Pair<FunctionKind, Boolean> {
        if (kindElement == null || kindElement is JsonNull) return FunctionKind.FUNCTION to false
        return when (kindElement) {
            is JsonPrimitive -> {
                val raw = kindElement.content
                val async = raw.startsWith("async-")
                val key = if (async) raw.removePrefix("async-") else raw
                resolveFunctionKind(key) to async
            }
            is JsonObject -> {
                val entry = kindElement.entries.firstOrNull()
                if (entry == null) {
                    FunctionKind.FUNCTION to false
                } else {
                    val (key, _) = entry
                    val async = key.startsWith("async-")
                    val normalized = if (async) key.removePrefix("async-") else key
                    resolveFunctionKind(normalized) to async
                }
            }
            is JsonArray -> FunctionKind.FUNCTION to false
            else -> FunctionKind.FUNCTION to false
        }
    }

    private fun resolveFunctionKind(key: String): FunctionKind {
        return when (key) {
            "freestanding" -> FunctionKind.FUNCTION
            "method" -> FunctionKind.METHOD
            "static" -> FunctionKind.STATIC
            "constructor" -> FunctionKind.CONSTRUCTOR
            "lift" -> FunctionKind.LIFT
            "lower" -> FunctionKind.LOWER
            else -> FunctionKind.FUNCTION
        }
    }

    private class RuntimeContext(root: JsonObject) {
        val interfaces: List<JsonObject> =
            root["interfaces"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val worlds: List<JsonObject> =
            root["worlds"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val types: List<JsonObject> =
            root["types"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

        fun interfaceName(index: Int): String =
            interfaces.getOrNull(index)?.getString("name") ?: "interface#$index"

        fun typeName(index: Int): String =
            types.getOrNull(index)?.getString("name") ?: "type#$index"

        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        fun typeRef(element: JsonElement?): String? {
            element ?: return null
            return when (element) {
                is JsonPrimitive -> {
                    val content = element.content
                    when {
                        element.isString -> content
                        content.equals("true", ignoreCase = true) || content.equals("false", ignoreCase = true) -> content
                        content.toIntOrNull() != null -> {
                            val index = content.toIntOrNull()
                            index?.let { typeName(it) }
                        }
                        content.toLongOrNull() != null -> {
                            val index = content.toLongOrNull()?.toInt()
                            index?.let { typeName(it) }
                        }
                        content.toDoubleOrNull() != null -> content
                        else -> content
                    }
                }
                is JsonObject -> element.toString()
                is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { typeRef(it) ?: "null" }
                else -> element.toString()
            }
        }

        fun usesStreams(element: JsonElement?): Boolean {
            if (element == null || element is JsonNull) return false
            return detectStreamUsage(element, mutableSetOf())
        }

        private fun detectStreamUsage(element: JsonElement, visitedTypeIndices: MutableSet<Int>): Boolean {
            return when (element) {
                is JsonNull -> false
                is JsonObject -> {
                    if ("stream" in element) return true
                    element.values.any { detectStreamUsage(it, visitedTypeIndices) }
                }
                is JsonArray -> element.any { detectStreamUsage(it, visitedTypeIndices) }
                is JsonPrimitive -> detectStreamUsageInPrimitive(element, visitedTypeIndices)
            }
        }

        private fun detectStreamUsageInPrimitive(
            primitive: JsonPrimitive,
            visitedTypeIndices: MutableSet<Int>,
        ): Boolean {
            val content = primitive.content
            if (primitive.isString) {
                // Named references fall back to checking for the stream keyword.
                return content.contains("stream")
            }
            val index = content.toIntOrNull() ?: return false
            if (!visitedTypeIndices.add(index)) return false
            val typeObject = types.getOrNull(index) ?: return false
            val kindElement = typeObject["kind"]
            return when (kindElement) {
                is JsonObject -> {
                    if ("stream" in kindElement) return true
                    detectStreamUsage(kindElement, visitedTypeIndices)
                }
                is JsonArray -> detectStreamUsage(kindElement, visitedTypeIndices)
                is JsonPrimitive -> detectStreamUsageInPrimitive(kindElement, visitedTypeIndices)
                else -> false
            }
        }
    }

    private data class PackageCandidate(
        val packageModel: WitRuntimePackage,
        val origin: SchemaOrigin,
    )

    internal data class Input(
        val source: WitSchemaSource,
        val origin: SchemaOrigin,
        val metadataJson: String,
        val sourceFiles: List<Path>,
    )

    internal enum class SchemaOrigin(val priority: Int) {
        JSON(0),
        WIT_FILE(1),
        WIT_DIRECTORY(2),
    }

    private fun JsonObject.getString(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }

}
