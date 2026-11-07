package dev.cueopen.app.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cueopen.app.CueApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Narrow action API used by AgentRunner; no model-supplied agent ID is accepted. */
object DeviceAccessibilityActions {
    internal fun requireAuthorizedAgent(context: Context, sessionId: String) {
        val app = context.applicationContext as? CueApp
            ?: error("A sessão Fluwy não está disponível para autorizar acessibilidade.")
        val session = app.store.session(sessionId)
            ?: error("A tarefa não existe mais; acessibilidade foi recusada.")
        val agent = app.store.agent(session.agentId)
            ?: error("Esta tarefa não pertence a um Agent com acesso individualizado.")
        if (DeviceCaps.ACCESSIBILITY !in agent.deviceCaps) {
            error("Este Agent não ativou Navegar em outros apps.")
        }
        if (!DeviceCaps.granted(context, DeviceCaps.ACCESSIBILITY)) {
            error("O serviço de acessibilidade está desligado nas Configurações do Android.")
        }
    }

    suspend fun openApp(context: Context, sessionId: String, requestedName: String): String {
        requireAuthorizedAgent(context, sessionId)
        val query = requestedName.trim().take(MAX_APP_QUERY_CHARS)
        if (query.isBlank()) error("Informe o nome ou o pacote do aplicativo.")
        val app = withContext(Dispatchers.Main.immediate) {
            requireAuthorizedAgent(context, sessionId)
            findLauncherApp(context.applicationContext, query)
        }
        withContext(Dispatchers.Main.immediate) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.packageName, app.className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(intent)
        }
        delay(APP_OPEN_SETTLE_MS)
        return "Abri ${app.label}. Use device_accessibility_read_screen para inspecionar a tela atual."
    }

    suspend fun readScreen(sessionId: String): String = AccessibilityAutomationBridge.execute(sessionId) {
        it.readScreen(sessionId)
    }

    suspend fun inspectImage(sessionId: String, snapshotId: String, elementId: Int): ByteArray {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            error("A captura visual por acessibilidade exige Android 11 (API 30) ou superior.")
        }
        return AccessibilityAutomationBridge.execute(sessionId, IMAGE_CAPTURE_TIMEOUT_MS) {
            it.captureImageForInspection(sessionId, snapshotId, elementId)
        }
    }

    /** (pacote do app, rótulo) do controle, sem tocar nele — para o portão de confirmação. */
    suspend fun describe(sessionId: String, snapshotId: String, elementId: Int): Pair<String, String> =
        AccessibilityAutomationBridge.execute(sessionId) { it.describe(sessionId, snapshotId, elementId) }

    suspend fun click(sessionId: String, snapshotId: String, elementId: Int): String =
        AccessibilityAutomationBridge.execute(sessionId) { it.click(sessionId, snapshotId, elementId) }

    suspend fun typeText(sessionId: String, snapshotId: String, elementId: Int, text: String, hasSecureSecret: Boolean): String =
        AccessibilityAutomationBridge.execute(sessionId) { it.typeText(sessionId, snapshotId, elementId, text, hasSecureSecret) }

    suspend fun scroll(sessionId: String, snapshotId: String, elementId: Int, direction: String): String =
        AccessibilityAutomationBridge.execute(sessionId) { it.scroll(sessionId, snapshotId, elementId, direction) }

    suspend fun back(sessionId: String): String =
        AccessibilityAutomationBridge.execute(sessionId) { it.back(sessionId) }

    private data class LauncherApp(val packageName: String, val className: String, val label: String)

    private fun findLauncherApp(context: Context, query: String): LauncherApp {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(launchIntent, 0) }
            .getOrDefault(emptyList())
            .mapNotNull { resolve ->
                val activity = resolve.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName) return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(activity.applicationInfo).toString() }
                    .getOrDefault(activity.packageName)
                LauncherApp(activity.packageName, activity.name, label)
            }
            .distinctBy(LauncherApp::packageName)
        val exact = apps.filter {
            it.label.equals(query, ignoreCase = true) || it.packageName.equals(query, ignoreCase = true)
        }
        val candidates = if (exact.isNotEmpty()) exact else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        if (candidates.isEmpty()) error("Não encontrei um app instalado que corresponda a '$query'.")
        if (candidates.size > 1) {
            val labels = candidates.take(MAX_AMBIGUOUS_APPS).joinToString(", ") { "${it.label} (${it.packageName})" }
            error("Há mais de um app correspondente: $labels. Informe o nome exato.")
        }
        return candidates.single()
    }

    private const val MAX_APP_QUERY_CHARS = 120
    private const val MAX_AMBIGUOUS_APPS = 5
    private const val APP_OPEN_SETTLE_MS = 650L
    private const val IMAGE_CAPTURE_TIMEOUT_MS = 20_000L
}

/** One-at-a-time bridge: validates the current session's Agent again on the Android main thread. */
internal object AccessibilityAutomationBridge {
    private val connectedService = MutableStateFlow<FluwyAccessibilityService?>(null)
    private val actionMutex = Mutex()

    fun connect(service: FluwyAccessibilityService) {
        connectedService.value = service
    }

    fun disconnect(service: FluwyAccessibilityService) {
        if (connectedService.value === service) connectedService.value = null
    }

    suspend fun <T> execute(
        sessionId: String,
        timeoutMs: Long = ACTION_TIMEOUT_MS,
        action: suspend (FluwyAccessibilityService) -> T,
    ): T {
        val service = connectedService.value ?: withTimeoutOrNull(SERVICE_WAIT_TIMEOUT_MS) {
            connectedService.filterNotNull().first()
        } ?: error("Acessibilidade ainda não está conectada. Verifique Configurações do Android e tente novamente.")

        return withTimeout(timeoutMs) {
            actionMutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    if (connectedService.value !== service) error("O serviço de acessibilidade foi desconectado.")
                    service.requireAuthorized(sessionId)
                    action(service)
                }
            }
        }
    }

    private const val SERVICE_WAIT_TIMEOUT_MS = 4_000L
    private const val ACTION_TIMEOUT_MS = 12_000L
}
