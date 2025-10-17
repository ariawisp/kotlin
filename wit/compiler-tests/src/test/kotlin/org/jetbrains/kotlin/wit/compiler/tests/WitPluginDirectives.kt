package org.jetbrains.kotlin.wit.compiler.tests

import org.jetbrains.kotlin.test.directives.model.*

object WitPluginDirectives : SimpleDirectivesContainer() {
    val WIT_SCHEMA by stringDirective(
        description = "Name of the embedded WIT JSON schema resource to load for this test.",
    )
}
