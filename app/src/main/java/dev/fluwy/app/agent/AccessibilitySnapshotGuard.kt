package dev.fluwy.app.agent

/** Falhas que significam "o snapshot da tela ficou velho" (é preciso ler a tela de novo, não repetir o toque). */
internal val accessibilitySnapshotBoundTools = setOf(
    "device_accessibility_click", "device_accessibility_type",
    "device_accessibility_scroll", "device_accessibility_inspect_image",
)

internal fun isStaleAccessibilitySnapshotFailure(toolName: String, detail: String): Boolean =
    toolName in accessibilitySnapshotBoundTools && listOf(
    "Snapshot ausente ou invalidado", "snapshot_id expirado", "snapshot_id ausente",
    "A janela mudou desde a leitura", "A árvore de controles mudou",
    "O controle mudou desde a leitura", "element_id desconhecido neste snapshot",
    ).any { phrase -> detail.contains(phrase) }

