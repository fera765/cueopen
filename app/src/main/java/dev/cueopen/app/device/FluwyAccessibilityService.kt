package dev.cueopen.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import java.util.UUID

/**
 * Serviço opt-in do Android. Só examina a árvore dos controles visíveis quando uma ferramenta
 * explicitamente solicitada chama readScreen; eventos servem apenas para invalidar snapshots.
 */
class FluwyAccessibilityService : AccessibilityService() {
    private data class ActiveWindow(
        val packageName: String,
        val id: Int,
        val root: AccessibilityNodeInfo,
        val bounds: Rect,
    )

    private data class Target(
        val path: List<Int>,
        val fingerprint: String,
    )

    private data class Snapshot(
        val id: String,
        val ownerSessionId: String,
        val createdAt: Long,
        val packageName: String,
        val windowId: Int,
        val targets: Map<Int, Target>,
    )

    private data class Visit(val node: AccessibilityNodeInfo, val path: List<Int>)
    private data class LocatedTarget(val node: AccessibilityNodeInfo, val windowBounds: Rect)

    private val snapshots = AccessibilitySnapshotStore<Snapshot>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100L
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AccessibilityAutomationBridge.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Eventos de conteúdo/foco podem ser ruído ou animações. locate() sempre relê a janela e
        // compara caminho, visibilidade e fingerprint antes da ação. Só uma troca de janela invalida tudo.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            snapshots.clear()
        }
    }

    override fun onInterrupt() {
        snapshots.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        snapshots.clear()
        AccessibilityAutomationBridge.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        snapshots.clear()
        AccessibilityAutomationBridge.disconnect(this)
        super.onDestroy()
    }

    internal fun requireAuthorized(sessionId: String) {
        DeviceAccessibilityActions.requireAuthorizedAgent(applicationContext, sessionId)
    }

    internal fun readScreen(sessionId: String): String {
        requireAuthorized(sessionId)
        val window = activeWindow()
        val snapshotId = UUID.randomUUID().toString().take(10)
        val targets = linkedMapOf<Int, Target>()
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<Visit>()
        queue.addLast(Visit(window.root, emptyList()))
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES && targets.size < MAX_ELEMENTS) {
            val visit = queue.removeFirst()
            val node = visit.node
            visited++

            if (node.isVisibleToUser) {
                val label = label(node)
                val relevant = label.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable
                if (relevant) {
                    val elementId = targets.size + 1
                    targets[elementId] = Target(visit.path, fingerprint(node))
                    val flags = buildList {
                        if (node.isClickable) add("clickable")
                        if (node.isEditable) add("editable")
                        if (node.isScrollable) add("scrollable")
                        if (node.isPassword) add("password hidden")
                    }.joinToString(", ")
                    val className = node.className?.toString()?.substringAfterLast('.')?.take(50).orEmpty()
                    lines += "[$elementId] ${className.ifBlank { "element" }}${if (label.isBlank()) "" else " · $label"}${if (flags.isBlank()) "" else " · $flags"}"
                }
            }

            if (visit.path.size < MAX_TREE_DEPTH) {
                val childCount = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
                for (index in 0 until childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    queue.addLast(Visit(child, visit.path + index))
                }
            }
        }

        snapshots.put(sessionId, Snapshot(
            id = snapshotId,
            ownerSessionId = sessionId,
            createdAt = SystemClock.elapsedRealtime(),
            packageName = window.packageName,
            windowId = window.id,
            targets = targets,
        ))
        val header = "Tela acessível no app ${window.packageName}. snapshot_id=$snapshotId. IDs temporários (máx. ${SNAPSHOT_TTL_MS / 1000}s); releia após cada ação ou mudança observável."
        val body = if (lines.isEmpty()) "Nenhum controle/texto acessível foi encontrado nesta tela." else lines.joinToString("\n")
        return (header + "\n" + body).take(MAX_SCREEN_OUTPUT_CHARS)
    }

    internal fun click(sessionId: String, snapshotId: String, elementId: Int): String {
        requireAuthorized(sessionId)
        val located = locate(sessionId, snapshotId, elementId)
        var node: AccessibilityNodeInfo? = located.node
        repeat(MAX_CLICK_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                snapshots.remove(sessionId)
                return "Toque executado no elemento $elementId. Releia a tela antes da próxima ação."
            }
            node = current.parent
        }

        val bounds = Rect().also(located.node::getBoundsInScreen)
        if (dispatchTap(bounds, located.windowBounds)) {
            snapshots.remove(sessionId)
            return "Toque por gesto enviado ao elemento $elementId. Releia a tela antes da próxima ação."
        }
        error("O elemento $elementId não aceitou toque; releia a tela ou peça ao usuário para concluir manualmente.")
    }

    internal fun typeText(sessionId: String, snapshotId: String, elementId: Int, text: String, hasSecureSecret: Boolean): String {
        requireAuthorized(sessionId)
        val node = locate(sessionId, snapshotId, elementId).node
        if (!AccessibilityInputPolicy.mayTypeIntoField(node.isPassword, hasSecureSecret)) {
            error("Campo de senha recusado: use ask_secure para fornecer a credencial com segurança.")
        }
        if (!node.isEditable && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            error("O elemento $elementId não é um campo editável acessível.")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val safeText = text.take(MAX_TYPED_TEXT_CHARS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            error("O app não permite preencher este campo por acessibilidade; não foi usado clipboard nem screenshot.")
        }
        snapshots.remove(sessionId)
        return "Texto definido no campo (${safeText.length} caracteres; conteúdo omitido). Releia a tela para conferir o estado."
    }

    internal fun scroll(sessionId: String, snapshotId: String, elementId: Int, direction: String): String {
        requireAuthorized(sessionId)
        val forward = when (direction.lowercase()) {
            "down", "baixo" -> true
            "up", "cima" -> false
            else -> error("Direção inválida; use up ou down.")
        }
        val located = locate(sessionId, snapshotId, elementId)
        var node: AccessibilityNodeInfo? = located.node
        repeat(MAX_SCROLL_ANCESTORS) {
            val current = node ?: return@repeat
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (current.isScrollable && current.performAction(action)) {
                snapshots.remove(sessionId)
                return if (forward) "Rolagem para baixo solicitada." else "Rolagem para cima solicitada."
            }
            node = current.parent
        }

        val bounds = Rect().also(located.node::getBoundsInScreen)
        if (dispatchScroll(bounds, located.windowBounds, forward)) {
            snapshots.remove(sessionId)
            return if (forward) "Gesto de rolagem para baixo enviado." else "Gesto de rolagem para cima enviado."
        }
        error("O elemento $elementId não permite rolagem por acessibilidade; releia a tela ou peça ao usuário para concluir manualmente.")
    }

    internal fun back(sessionId: String): String {
        requireAuthorized(sessionId)
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) error("O Android recusou a ação Voltar.")
        snapshots.remove(sessionId)
        return "Ação Voltar enviada. Releia a tela antes de continuar."
    }

    private fun activeWindow(): ActiveWindow {
        val appWindows = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val selected = appWindows.firstOrNull { it.isActive }
            ?: appWindows.firstOrNull { it.isFocused }
        val root = selected?.root ?: rootInActiveWindow
            ?: error("Não há janela ativa com conteúdo de acessibilidade disponível.")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank()) error("O Android não informou o app da janela ativa.")
        val bounds = Rect()
        if (selected != null) selected.getBoundsInScreen(bounds) else root.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            bounds.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        return ActiveWindow(packageName, selected?.id ?: -1, root, bounds)
    }

    private fun locate(sessionId: String, snapshotId: String, elementId: Int): LocatedTarget {
        val saved = snapshots[sessionId]
            ?: error("Snapshot ausente ou invalidado; nenhuma ação foi executada. Execute device_accessibility_read_screen novamente e use somente os IDs novos.")
        if (saved.ownerSessionId != sessionId) {
            error("Snapshot pertence a outra tarefa; execute device_accessibility_read_screen nesta tarefa antes da ação.")
        }
        if (saved.id != snapshotId || SystemClock.elapsedRealtime() - saved.createdAt > SNAPSHOT_TTL_MS) {
            snapshots.remove(sessionId)
            error("snapshot_id expirado ou diferente da tela atual; execute read_screen novamente.")
        }
        val target = saved.targets[elementId] ?: error("element_id desconhecido neste snapshot; execute read_screen novamente.")
        val window = activeWindow()
        if (window.packageName != saved.packageName || (saved.windowId >= 0 && window.id != saved.windowId)) {
            snapshots.remove(sessionId)
            error("A janela mudou desde a leitura; execute read_screen novamente.")
        }
        var node = window.root
        for (index in target.path) {
            node = node.getChild(index) ?: error("A árvore de controles mudou; execute read_screen novamente.")
        }
        if (!node.isVisibleToUser || fingerprint(node) != target.fingerprint) {
            snapshots.remove(sessionId)
            error("O controle mudou desde a leitura; execute read_screen novamente.")
        }
        return LocatedTarget(node, window.bounds)
    }

    private fun dispatchTap(rawBounds: Rect, windowBounds: Rect): Boolean {
        val bounds = intersectVisible(rawBounds, windowBounds) ?: return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, mainHandler)
    }

    private fun dispatchScroll(rawBounds: Rect, windowBounds: Rect, forward: Boolean): Boolean {
        val bounds = intersectVisible(rawBounds, windowBounds) ?: return false
        if (bounds.height() < MIN_GESTURE_SIZE_PX || bounds.width() < MIN_GESTURE_SIZE_PX) return false
        val x = bounds.exactCenterX()
        val fromY = if (forward) bounds.top + bounds.height() * 0.78f else bounds.top + bounds.height() * 0.22f
        val toY = if (forward) bounds.top + bounds.height() * 0.22f else bounds.top + bounds.height() * 0.78f
        val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
            .build()
        return dispatchGesture(gesture, null, mainHandler)
    }

    private fun intersectVisible(rawBounds: Rect, windowBounds: Rect): Rect? {
        val visible = Rect(rawBounds)
        if (!visible.intersect(windowBounds) || visible.isEmpty) return null
        return visible
    }

    private fun label(node: AccessibilityNodeInfo): String {
        if (node.isPassword) return "[conteúdo de senha oculto]"
        return listOfNotNull(
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank),
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank),
            node.hintText?.toString()?.trim()?.takeIf(String::isNotBlank)?.let { "hint: $it" },
        ).distinct().joinToString(" · ").replace('\n', ' ').take(MAX_LABEL_CHARS)
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String = listOf(
        node.className?.toString().orEmpty(),
        node.viewIdResourceName.orEmpty(),
        if (node.isPassword) "[password]" else node.text?.toString().orEmpty(),
        if (node.isPassword) "[password]" else node.contentDescription?.toString().orEmpty(),
    ).joinToString("\u0000")

    private companion object {
        const val MAX_VISITED_NODES = 500
        const val MAX_ELEMENTS = 80
        const val MAX_TREE_DEPTH = 18
        const val MAX_CHILDREN_PER_NODE = 100
        const val MAX_CLICK_ANCESTORS = 5
        const val MAX_SCROLL_ANCESTORS = 10
        const val MAX_LABEL_CHARS = 120
        const val MAX_SCREEN_OUTPUT_CHARS = 6_000
        const val MAX_TYPED_TEXT_CHARS = 2_000
        const val SNAPSHOT_TTL_MS = 30_000L
        const val MIN_GESTURE_SIZE_PX = 24
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
