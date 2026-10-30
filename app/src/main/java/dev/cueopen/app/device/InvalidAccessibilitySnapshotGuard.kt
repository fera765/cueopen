package dev.cueopen.app.device

/** Evita repetir uma ação com um ID que já foi recusado como snapshot ausente/obsoleto. */
internal class InvalidAccessibilitySnapshotGuard {
    private val invalidIds = mutableSetOf<String>()

    fun recordFailure(snapshotId: String?, output: String) {
        if (snapshotId.isNullOrBlank()) return
        val stale = listOf(
            "Snapshot ausente ou invalidado",
            "snapshot_id expirado",
            "A janela mudou desde a leitura",
            "A árvore de controles mudou",
            "O controle mudou desde a leitura",
        ).any(output::contains)
        if (stale) invalidIds += snapshotId
    }

    fun shouldBlock(snapshotId: String?): Boolean =
        !snapshotId.isNullOrBlank() && snapshotId in invalidIds
}
