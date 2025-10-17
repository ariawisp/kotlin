package org.jetbrains.kotlin.wit.runtime

/**
 * Marker interface for handle-like values produced by WIT resources.
 *
 * TODO: add lifetime tracking once the runtime grows real ownership/borrowing semantics.
 */
public sealed interface Handle<out T : Resource> {
    public val id: Int

    public fun close()
}

/**
 * Own handles represent exclusive ownership of a resource instance.
 */
public class OwnHandle<T : Resource>(
    override val id: Int,
    private val releaser: HandleReleaser,
) : Handle<T> {
    override fun close() {
        releaser.release(id)
    }
}

/**
 * Borrow handles represent a temporary borrow; callers must invoke [close] when finished.
 */
public class BorrowHandle<T : Resource>(
    override val id: Int,
    private val releaser: HandleReleaser,
) : Handle<T> {
    override fun close() {
        releaser.release(id)
    }
}

public fun interface HandleReleaser {
    public fun release(id: Int)
}
