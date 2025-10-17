package org.jetbrains.kotlin.wit.runtime

/**
 * Describes the WIT-level shape of a binding. This metadata originates from the
 * schema and is propagated through FIR/IR annotations so runtime components can
 * perform typed marshalling rather than relying on `Any?` arrays.
 */
public data class BindingSignature(
    val parameters: List<BindingTypeRef>,
    val results: List<BindingTypeRef>,
) {
    public val hasParameters: Boolean get() = parameters.isNotEmpty()
    public val hasResults: Boolean get() = results.isNotEmpty()

    public companion object {
        public val EMPTY: BindingSignature = BindingSignature(emptyList(), emptyList())
    }
}

/**
 * Simplified description of a single WIT type reference. For now this only
 * tracks the schema label (if any) and the raw type reference string; future
 * iterations can expand it with fully resolved type metadata once marshalling
 * gains dedicated lowering.
 */
public data class BindingTypeRef(
    val typeRef: String,
    val label: String = "",
    val shape: BindingValueShape = BindingValueShape.Unknown,
)
