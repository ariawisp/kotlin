package org.jetbrains.kotlin.wit.compiler.ir

internal sealed class WitTypeShape {
    object Unknown : WitTypeShape()

    data class Scalar(val name: String) : WitTypeShape()

    data class ResourceHandle(val ownership: Ownership) : WitTypeShape() {
        enum class Ownership { OWNED, BORROWED }
    }
}
