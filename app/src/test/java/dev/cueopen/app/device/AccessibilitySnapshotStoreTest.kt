package dev.cueopen.app.device

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
}
