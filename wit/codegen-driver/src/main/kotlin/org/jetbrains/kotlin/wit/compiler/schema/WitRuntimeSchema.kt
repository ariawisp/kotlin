package org.jetbrains.kotlin.wit.compiler.schema

import java.nio.file.Path

/**
 * Structured view of the resolved WIT packages that the runtime/FIR/IR phases can consume.
 * This intentionally mirrors the information emitted by the Rust wasm-tools parser but
 * normalises it into Kotlin-friendly shapes and keeps track of source provenance.
 */
data class WitRuntimeSchema(
    val packages: List<WitRuntimePackage>,
    val features: Set<String>,
    val sources: List<WitSchemaSource>,
)

data class WitRuntimePackage(
    val id: String,
    val source: WitSchemaSource,
    val includes: List<Path>,
    val sourceFiles: List<Path>,
    val metadata: WitPackageMetadata?,
    val interfaces: List<WitRuntimeInterface>,
    val worlds: List<WitRuntimeWorld>,
)

data class WitRuntimeInterface(
    val name: String,
    val stability: Stability?,
    val resources: List<WitRuntimeResource>,
    val functions: List<WitRuntimeFunction>,
)

data class WitRuntimeWorld(
    val name: String,
    val stability: Stability?,
    val imports: List<WitRuntimeBinding>,
    val exports: List<WitRuntimeBinding>,
    val constructors: List<WitRuntimeConstructor>,
)

data class WitRuntimeResource(
    val name: String,
    val stability: Stability?,
    val ownHandleType: String?,
    val borrowHandleType: String?,
)

data class WitRuntimeFunction(
    val name: String,
    val parameters: List<WitRuntimeType>,
    val results: List<WitRuntimeType>,
    val kind: FunctionKind,
    val isAsync: Boolean = false,
    val usesStreams: Boolean = false,
)

data class WitRuntimeBinding(
    val name: String,
    val target: String,
    val bindingKind: BindingKind,
    val signature: WitRuntimeFunction?,
)

data class WitRuntimeConstructor(
    val bindingName: String,
    val signature: WitRuntimeFunction,
)

data class WitRuntimeType(
    val label: String?,
    val typeRef: String,
)

enum class FunctionKind {
    FUNCTION,
    METHOD,
    STATIC,
    LIFT,
    LOWER,
    CONSTRUCTOR,
}

enum class BindingKind {
    FUNCTION,
    INTERFACE,
    RESOURCE,
}

sealed interface WitSchemaSource {
    val path: Path

    data class Directory(
        override val path: Path,
        val includeRoots: List<Path>,
    ) : WitSchemaSource

    data class File(
        override val path: Path,
    ) : WitSchemaSource

    data class Json(
        override val path: Path,
    ) : WitSchemaSource
}
