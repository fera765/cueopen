package dev.fluwy.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.Display
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private data class LocatedTarget(
        val node: AccessibilityNodeInfo,
        val windowBounds: Rect,
        val root: AccessibilityNodeInfo,
        val windowId: Int,
    )

    private val snapshots = AccessibilitySnapshotStore<Snapshot>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
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

    /**
     * Apps de banco (Nubank e outros) bloqueiam a abertura quando há um serviço de acessibilidade de fora da loja ligado
     * ("app perigoso"). Ao detectar um deles em primeiro plano, o serviço se desliga sozinho e avisa como religar.
     * O Android não permite esconder um serviço ligado, então desligar é a única forma de o banco abrir.
     */
    private fun bankGuard(pkg: String?) {
        if (pkg == null || !BankApps.isBank(pkg) || !BankApps.autoOff(this)) return
        runCatching { BankApps.notifyDisabled(this, pkg) }
        runCatching { AccessibilityAutomationBridge.disconnect(this) }
        disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Conteúdo/foco gera eventos benignos frequentes. Em uma transição, retenha snapshots da
        // janela que realmente está ativa; locate() revalida árvore, alvo e fingerprint antes de agir.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            bankGuard(event.packageName?.toString())
            val current = runCatching { activeWindow() }.getOrNull() ?: return
            snapshots.removeWhere { saved ->
                !AccessibilitySnapshotPolicy.keepAfterWindowEvent(
                    saved.packageName, saved.windowId, current.packageName, current.id,
                )
            }
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
        val snapshotId = UUID.randomUUID().toString()
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
        val header = "Tela acessível no app ${window.packageName}. snapshot_id=$snapshotId. IDs temporários (máx. ${SNAPSHOT_TTL_MS / 1000}s); execute uma ação por vez e releia após cada mudança."
        val body = if (lines.isEmpty()) "Nenhum controle/texto acessível foi encontrado nesta tela." else lines.joinToString("\n")
        return (header + "\n" + body).take(MAX_SCREEN_OUTPUT_CHARS)
    }

    /** Captura em memória só o recorte do elemento solicitado; nunca grava a imagem em disco/chat. */
    @android.annotation.SuppressLint("NewApi")
    internal suspend fun captureImageForInspection(sessionId: String, snapshotId: String, elementId: Int): ByteArray {
        requireAuthorized(sessionId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !AccessibilitySnapshotPolicy.supportsDisplayScreenshot(Build.VERSION.SDK_INT)) {
            error("A captura visual por acessibilidade exige Android 11 (API 30) ou superior.")
        }
        val target = locate(sessionId, snapshotId, elementId)
        val rawBounds = Rect().also(target.node::getBoundsInScreen)
        if (rawBounds.isEmpty) error("O elemento pedido não tem limites visuais disponíveis.")
        val windowManager = getSystemService(android.view.WindowManager::class.java)
            ?: error("O Android não disponibilizou o serviço de janelas para a captura.")
        val displaySize = android.graphics.Point().also { point ->
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            display.getRealSize(point)
        }
        if (displaySize.x <= 0 || displaySize.y <= 0) error("O tamanho do display não está disponível.")
        val screenBounds = Rect(0, 0, displaySize.x, displaySize.y)
        val targetOnScreen = Rect(rawBounds)
        if (!targetOnScreen.intersect(screenBounds) || targetOnScreen.isEmpty) {
            error("O elemento relevante está fora do display atual.")
        }
        if (targetOnScreen.width().toLong() * targetOnScreen.height() >=
            displaySize.x.toLong() * displaySize.y * MAX_TARGET_SCREEN_RATIO) {
            error("O elemento cobre quase todo o display; a captura ampla foi recusada para evitar conteúdo alheio.")
        }
        val paddingX = (targetOnScreen.width() * CROP_PADDING_RATIO).toInt().coerceAtMost(MAX_CROP_PADDING_PX)
        val paddingY = (targetOnScreen.height() * CROP_PADDING_RATIO).toInt().coerceAtMost(MAX_CROP_PADDING_PX)
        val cropOnScreen = Rect(targetOnScreen).apply { inset(-paddingX, -paddingY) }
        if (!cropOnScreen.intersect(screenBounds) || cropOnScreen.isEmpty) {
            error("Não foi possível delimitar o recorte visual solicitado.")
        }
        if (hasOverlappingOtherWindow(cropOnScreen, target.windowId)) {
            error("Uma janela/overlay do sistema cobre o elemento; captura recusada para não incluir notificações ou telas alheias.")
        }

        val result = takeDisplayScreenshot()
        try {
            val active = runCatching { activeWindow() }.getOrNull()
            val saved = snapshots[sessionId]
            if (active == null || saved == null || active.packageName != saved.packageName ||
                (saved.windowId >= 0 && active.id >= 0 && active.id != saved.windowId) ||
                hasOverlappingOtherWindow(cropOnScreen, saved.windowId)) {
                error("A janela mudou ou recebeu uma sobreposição durante a captura; a imagem foi descartada.")
            }

            val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                ?: error("O Android não conseguiu abrir o buffer da captura.")
            try {
                val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    ?: error("O Android não conseguiu preparar o recorte visual.")
                try {
                    return encodeTargetCrop(bitmap, cropOnScreen, displaySize, target.root)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                hardwareBitmap.recycle()
            }
        } finally {
            runCatching { result.hardwareBuffer.close() }
            snapshots.remove(sessionId)
        }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun takeDisplayScreenshot(): AccessibilityService.ScreenshotResult = suspendCancellableCoroutine { continuation ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { command -> mainHandler.post(command) },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) continuation.resume(screenshot)
                        else screenshot.hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException(screenshotFailure(errorCode)),
                        )
                    }
                })
        } catch (t: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(t)
        }
    }

    private fun screenshotFailure(errorCode: Int): String = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "O Android bloqueou a captura porque há conteúdo marcado como seguro."
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "O serviço de acessibilidade não tem autorização de captura ativa."
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "O Android limitou a frequência de screenshots; não vou repetir automaticamente."
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "O display solicitado não está disponível."
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "O Android não conseguiu capturar o display (erro interno)."
        else -> "O Android recusou a captura visual (código $errorCode)."
    }

    private fun hasOverlappingOtherWindow(region: Rect, targetWindowId: Int): Boolean = runCatching {
        windows.orEmpty().any { window ->
            if (targetWindowId >= 0 && window.id == targetWindowId) return@any false
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            bounds.intersect(region) && !bounds.isEmpty
        }
    }.getOrDefault(true)

    private fun encodeTargetCrop(bitmap: Bitmap, cropOnScreen: Rect, displaySize: android.graphics.Point, root: AccessibilityNodeInfo): ByteArray {
        val scaleX = bitmap.width.toFloat() / displaySize.x
        val scaleY = bitmap.height.toFloat() / displaySize.y
        val crop = Rect(
            (cropOnScreen.left * scaleX).toInt(), (cropOnScreen.top * scaleY).toInt(),
            (cropOnScreen.right * scaleX).toInt(), (cropOnScreen.bottom * scaleY).toInt(),
        ).apply { intersect(0, 0, bitmap.width, bitmap.height) }
        if (crop.width() < MIN_CAPTURE_SIZE_PX || crop.height() < MIN_CAPTURE_SIZE_PX) {
            error("O recorte visual é pequeno demais para uma análise confiável.")
        }

        val canvas = Canvas(bitmap)
        val mask = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val passwords = ArrayDeque<AccessibilityNodeInfo>()
        passwords.addLast(root)
        var checked = 0
        while (passwords.isNotEmpty() && checked++ < MAX_VISITED_NODES) {
            val node = passwords.removeFirst()
            if (node.isVisibleToUser && node.isPassword) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val masked = Rect(
                    (bounds.left * scaleX).toInt(), (bounds.top * scaleY).toInt(),
                    (bounds.right * scaleX).toInt(), (bounds.bottom * scaleY).toInt(),
                )
                if (masked.intersect(crop) && !masked.isEmpty) canvas.drawRect(masked, mask)
            }
            for (index in 0 until node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)) {
                runCatching { node.getChild(index) }.getOrNull()?.let(passwords::addLast)
            }
        }

        val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
        try {
            val scale = minOf(1f, MAX_CAPTURE_EDGE_PX.toFloat() / maxOf(cropped.width, cropped.height))
            val output = if (scale < 1f) Bitmap.createScaledBitmap(
                cropped, (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1), true,
            ) else cropped
            try {
                val bytes = java.io.ByteArrayOutputStream().use { stream ->
                    if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        error("Não foi possível preparar a imagem para análise.")
                    }
                    stream.toByteArray()
                }
                if (bytes.isEmpty() || bytes.size > MAX_CAPTURE_BYTES) {
                    error("O recorte visual excedeu o limite de tamanho; não foi enviado ao modelo.")
                }
                return bytes
            } finally {
                if (output !== cropped) output.recycle()
            }
        } finally {
            cropped.recycle()
        }
    }

    /**
     * Rótulo do controle (texto/descrição, ou do primeiro ancestral clicável que tenha texto) sem tocar nele.
     * Usado pelo portão de confirmação. Retorna (pacote do app, rótulo).
     */
    internal fun describe(sessionId: String, snapshotId: String, elementId: Int): Pair<String, String> {
        requireAuthorized(sessionId)
        val located = locate(sessionId, snapshotId, elementId)
        fun label(n: AccessibilityNodeInfo?): String =
            (n?.text?.toString()?.takeIf { it.isNotBlank() } ?: n?.contentDescription?.toString()).orEmpty().trim()
        var node: AccessibilityNodeInfo? = located.node
        var text = label(node)
        var depth = 0
        while (text.isBlank() && node != null && depth < MAX_CLICK_ANCESTORS) { node = node.parent; text = label(node); depth++ }
        if (text.isBlank()) {
            // botão de ícone: usa o texto do primeiro filho com texto
            val n = located.node
            for (i in 0 until n.childCount) { text = label(n.getChild(i)); if (text.isNotBlank()) break }
        }
        return (snapshots[sessionId]?.packageName ?: "") to text.take(90)
    }

    internal suspend fun click(sessionId: String, snapshotId: String, elementId: Int): String {
        requireAuthorized(sessionId)
        val located = locate(sessionId, snapshotId, elementId)
        var node: AccessibilityNodeInfo? = located.node
        repeat(MAX_CLICK_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                snapshots.remove(sessionId)
                delay(ACTION_SETTLE_MS)
                return "Toque executado no elemento $elementId. Releia a tela antes da próxima ação."
            }
            node = current.parent
        }

        val bounds = Rect().also(located.node::getBoundsInScreen)
        if (dispatchTap(sessionId, bounds, located.windowBounds)) {
            snapshots.remove(sessionId)
            delay(ACTION_SETTLE_MS)
            return "Toque por gesto enviado ao elemento $elementId. Releia a tela antes da próxima ação."
        }
        error("O elemento $elementId não aceitou toque; releia a tela ou peça ao usuário para concluir manualmente.")
    }

    internal suspend fun typeText(sessionId: String, snapshotId: String, elementId: Int, text: String, hasSecureSecret: Boolean): String {
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
        delay(TYPE_SETTLE_MS)
        return "Texto definido no campo (${safeText.length} caracteres; conteúdo omitido). Releia a tela para conferir o estado."
    }

    internal suspend fun scroll(sessionId: String, snapshotId: String, elementId: Int, direction: String): String {
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
                delay(SCROLL_SETTLE_MS)
                return if (forward) "Rolagem para baixo solicitada." else "Rolagem para cima solicitada."
            }
            node = current.parent
        }

        val bounds = Rect().also(located.node::getBoundsInScreen)
        if (dispatchScroll(sessionId, bounds, located.windowBounds, forward)) {
            snapshots.remove(sessionId)
            delay(SCROLL_SETTLE_MS)
            return if (forward) "Gesto de rolagem para baixo enviado." else "Gesto de rolagem para cima enviado."
        }
        error("O elemento $elementId não permite rolagem por acessibilidade; releia a tela ou peça ao usuário para concluir manualmente.")
    }

    internal suspend fun back(sessionId: String): String {
        requireAuthorized(sessionId)
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) error("O Android recusou a ação Voltar.")
        snapshots.remove(sessionId)
        delay(ACTION_SETTLE_MS)
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
        if (saved.id != snapshotId || AccessibilitySnapshotPolicy.isExpired(
                saved.createdAt, SystemClock.elapsedRealtime(), SNAPSHOT_TTL_MS,
            )) {
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
        return LocatedTarget(node, window.bounds, window.root, window.id)
    }

    private suspend fun dispatchTap(sessionId: String, rawBounds: Rect, windowBounds: Rect): Boolean {
        val bounds = intersectVisible(rawBounds, windowBounds) ?: return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGestureAndWait(sessionId, gesture)
    }

    private suspend fun dispatchScroll(sessionId: String, rawBounds: Rect, windowBounds: Rect, forward: Boolean): Boolean {
        val bounds = intersectVisible(rawBounds, windowBounds) ?: return false
        if (bounds.height() < MIN_GESTURE_SIZE_PX || bounds.width() < MIN_GESTURE_SIZE_PX) return false
        val x = bounds.exactCenterX()
        val fromY = if (forward) bounds.top + bounds.height() * 0.78f else bounds.top + bounds.height() * 0.22f
        val toY = if (forward) bounds.top + bounds.height() * 0.22f else bounds.top + bounds.height() * 0.78f
        val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
            .build()
        return dispatchGestureAndWait(sessionId, gesture)
    }

    private suspend fun dispatchGestureAndWait(sessionId: String, gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            var submitted = false
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    snapshots.remove(sessionId)
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("O Android cancelou o gesto; nenhuma próxima ação será tentada com o snapshot atual. Leia a tela novamente."),
                    )
                }
            }, mainHandler)
            submitted = accepted
            if (!accepted && continuation.isActive) continuation.resume(false)
            continuation.invokeOnCancellation {
                if (submitted) snapshots.remove(sessionId)
            }
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
        const val MAX_TARGET_SCREEN_RATIO = 0.98
        const val CROP_PADDING_RATIO = 0.08
        const val MAX_CROP_PADDING_PX = 96
        const val MIN_CAPTURE_SIZE_PX = 48
        const val MAX_CAPTURE_EDGE_PX = 1_024
        const val MAX_CAPTURE_BYTES = 1_000_000
        const val JPEG_QUALITY = 78
        const val ACTION_SETTLE_MS = 350L
        const val TYPE_SETTLE_MS = 250L
        const val SCROLL_SETTLE_MS = 450L
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
