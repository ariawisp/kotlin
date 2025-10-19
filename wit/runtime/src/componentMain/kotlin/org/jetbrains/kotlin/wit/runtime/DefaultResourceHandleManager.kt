package org.jetbrains.kotlin.wit.runtime

/**
 * Simple handle manager backed by mutable collections for Wasm runtimes. Handles are currently
 * single-threaded, so we increment identifiers monotonically and drop adapters once the handle
 * closes.
 */
public class DefaultResourceHandleManager : ResourceHandleManager {
    private var nextId: Int = 1
    private val adapters: MutableMap<Int, ResourceAdapter> = mutableMapOf()

    override fun allocateOwned(adapter: ResourceAdapter): OwnHandle<Resource> {
        val id = nextId++
        adapters[id] = adapter
        return OwnHandle(id, HandleReleaser { releaseOwned(it) })
    }

    override fun allocateBorrowed(adapter: ResourceAdapter): BorrowHandle<Resource> {
        val id = nextId++
        adapters[id] = adapter
        return BorrowHandle(id, HandleReleaser { releaseBorrowed(it) })
    }

    override fun resolve(handle: ComponentHandle): ResourceAdapter? = adapters[handle.id]

    override fun releaseOwned(handleId: Int) {
        val adapter = adapters.remove(handleId) ?: return
        adapter.onCloseOwned()
    }

    override fun releaseBorrowed(handleId: Int) {
        val adapter = adapters.remove(handleId) ?: return
        adapter.onCloseBorrowed()
    }
}
