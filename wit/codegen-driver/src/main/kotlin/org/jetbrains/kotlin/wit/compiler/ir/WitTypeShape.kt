package org.jetbrains.kotlin.wit.compiler.ir

public sealed class WitTypeShape {
    public object Unknown : WitTypeShape()

    public data class Scalar(val name: String) : WitTypeShape()

    public data class ResourceHandle(val ownership: Ownership) : WitTypeShape() {
        public enum class Ownership { OWNED, BORROWED }
    }
}
