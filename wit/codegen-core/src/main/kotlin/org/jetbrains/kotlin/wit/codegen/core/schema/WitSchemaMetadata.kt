package org.jetbrains.kotlin.wit.codegen.core.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WitPackageMetadata(
    val name: String,
    val interfaces: List<WitInterfaceMetadata>,
    val worlds: List<WitWorldMetadata>,
)

data class WitInterfaceMetadata(
    val name: String,
    val stability: Stability?,
)

data class WitWorldMetadata(
    val name: String,
    val stability: Stability?,
)

sealed interface Stability {
    data object Stable : Stability
    data class Unstable(val feature: String) : Stability
}

private object StabilityFilter {
    fun isEnabled(stability: JsonObject?, enabledFeatures: Set<String>): Boolean {
        stability ?: return true
        val unstable = stability["unstable"]?.jsonObject ?: return true
        val feature = unstable["feature"]?.jsonPrimitive?.contentOrNull ?: return false
        return feature in enabledFeatures
    }

    fun parse(stability: JsonObject?, enabledFeatures: Set<String>): Stability? {
        stability ?: return null
        val unstable = stability["unstable"]?.jsonObject
        if (unstable != null) {
            val feature = unstable["feature"]?.jsonPrimitive?.contentOrNull ?: return null
            return if (feature in enabledFeatures) Stability.Unstable(feature) else null
        }
        return Stability.Stable
    }
}

internal object WitSchemaMetadataParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePackages(metadataJson: String, enabledFeatures: Set<String>): List<WitPackageMetadata> {
        val element = runCatching { json.parseToJsonElement(metadataJson) }.getOrNull() ?: return emptyList()
        val root = element.jsonObject
        val packagesArray = root["packages"]?.jsonArray ?: return emptyList()
        val interfacesArray = root["interfaces"]?.jsonArray.orEmpty()
        val worldsArray = root["worlds"]?.jsonArray.orEmpty()

        val interfaceAllowed = interfacesArray.map { ifaceElement ->
            StabilityFilter.isEnabled(ifaceElement.jsonObject["stability"]?.jsonObject, enabledFeatures)
        }
        val worldAllowed = worldsArray.map { worldElement ->
            StabilityFilter.isEnabled(worldElement.jsonObject["stability"]?.jsonObject, enabledFeatures)
        }

        return packagesArray.mapNotNull { pkgElement ->
            parsePackage(pkgElement.jsonObject, interfacesArray, interfaceAllowed, worldsArray, worldAllowed, enabledFeatures)
        }
    }

    private fun parsePackage(
        obj: JsonObject,
        interfacesArray: List<JsonElement>,
        interfaceAllowed: List<Boolean>,
        worldsArray: List<JsonElement>,
        worldAllowed: List<Boolean>,
        enabledFeatures: Set<String>,
    ): WitPackageMetadata? {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val interfaces = obj["interfaces"]
            ?.jsonObject
            ?.entries
            ?.mapNotNull { (key, value) ->
                val idx = value.jsonPrimitive.int
                if (interfaceAllowed.getOrNull(idx) != true) return@mapNotNull null
                val stabilityElement = interfacesArray.getOrNull(idx)?.jsonObject?.get("stability")?.jsonObject
                if (!StabilityFilter.isEnabled(stabilityElement, enabledFeatures)) return@mapNotNull null
                val stability = StabilityFilter.parse(stabilityElement, enabledFeatures)
                WitInterfaceMetadata(key, stability)
            }
            ?.sortedBy { it.name }
            ?: emptyList()

        val worlds = obj["worlds"]
            ?.jsonObject
            ?.entries
            ?.mapNotNull { (key, value) ->
                val idx = value.jsonPrimitive.int
                if (worldAllowed.getOrNull(idx) != true) return@mapNotNull null
                val stabilityElement = worldsArray.getOrNull(idx)?.jsonObject?.get("stability")?.jsonObject
                if (!StabilityFilter.isEnabled(stabilityElement, enabledFeatures)) return@mapNotNull null
                val stability = StabilityFilter.parse(stabilityElement, enabledFeatures)
                WitWorldMetadata(key, stability)
            }
            ?.sortedBy { it.name }
            ?: emptyList()

        return WitPackageMetadata(name, interfaces, worlds)
    }
}
