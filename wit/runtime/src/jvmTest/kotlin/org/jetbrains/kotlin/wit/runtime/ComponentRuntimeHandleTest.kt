package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComponentRuntimeHandleTest {
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
    fun ownedHandleLifecycle() {
        val runtime = DefaultComponentRuntime()
        val adapter = RecordingAdapter()

        val handle = runtime.allocateOwnedHandle(adapter)

        assertEquals(adapter, runtime.resolveResourceAdapter(handle))

        runtime.releaseOwnedHandle(handle)

        assertEquals(1, adapter.ownedClosed)
        assertEquals(0, adapter.borrowedClosed)
        assertNull(runtime.resolveResourceAdapter(handle))
    }

    @Test
    fun borrowedHandleLifecycle() {
        val runtime = DefaultComponentRuntime()
        val adapter = RecordingAdapter()

        val handle = runtime.allocateBorrowedHandle(adapter)

        assertEquals(adapter, runtime.resolveResourceAdapter(handle))

        runtime.releaseBorrowedHandle(handle)

        assertEquals(0, adapter.ownedClosed)
        assertEquals(1, adapter.borrowedClosed)
        assertNull(runtime.resolveResourceAdapter(handle))
    }
}
