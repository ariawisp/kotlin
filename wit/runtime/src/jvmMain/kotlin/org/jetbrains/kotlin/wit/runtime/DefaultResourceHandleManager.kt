package org.jetbrains.kotlin.wit.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class DefaultResourceHandleManager : ResourceHandleManager {
    private val nextId = AtomicInteger(1)
    private val adapters = ConcurrentHashMap<Int, ResourceAdapter>()

    override fun allocateOwned(adapter: ResourceAdapter): OwnHandle<Resource> {
        val id = nextId.getAndIncrement()
        adapters[id] = adapter
        return OwnHandle(id, HandleReleaser { releaseOwned(it) })
    }

    override fun allocateBorrowed(adapter: ResourceAdapter): BorrowHandle<Resource> {
        val id = nextId.getAndIncrement()
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
