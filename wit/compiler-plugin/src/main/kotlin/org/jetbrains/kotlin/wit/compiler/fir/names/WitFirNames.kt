package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.compiler.schema.FunctionKind
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding

internal fun interfacePropertyName(rawName: String): Name =
    Name.identifier("__witInterface_" + sanitizeIdentifier(rawName))

internal fun bindingPropertyName(prefix: String, rawName: String): Name =
    Name.identifier("__wit${prefix}_" + sanitizeIdentifier(rawName))

internal fun resourcePropertyName(interfaceName: String, resourceName: String): Name =
    Name.identifier("__witResource_" + sanitizeIdentifier(interfaceName) + "_" + sanitizeIdentifier(resourceName))

internal fun allocateConstructorHelperName(rawBase: String, usedNames: MutableSet<String>): Name {
    val sanitizedBase = sanitizeIdentifier(rawBase).ifEmpty { "resource" }
    var candidate = CONSTRUCTOR_HELPER_PREFIX + sanitizedBase
    var suffix = 1
    while (!usedNames.add(candidate)) {
        candidate = CONSTRUCTOR_HELPER_PREFIX + sanitizedBase + "_" + suffix++
    }
    return Name.identifier(candidate)
}

internal fun allocateHostFunctionName(rawBaseName: String, usedNames: MutableSet<String>): String {
    val sanitizedBase = sanitizeIdentifier(rawBaseName).ifEmpty { "binding" }
    var candidate = sanitizedBase
    var suffix = 1
    while (!usedNames.add(candidate)) {
        candidate = "${sanitizedBase}_${suffix++}"
    }
    return candidate
}

internal fun computeHostFunctionBaseName(
    binding: WitRuntimeBinding,
    interfaceName: String?,
    resourceName: String?,
): String {
    val signature = binding.signature ?: return binding.name
    val parts = parseWitFunctionName(signature.name)
    val cleanedInterface = interfaceName?.takeIf { it.isNotBlank() }
    val cleanedResource = resourceName?.takeIf { it.isNotBlank() }
    return when (signature.kind) {
        FunctionKind.METHOD -> {
            val segments = mutableListOf<String>()
            (cleanedResource ?: parts.scope?.takeIf { it.isNotBlank() })?.let { segments += it }
            parts.member.takeIf { it.isNotBlank() && it != segments.lastOrNull() }?.let { segments += it }
            segments.takeIf { it.isNotEmpty() }?.joinToString("_") ?: binding.name
        }
        FunctionKind.CONSTRUCTOR -> {
            (cleanedResource ?: parts.scope ?: binding.name).ifEmpty { binding.name }
        }
        FunctionKind.STATIC -> {
            val segments = mutableListOf<String>()
            cleanedInterface?.let { segments += it }
            parts.member.takeIf { it.isNotBlank() }?.let { segments += it }
            segments.takeIf { it.isNotEmpty() }?.joinToString("_") ?: binding.name
        }
        else -> binding.name
    }
}

internal fun functionStubName(binding: WitRuntimeBinding, direction: BindingDirection): Name? {
    val signature = binding.signature ?: return null
    if (signature.isAsync || signature.usesStreams) return null
    val supported = when (signature.kind) {
        FunctionKind.CONSTRUCTOR,
        FunctionKind.METHOD,
        FunctionKind.STATIC -> true
        else -> false
    }
    if (!supported) return null
    val directionSegment = when (direction) {
        BindingDirection.IMPORT -> "Import"
        BindingDirection.EXPORT -> "Export"
    }
    return Name.identifier("__wit${directionSegment}Fn_" + sanitizeIdentifier(binding.name))
}

internal fun sanitizeParameterName(label: String?, index: Int): String {
    val base = label?.takeIf { it.isNotBlank() } ?: "param$index"
    return sanitizeIdentifier(base)
}

internal fun sanitizeIdentifier(raw: String): String {
    if (raw.isEmpty()) return "_"
    val builder = StringBuilder(raw.length)
    raw.forEach { ch ->
        builder.append(
            when {
                ch == '_' -> '_'
                ch.isLetterOrDigit() -> ch
                else -> '_'
            },
        )
    }
    if (builder.isEmpty()) return "_"
    if (!builder.first().isLetter() && builder.first() != '_') {
        builder.insert(0, '_')
    }
    return builder.toString()
}

internal fun packageToFqName(packageId: String): FqName {
    val withoutVersion = packageId.substringBefore('@')
    val segments = withoutVersion.replace(':', '.').split('.')
    val sanitized = buildList {
        add("wit")
        add("generated")
        segments.filter { it.isNotBlank() }.forEach { add(sanitizeIdentifier(it)) }
    }
    return FqName(sanitized.joinToString(separator = "."))
}

internal fun parseWitFunctionName(rawName: String): WitFunctionNameParts {
    if (!rawName.startsWith("[")) {
        return WitFunctionNameParts(prefix = null, scope = null, member = rawName)
    }
    val closingIndex = rawName.indexOf(']')
    if (closingIndex <= 0) {
        return WitFunctionNameParts(prefix = null, scope = null, member = rawName)
    }
    val prefix = rawName.substring(1, closingIndex)
    val remainder = rawName.substring(closingIndex + 1)
    val scope = when {
        prefix == "constructor" -> remainder.takeIf { it.isNotBlank() }
        remainder.contains('.') -> remainder.substringBefore('.').takeIf { it.isNotBlank() }
        else -> null
    }
    val member = when {
        remainder.contains('.') -> remainder.substringAfter('.')
        else -> remainder
    }.ifBlank { remainder }
    return WitFunctionNameParts(
        prefix = prefix,
        scope = scope,
        member = member,
    )
}

internal data class WitFunctionNameParts(
    val prefix: String?,
    val scope: String?,
    val member: String,
)

private const val CONSTRUCTOR_HELPER_PREFIX: String = "__witConstruct_"
