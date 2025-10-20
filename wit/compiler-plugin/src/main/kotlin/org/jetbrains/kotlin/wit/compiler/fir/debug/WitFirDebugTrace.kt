package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType

internal fun renderDebugTrace(worldDeclarations: Map<ClassId, WorldMetadata>): String {
    if (worldDeclarations.isEmpty()) return "WIT FIR trace: <empty>"
    val sortedWorlds = worldDeclarations.entries.sortedBy { entry ->
        val metadata = entry.value
        "${metadata.packageId}/${metadata.runtimeWorld.name}"
    }
    return buildString {
        append("WIT FIR trace: ")
        append(sortedWorlds.size)
        append(" world(s)")
        sortedWorlds.forEach { (classId, metadata) ->
            appendLine()
            append("  • ")
            append(metadata.packageId)
            append('/')
            append(metadata.runtimeWorld.name)
            append(" -> ")
            append(classId.asString())
            appendLine()
            append("    interfaces (")
            append(metadata.interfaceBindings.size)
            append("):")
            if (metadata.interfaceBindings.isEmpty()) {
                append(" <none>")
            } else {
                metadata.interfaceBindings.entries
                    .sortedBy { it.key.asString() }
                    .forEach { (name, interfaceMetadata) ->
                        appendLine()
                        append("      - ")
                        append(name.asString())
                        interfaceMetadata.runtime?.name?.let { runtimeName ->
                            append(" :: ")
                            append(runtimeName)
                        }
                    }
            }
            appendLine()
            append("    bindings (")
            val totalBindings = metadata.importBindings.size + metadata.exportBindings.size
            append(totalBindings)
            append("):")
            if (totalBindings == 0) {
                append(" <none>")
            } else {
                val orderedBindings = buildList {
                    metadata.importBindings.entries.forEach { add(it) }
                    metadata.exportBindings.entries.forEach { add(it) }
                }.sortedBy { it.key.asString() }
                orderedBindings.forEach { (name, bindingMetadata) ->
                    val runtimeBinding = bindingMetadata.binding
                    appendLine()
                    append("      - ")
                    append(name.asString())
                    append(" :: ")
                    append(bindingMetadata.direction)
                    append('/')
                    append(runtimeBinding.bindingKind)
                    append(" [")
                    append(runtimeBinding.name)
                    append(']')
                    if (runtimeBinding.target.isNotEmpty()) {
                        append(" target=")
                        append(runtimeBinding.target)
                    }
                    bindingMetadata.functionStubName?.let { stub ->
                        append(" stub=")
                        append(stub.asString())
                    }
                    bindingMetadata.hostFunctionName?.let { host ->
                        append(" host=")
                        append(host.asString())
                    }
                    runtimeBinding.signature?.let { signature ->
                        append(" sig=")
                        append(signature.kind)
                        append(" params=")
                        append(renderParameters(signature.parameters))
                        append(" results=")
                        append(renderResults(signature.results))
                        if (signature.isAsync) append(" async")
                        if (signature.usesStreams) append(" streams")
                    }
                }
            }
            appendLine()
            append("    resources (")
            append(metadata.resourceBindings.size)
            append("):")
            if (metadata.resourceBindings.isEmpty()) {
                append(" <none>")
            } else {
                metadata.resourceBindings.entries
                    .sortedBy { it.key.asString() }
                    .forEach { (name, resourceMetadata) ->
                        val resource = resourceMetadata.resource
                        appendLine()
                        append("      - ")
                        append(name.asString())
                        append(" :: ")
                        append(resourceMetadata.runtimeInterface.name)
                        append('/')
                        append(resource.name)
                        resource.ownHandleType?.takeIf { it.isNotEmpty() }?.let {
                            append(" own=")
                            append(it)
                        }
                        resource.borrowHandleType?.takeIf { it.isNotEmpty() }?.let {
                            append(" borrow=")
                            append(it)
                        }
                    }
            }
            appendLine()
            val constructors = metadata.runtimeWorld.constructors
            append("    constructors (")
            append(constructors.size)
            append("):")
            if (constructors.isEmpty()) {
                append(" <none>")
            } else {
                constructors.forEach { constructor ->
                    appendLine()
                    append("      - ")
                    append(constructor.bindingName)
                    metadata.constructorDirection(constructor.bindingName)?.let { direction ->
                        append(" :: ")
                        append(direction)
                    }
                    val signature = constructor.signature
                    append(" sig=")
                    append(signature.kind)
                    append(" params=")
                    append(renderParameters(signature.parameters))
                    append(" results=")
                    append(renderResults(signature.results))
                    if (signature.isAsync) append(" async")
                    if (signature.usesStreams) append(" streams")
                }
            }
            appendLine()
            append("    driver: companion=")
            append(metadata.driverCompanionClassId.asString())
            append(" class=")
            append(metadata.driverClassId.asString())
            append(" bind=")
            append(metadata.bindCallableId)
        }
    }
}

private fun renderParameters(parameters: List<WitRuntimeType>): String =
    if (parameters.isEmpty()) {
        "()"
    } else {
        parameters.joinToString(prefix = "(", postfix = ")", separator = ", ") { parameter ->
            val label = parameter.label?.takeIf { it.isNotBlank() } ?: "_"
            "$label:${parameter.typeRef}"
        }
    }

private fun renderResults(results: List<WitRuntimeType>): String =
    if (results.isEmpty()) {
        "()"
    } else {
        results.joinToString(prefix = "(", postfix = ")", separator = ", ") { it.typeRef }
    }
