package dev.cueopen.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvalidAccessibilitySnapshotGuardTest {
    @Test
    fun failedSnapshotIsBlockedButFreshSnapshotRemainsUsable() {
        val guard = InvalidAccessibilitySnapshotGuard()

        guard.recordFailure("old-snapshot", "ERRO: Snapshot ausente ou invalidado; nenhuma ação foi executada.")

        assertTrue(guard.shouldBlock("old-snapshot"))
        assertFalse(guard.shouldBlock("new-snapshot"))
        assertFalse(guard.shouldBlock(null))
    }

    @Test
    fun unrelatedActionErrorDoesNotBlockSnapshot() {
        val guard = InvalidAccessibilitySnapshotGuard()

        guard.recordFailure("current-snapshot", "O elemento não aceitou toque.")

        assertFalse(guard.shouldBlock("current-snapshot"))
    }
}
