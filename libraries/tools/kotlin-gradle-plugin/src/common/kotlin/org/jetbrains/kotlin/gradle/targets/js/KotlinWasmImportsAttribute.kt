/* Registers the stable WASM imports attribute (no custom rules here). */
package org.jetbrains.kotlin.gradle.targets.js

import org.gradle.api.attributes.*

internal object KotlinWasmImportsAttribute {
    val attribute: Attribute<String> = Attribute.of("org.jetbrains.kotlin.wasm.imports", String::class.java)

    fun setupAttributesMatchingStrategy(attributesSchema: AttributesSchema) {
        attributesSchema.attribute(attribute)
    }
}
