package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceHandleManagerTest {
    private class RecordingAdapter : ResourceAdapter {
        var ownedClosed = 0
        var borrowedClosed = 0

        override val type: ResourceType = ResourceType("pkg", "iface", "resource")

        override fun ownHandlePrototype(): Handle<Resource>? = null

        override fun borrowHandlePrototype(): Handle<Resource>? = null

        override fun onCloseOwned() {
            ownedClosed += 1
        }

        override fun onCloseBorrowed() {
            borrowedClosed += 1
        }
    }

    @Test
    fun releasesOwnedAdapters() {
        val manager = DefaultResourceHandleManager()
        val adapter = RecordingAdapter()
        val handle = manager.allocateOwned(adapter)

        assertEquals(adapter, manager.resolve(handle))

        handle.close()

        assertEquals(1, adapter.ownedClosed)
        assertEquals(0, adapter.borrowedClosed)
        assertNull(manager.resolve(ComponentHandle(handle.id)))
    }

    @Test
    fun releasesBorrowedAdapters() {
        val manager = DefaultResourceHandleManager()
        val adapter = RecordingAdapter()
        val handle = manager.allocateBorrowed(adapter)

        assertEquals(adapter, manager.resolve(handle))

        handle.close()

        assertEquals(0, adapter.ownedClosed)
        assertEquals(1, adapter.borrowedClosed)
        assertNull(manager.resolve(ComponentHandle(handle.id)))
    }
}
