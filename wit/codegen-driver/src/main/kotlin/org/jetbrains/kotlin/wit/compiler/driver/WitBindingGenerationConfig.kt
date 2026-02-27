package org.jetbrains.kotlin.wit.compiler.driver

import java.nio.file.Path

/**
 * Configuration describing how WIT schemas should be located and interpreted.
 */
public data class WitSchemaConfig(
    val rootPaths: List<Path> = emptyList(),
    val includePaths: List<Path> = emptyList(),
    val jsonSchemas: List<Path> = emptyList(),
    val enabledFeatures: Set<String> = emptySet(),
    val allowJsonSchemas: Boolean = false,
)

/**
 * Controls the IR generation pipeline once a [WitSchemaConfig] has been resolved.
 */
public data class WitBindingGenerationConfig(
    val schema: WitSchemaConfig,
    val debugLogging: Boolean = false,
)
