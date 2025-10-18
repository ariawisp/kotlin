package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.wit.model.TypeRef

internal fun sanitizeIdentifier(raw: String): String {
    if (raw.isEmpty()) return "_"
    val builder = StringBuilder(raw.length)
    raw.forEachIndexed { index, ch ->
        val mapped = when {
            ch.isLetterOrDigit() || ch == '_' -> ch
            ch == '-' || ch == '/' || ch == '.' -> '_'
            else -> '_'
        }
        if (index == 0 && mapped.isDigit()) {
            builder.append('_')
        }
        builder.append(mapped)
    }
    return builder.toString()
}

internal fun interfacePropertyName(name: String): String = "__witInterface_${sanitizeIdentifier(name)}"
internal fun bindingPropertyName(prefix: String, name: String): String = "__wit${prefix}_${sanitizeIdentifier(name)}"
internal fun bindingFunctionName(prefix: String, name: String): String = "__wit${prefix}Fn_${sanitizeIdentifier(name)}"
internal fun resourcePropertyName(interfaceName: String, resourceName: String): String =
    buildString {
        append("__witResource_")
        if (interfaceName.isNotBlank()) {
            append(sanitizeIdentifier(interfaceName))
            append('_')
        }
        append(sanitizeIdentifier(resourceName))
    }

internal fun constructorHelperName(bindingName: String): String {
    val base = bindingName.substringAfter("constructor:", bindingName)
    return "__witConstruct_${sanitizeIdentifier(base)}"
}

internal fun splitResourceTarget(raw: String): Pair<String, String> {
    val parts = raw.split('/', limit = 2)
    return when (parts.size) {
        2 -> parts[0] to parts[1]
        else -> "" to parts.firstOrNull().orEmpty()
    }
}

internal fun handleType(prefix: String, interfaceName: String, resourceName: String): String {
    val suffix = buildString {
        if (interfaceName.isNotBlank()) {
            append(interfaceName)
            append('/')
        }
        append(resourceName)
    }.ifBlank { resourceName }
    return "$prefix:$suffix"
}

internal fun TypeRef.toDisplayString(): String = when (this) {
    is TypeRef.Primitive -> name
    is TypeRef.Identifier -> name
    is TypeRef.Tuple -> elements.joinToString(prefix = "[", postfix = "]") { it.toDisplayString() }
    is TypeRef.ListT -> "list<${element.toDisplayString()}>"
    is TypeRef.FixedList -> "list<${element.toDisplayString()},$size>"
    is TypeRef.Option -> "option<${element.toDisplayString()}>"
    is TypeRef.Result -> {
        val okStr = ok?.toDisplayString()
        val errStr = err?.toDisplayString()
        when {
            okStr == null && errStr == null -> "result"
            errStr == null -> "result<$okStr>"
            else -> "result<${okStr ?: "_"},$errStr>"
        }
    }
    is TypeRef.Future -> output?.let { "future<${it.toDisplayString()}>" } ?: "future"
    is TypeRef.Stream -> buildString {
        append("stream")
        val el = element
        val en = end
        if (el != null || en != null) {
            append('<')
            append(el?.toDisplayString() ?: "_")
            if (en != null) {
                append(',')
                append(en.toDisplayString())
            }
            append('>')
        }
    }
    is TypeRef.ErrorContext -> "error-context"
    is TypeRef.Record -> fields.joinToString(prefix = "record{", postfix = "}") { "${it.name}:${it.type.toDisplayString()}" }
    is TypeRef.Flags -> "flags{${names.joinToString()}}"
    is TypeRef.EnumT -> "enum{${names.joinToString()}}"
    is TypeRef.Variant -> cases.joinToString(prefix = "variant{", postfix = "}") { case ->
        val caseType = case.type
        if (caseType != null) "${case.name}:${caseType.toDisplayString()}" else case.name
    }
    is TypeRef.Handle -> when (ownership) {
        TypeRef.Handle.Ownership.OWN -> "own:$resource"
        TypeRef.Handle.Ownership.BORROW -> "borrow:$resource"
    }
}
