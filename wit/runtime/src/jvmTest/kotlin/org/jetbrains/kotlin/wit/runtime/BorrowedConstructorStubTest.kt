package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BorrowedConstructorStubTest {
    @Test
    fun borrowedConstructorThrowsUnsupported() {
        BorrowedWorld.__witRuntime = DefaultComponentRuntime()

        val exception = assertFailsWith<UnsupportedOperationException> {
            BorrowedWorld().__witExportFn__constructor_resource(42)
        }

        assertEquals(EXPECTED_MESSAGE, exception.message)
    }

    private class BorrowedWorld {
        companion object {
            var __witRuntime: ComponentRuntime? = null

            val __witExport__constructor_resource: BindingDelegate = pendingBindingDelegate(
                packageId = PACKAGE_ID,
                worldName = WORLD_NAME,
                bindingName = BINDING_NAME,
                direction = WitBindingDirection.EXPORT,
                kind = WitBindingKind.RESOURCE,
                runtimeTarget = "",
                isAsync = false,
                usesStreams = false,
            )
        }

        fun __witExportFn__constructor_resource(size: Any): Any {
            throw UnsupportedOperationException(EXPECTED_MESSAGE)
        }
    }

    private companion object {
        private const val PACKAGE_ID = "pkg"
        private const val WORLD_NAME = "world"
        private const val BINDING_NAME = "[constructor]borrowed"
        private const val RESOURCE_INTERFACE = "world"
        private const val RESOURCE_NAME = "borrowed"
        private val EXPECTED_MESSAGE =
            "Borrowed resource constructors are not supported yet: " +
                "$PACKAGE_ID/$WORLD_NAME -> $BINDING_NAME (resource=$RESOURCE_INTERFACE/$RESOURCE_NAME)"
    }
}
