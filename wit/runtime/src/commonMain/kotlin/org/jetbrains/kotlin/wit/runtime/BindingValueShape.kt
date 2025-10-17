package org.jetbrains.kotlin.wit.runtime

/**
 * Describes the shape of a value that flows through a WIT binding. This
 * metadata is produced by the compiler plugin so runtime marshallers can make
 * decisions without re-parsing schema strings.
 */
public sealed class BindingValueShape {
    public object Unknown : BindingValueShape()

    public data class Scalar(val name: String) : BindingValueShape()

    public data class ResourceHandle(val ownership: ResourceHandleOwnership) : BindingValueShape()
}

public enum class ResourceHandleOwnership {
    OWNED,
    BORROWED,
}
