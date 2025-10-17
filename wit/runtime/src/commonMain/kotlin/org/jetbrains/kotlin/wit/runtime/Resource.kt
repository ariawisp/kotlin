package org.jetbrains.kotlin.wit.runtime

/**
 * Base contract implemented by generated Kotlin wrappers for WIT resources.
 *
 * TODO: extend with async helpers and streaming entry points when the runtime surface requires it.
 */
public interface Resource {
    public val type: ResourceType
}

public data class ResourceType(
    val packageName: String,
    val interfaceName: String,
    val resourceName: String,
)
