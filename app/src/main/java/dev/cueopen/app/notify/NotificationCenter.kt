package dev.cueopen.app.notify

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.cueopen.app.MainActivity
import dev.cueopen.app.SessionNotificationRouting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Central de notificações: toda notificação do app passa por aqui. Classifica por prioridade/categoria, registra no
 * histórico, evita repetição (deduplicação + limite de frequência), decide se interrompe o usuário e abre a sessão
 * certa ao tocar. Prioridade SILENT só registra no histórico (monitoramentos sem novidade não incomodam).
 */
object NotificationCenter {
    enum class Priority { SILENT, LOW, NORMAL, HIGH, CRITICAL }
    enum class Category(val label: String) {
        APPROVAL("Aprovação"), QUESTION("Pergunta"), SECURITY("Segurança"), FAILURE("Falha"), DONE("Concluído"), MONITOR("Monitoramento"), TRIGGER("Gatilho")
    }

    @Serializable
    data class Entry(val id: Long, val at: Long, val title: String, val text: String, val priority: String, val category: String,
                     val sessionId: String? = null, val agent: String = "", val needsAction: Boolean = false, val read: Boolean = false)

    const val CH_ATTENTION = "attention"
    const val CH_ALERTS = "alerts"
    const val CH_SILENT = "silent"
    private const val MAX_HISTORY = 300
    private const val DEDUPE_MS = 10 * 60_000L
    private const val SESSION_GAP_MS = 30_000L

    private lateinit var app: Application
    private val json = Json { ignoreUnknownKeys = true }
    private val _history = MutableStateFlow<List<Entry>>(emptyList())
    val history: StateFlow<List<Entry>> = _history
    private val lastByKey = HashMap<String, Long>()
    private val lastBySession = HashMap<String, Long>()
    @Volatile private var startedActivities = 0

    val foreground: Boolean get() = startedActivities > 0

    fun init(application: Application) {
        app = application
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Precisa de você (aprovações e perguntas)", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Aprovações de pagamento, compra, envio, perguntas do agente e pedidos de segurança." })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alertas e conclusões", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SILENT, "Discretas (monitoramento)", NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null); enableVibration(false) })
        _history.value = load()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { startedActivities++ }
            override fun onActivityStopped(a: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun prefs() = app.getSharedPreferences("fluwy_notifications", Context.MODE_PRIVATE)
    private fun load(): List<Entry> = runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), prefs().getString("h", "[]")!!) }.getOrDefault(emptyList())
    private fun save(list: List<Entry>) = runCatching { prefs().edit().putString("h", json.encodeToString(ListSerializer(Entry.serializer()), list)).apply() }

    /** Decisão pura (testável): mostrar no sistema? (histórico sempre registra, exceto duplicata recente). */
    fun decide(p: Priority, foreground: Boolean, lastSameKeyAt: Long?, lastSessionAt: Long?, now: Long): Pair<Boolean, Boolean> {
        val duplicate = lastSameKeyAt != null && now - lastSameKeyAt < DEDUPE_MS
        if (duplicate) return false to false                     // (registrar, mostrar)
        val throttled = lastSessionAt != null && now - lastSessionAt < SESSION_GAP_MS && p < Priority.HIGH
        val show = when {
            p == Priority.SILENT -> false
            foreground && p < Priority.HIGH -> false              // app aberto: só no histórico, sem interromper
            throttled -> false
            else -> true
        }
        return true to show
    }

    /**
     * Publica (ou só registra) uma notificação. [dedupeKey] igual dentro de 10 min é ignorado (ex.: o mesmo pedido de
     * aprovação). Tocar abre [sessionId].
     */
    @Synchronized
    fun post(title: String, text: String, priority: Priority, category: Category, sessionId: String? = null, agent: String = "",
             dedupeKey: String = "$category|$sessionId|${text.take(80)}", needsAction: Boolean = priority >= Priority.HIGH) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        val (record, show) = decide(priority, foreground, lastByKey[dedupeKey], sessionId?.let { lastBySession[it] }, now)
        if (!record) return
        lastByKey[dedupeKey] = now
        if (lastByKey.size > 500) lastByKey.entries.removeIf { now - it.value > DEDUPE_MS }
        val entry = Entry(now, now, title.take(120), plain(text).take(600), priority.name, category.name, sessionId, agent, needsAction)
        _history.value = (listOf(entry) + _history.value).take(MAX_HISTORY).also { save(it) }
        if (!show) return
        sessionId?.let { lastBySession[it] = now }
        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = when (priority) { Priority.HIGH, Priority.CRITICAL -> CH_ATTENTION; Priority.NORMAL -> CH_ALERTS; else -> CH_SILENT }
        val key = sessionId ?: "app:$category"
        val stableId = SessionNotificationRouting.stableId("$key|$category")
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        SessionNotificationRouting.validSessionId(sessionId)?.let {
            intent.setAction(SessionNotificationRouting.ACTION_OPEN_SESSION)
                .putExtra(SessionNotificationRouting.EXTRA_SESSION, it).putExtra(SessionNotificationRouting.EXTRA_SESSION_ID, it)
                .setData(Uri.parse(SessionNotificationRouting.deepLink(it)))
        }
        val pi = PendingIntent.getActivity(app, stableId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = plain(text)
        val n = Notification.Builder(app, channel)
            .setSmallIcon(if (priority >= Priority.HIGH) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body.take(120)).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).setGroup("fluwy_${category.name}")
            .setCategory(if (category == Category.APPROVAL || category == Category.QUESTION || category == Category.SECURITY) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setNumber(pendingCount())
            .build()
        runCatching { nm.notify(stableId, n) }
    }

    fun pendingCount(): Int = _history.value.count { it.needsAction && !it.read }

    fun markRead(sessionId: String? = null) {
        _history.value = _history.value.map { if (sessionId == null || it.sessionId == sessionId) it.copy(read = true) else it }.also { save(it) }
        if (::app.isInitialized && sessionId != null) runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            Category.entries.forEach { nm.cancel(SessionNotificationRouting.stableId("$sessionId|$it")) }
        }
    }

    fun clear() { _history.value = emptyList(); save(emptyList()) }

    private fun plain(md: String) = md.replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("[*_`#>]"), "")
        .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1").replace(Regex("\\s+"), " ").trim()
}
