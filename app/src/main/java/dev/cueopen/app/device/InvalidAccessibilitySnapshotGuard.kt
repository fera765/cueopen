package dev.cueopen.app.device

/** Evita repetir uma ação com um ID que já foi recusado como snapshot ausente/obsoleto. */
internal class InvalidAccessibilitySnapshotGuard {
    private val invalidIds = mutableSetOf<String>()

    fun recordFailure(snapshotId: String?, output: String) {
        if (snapshotId.isNullOrBlank()) return
        val stale = listOf(
            "Snapshot ausente ou invalidado",
            "snapshot_id expirado",
            "snapshot_id ausente",
            "A janela mudou desde a leitura",
            "A árvore de controles mudou",
            "O controle mudou desde a leitura",
            "element_id desconhecido neste snapshot",
        ).any { phrase -> output.contains(phrase) }
        if (stale) {
            if (invalidIds.size >= MAX_INVALID_IDS) invalidIds.remove(invalidIds.first())
            invalidIds += snapshotId
        }
    }

    fun shouldBlock(snapshotId: String?): Boolean =
        !snapshotId.isNullOrBlank() && snapshotId in invalidIds

    private companion object {
        const val MAX_INVALID_IDS = 128
    }
}
