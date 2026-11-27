package dev.fluwy.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilitySnapshotStoreTest {
    @Test
    fun readingForAnotherSessionDoesNotOverwriteExistingSnapshot() {
        val snapshots = AccessibilitySnapshotStore<String>()
        snapshots.put("session-a", "snapshot-a")
        snapshots.put("session-b", "snapshot-b")

        assertEquals("snapshot-a", snapshots["session-a"])
        assertEquals("snapshot-b", snapshots["session-b"])
    }

    @Test
    fun successfulActionInvalidatesOnlyCallingSession() {
        val snapshots = AccessibilitySnapshotStore<String>()
        snapshots.put("session-a", "snapshot-a")
        snapshots.put("session-b", "snapshot-b")

        snapshots.remove("session-a")

        assertNull(snapshots["session-a"])
        assertEquals("snapshot-b", snapshots["session-b"])
    }

    @Test
    fun foregroundWindowChangeCanInvalidateAllSnapshots() {
        val snapshots = AccessibilitySnapshotStore<String>()
        snapshots.put("session-a", "snapshot-a")
        snapshots.put("session-b", "snapshot-b")

        snapshots.clear()

        assertNull(snapshots["session-a"])
        assertNull(snapshots["session-b"])
    }

    @Test
    fun windowEventOnlyInvalidatesSnapshotsFromOtherWindows() {
        data class WindowSnapshot(val packageName: String, val windowId: Int)
        val snapshots = AccessibilitySnapshotStore<WindowSnapshot>()
        snapshots.put("same-window", WindowSnapshot("com.example", 7))
        snapshots.put("other-window", WindowSnapshot("com.example", 8))
        snapshots.put("other-app", WindowSnapshot("com.other", 7))

        snapshots.removeWhere { snapshot ->
            !AccessibilitySnapshotPolicy.keepAfterWindowEvent(
                snapshot.packageName, snapshot.windowId, "com.example", 7,
            )
        }

        assertEquals(WindowSnapshot("com.example", 7), snapshots["same-window"])
        assertNull(snapshots["other-window"])
        assertNull(snapshots["other-app"])
    }
}
