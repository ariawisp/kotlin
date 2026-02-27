package org.jetbrains.kotlin.wit.runtime

/**
 * Tracks resource adapters that have been exposed to the host or wasm exports.
 * A manager allocates stable handle identifiers, resolves adapters on demand,
 * and ensures lifecycle callbacks fire when handles are released.
 */
public interface ResourceHandleManager {
    public fun allocateOwned(adapter: ResourceAdapter): OwnHandle<Resource>
    public fun allocateBorrowed(adapter: ResourceAdapter): BorrowHandle<Resource>
    public fun resolve(handle: ComponentHandle): ResourceAdapter?
    public fun resolve(handle: Handle<Resource>): ResourceAdapter? = resolve(ComponentHandle(handle.id))
    public fun releaseOwned(handleId: Int)
    public fun releaseBorrowed(handleId: Int)

    public fun releaseOwned(handle: OwnHandle<out Resource>) {
        releaseOwned(handle.id)
    }

    public fun releaseBorrowed(handle: BorrowHandle<out Resource>) {
        releaseBorrowed(handle.id)
    }
}
