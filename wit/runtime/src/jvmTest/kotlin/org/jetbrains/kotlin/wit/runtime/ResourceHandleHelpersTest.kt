package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResourceHandleHelpersTest {
    private class RecordingAdapter : ResourceAdapter {
        override val type: ResourceType = ResourceType("pkg", "iface", "res")
        var ownedClosed = 0
        var borrowedClosed = 0
        override fun ownHandlePrototype(): Handle<Resource>? = null
        override fun borrowHandlePrototype(): Handle<Resource>? = null
        override fun onCloseOwned() { ownedClosed++ }
        override fun onCloseBorrowed() { borrowedClosed++ }
    }

    @Test
    fun withOwnedHandleClosesOnExit() {
        val runtime = createDefaultRuntime()
        val adapter = RecordingAdapter()
        val id = runtime.withOwnedHandle(adapter) { handle ->
            assertNotNull(runtime.resolveResourceAdapter(handle))
            handle.id
        }
        assertEquals(1, adapter.ownedClosed)
        assertEquals(0, adapter.borrowedClosed)
        assertNull(runtime.resolveResourceAdapter(ComponentHandle(id)))
    }

    @Test
    fun withBorrowedHandleClosesOnExit() {
        val runtime = createDefaultRuntime()
        val adapter = RecordingAdapter()
        val id = runtime.withBorrowedHandle(adapter) { handle ->
            assertNotNull(runtime.resolveResourceAdapter(handle))
            handle.id
        }
        assertEquals(0, adapter.ownedClosed)
        assertEquals(1, adapter.borrowedClosed)
        assertNull(runtime.resolveResourceAdapter(ComponentHandle(id)))
    }
}

