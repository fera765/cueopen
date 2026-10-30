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

    @Test
    fun staleSnapshotErrorsFromClickTypeScrollOrImageInspectionBlockOnlyTheOldId() {
        val staleErrors = listOf(
            "Snapshot ausente ou invalidado; nenhuma ação foi executada.",
            "snapshot_id expirado ou diferente da tela atual.",
            "A janela mudou desde a leitura.",
            "A árvore de controles mudou.",
            "O controle mudou desde a leitura.",
            "element_id desconhecido neste snapshot.",
        )

        staleErrors.forEachIndexed { index, error ->
            val guard = InvalidAccessibilitySnapshotGuard()
            val staleId = "old-$index"
            guard.recordFailure(staleId, "ERRO: $error")
            assertTrue("Expected stale ID to be blocked for $error", guard.shouldBlock(staleId))
            assertFalse(guard.shouldBlock("fresh-$index"))
        }
    }
}
