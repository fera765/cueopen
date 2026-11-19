package dev.cueopen.app.triggers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.cueopen.app.CueApp
import dev.cueopen.app.connectors.HubConnectors
import dev.cueopen.app.data.Session
import dev.cueopen.app.notify.NotificationCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Gatilhos: acordam um agente/sessão quando algo acontece numa fonte de eventos. Genérico: a fonte é qualquer ação de
 * leitura de um conector do hub (ex.: buscar e-mails, listar mensagens, linhas novas, eventos da agenda) ou uma página
 * web (mudança de conteúdo). Cada verificação compara com o que já foi visto (deduplicação por id/hash), só eventos
 * novos acordam a sessão existente — preservando o contexto — e a 1ª verificação só marca a linha de base.
 */
@Serializable
data class Trigger(
    val id: String = java.util.UUID.randomUUID().toString().take(12),
    val name: String,
    val sessionId: String,
    val agentId: String? = null,
    /** "connector" ou "site". */
    val source: String,
    val piece: String = "",
    val action: String = "",
    val input: String = "{}",
    val connection: String = "",
    val url: String = "",
    /** Palavras que o evento precisa conter (vazio = qualquer). */
    val filter: String = "",
    /** O que o agente deve fazer com o evento. */
    val instruction: String = "",
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val baselineDone: Boolean = false,
    val seen: List<String> = emptyList(),
    val lastRunAt: Long? = null,
    val lastEventAt: Long? = null,
    val lastError: String? = null,
    val errorStreak: Int = 0,
    val history: List<TriggerEvent> = emptyList(),
)

@Serializable
data class TriggerEvent(val at: Long, val count: Int, val note: String)

object TriggerStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow<List<Trigger>>(emptyList())
    val all: StateFlow<List<Trigger>> = _all
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fluwy_triggers", Context.MODE_PRIVATE)

    @Synchronized fun load(ctx: Context): List<Trigger> {
        if (!loaded) {
            _all.value = runCatching { json.decodeFromString(ListSerializer(Trigger.serializer()), prefs(ctx).getString("t", "[]")!!) }.getOrDefault(emptyList())
            loaded = true
        }
        return _all.value
    }

    @Synchronized fun save(ctx: Context, list: List<Trigger>) {
        _all.value = list
        prefs(ctx).edit().putString("t", json.encodeToString(ListSerializer(Trigger.serializer()), list)).commit()
    }

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }
    fun upsert(ctx: Context, t: Trigger) = save(ctx, load(ctx).filterNot { it.id == t.id } + t)
    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.id == id })
}

/** Regras puras (testáveis): extrair itens de uma saída, achar os novos, filtrar. */
object TriggerLogic {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** JSON que vem depois do cabeçalho de conector_executar ("✅ … concluída (…).\n\n<JSON>"). */
    fun payload(output: String): JsonElement? {
        val start = output.indexOfFirst { it == '{' || it == '[' }.takeIf { it >= 0 } ?: return null
        val el = runCatching { json.parseToJsonElement(output.substring(start).trim()) }.getOrNull()
        // Saída de conector_executar ({status, summary, data}): os itens estão em "data".
        val o = el as? JsonObject
        if (o != null && "status" in o && "data" in o) return o["data"]
        return el
    }

    /** Lista de itens: o maior array do resultado (messages, items, data…) ou o próprio objeto. */
    fun items(el: JsonElement?): List<JsonElement> = when (el) {
        null -> emptyList()
        is JsonArray -> el.toList()
        is JsonObject -> el.values.filterIsInstance<JsonArray>().maxByOrNull { it.size }?.toList()
            ?: el.values.filterIsInstance<JsonObject>().firstNotNullOfOrNull { o -> o.values.filterIsInstance<JsonArray>().maxByOrNull { it.size } }?.toList()
            ?: listOf(el)
        else -> listOf(el)
    }

    /** Id estável de um item: campo id/messageId/uid… quando existe, senão o hash do conteúdo. */
    fun itemKey(e: JsonElement): String {
        val o = e as? JsonObject
        val id = listOf("id", "messageId", "message_id", "uid", "threadId", "key", "url", "link").firstNotNullOfOrNull { k -> (o?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        return id?.let { "id:$it" } ?: "h:" + sha(e.toString())
    }

    fun matches(e: JsonElement, filter: String): Boolean {
        val words = filter.lowercase().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = e.toString().lowercase()
        return words.any { text.contains(it) }
    }

    data class Diff(val fresh: List<JsonElement>, val seen: List<String>)

    /** Novos itens desde a última vez (mantém os 500 ids mais recentes). */
    fun diff(items: List<JsonElement>, seen: List<String>, filter: String): Diff {
        val known = seen.toHashSet()
        val keyed = items.map { itemKey(it) to it }
        val fresh = keyed.filter { (k, e) -> k !in known && matches(e, filter) }.map { it.second }
        return Diff(fresh, (keyed.map { it.first } + seen).distinct().take(500))
    }
}

object TriggerEngine {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun work(id: String) = "trigger-$id"

    fun schedule(ctx: Context, t: Trigger) {
        val wm = WorkManager.getInstance(ctx)
        if (!t.enabled) { wm.cancelUniqueWork(work(t.id)); return }
        wm.enqueueUniquePeriodicWork(work(t.id), ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TriggerWorker>(t.intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("id" to t.id)).build())
    }

    fun cancel(ctx: Context, id: String) = WorkManager.getInstance(ctx).cancelUniqueWork(work(id))

    fun runNow(ctx: Context, id: String) = WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<TriggerWorker>().setInputData(workDataOf("id" to id)).build())

    /** Uma verificação. Devolve um resumo legível do que aconteceu. */
    suspend fun check(app: CueApp, id: String): String = locks.getOrPut(id) { Mutex() }.withLock {
        val t = TriggerStore.get(app, id) ?: return "gatilho não existe"
        if (!t.enabled) return "desativado"
        val now = System.currentTimeMillis()
        val result = runCatching {
            when (t.source) {
                "site" -> {
                    val text = dev.cueopen.app.web.WebTools.fetch(t.url, 20_000)
                    if (text.startsWith("ERRO")) error(text.take(200))
                    listOf(JsonPrimitive(text.take(20_000)))
                }
                else -> {
                    val input = runCatching { Json.parseToJsonElement(t.input) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                    // Gatilho precisa da lista inteira (ids) para achar o que é novo: payload completo.
                    val out = HubConnectors.run(app.store, t.piece, t.action, input, t.connection, full = true)
                    if (out.startsWith("ERRO") || out.trimStart().startsWith("❌") || out.trimStart().startsWith("⏳")) error(out.take(300))
                    TriggerLogic.items(TriggerLogic.payload(out))
                }
            }
        }
        val items = result.getOrElse { e ->
            val streak = t.errorStreak + 1
            TriggerStore.upsert(app, t.copy(lastRunAt = now, lastError = e.message?.take(300), errorStreak = streak,
                history = (listOf(TriggerEvent(now, 0, "erro: ${e.message?.take(120)}")) + t.history).take(20)))
            if (streak == 3) NotificationCenter.post("Gatilho com falha: ${t.name}", "3 verificações seguidas falharam: ${e.message?.take(160)}",
                NotificationCenter.Priority.NORMAL, NotificationCenter.Category.FAILURE, t.sessionId, dedupeKey = "trigger-fail|${t.id}")
            return "erro: ${e.message}"
        }
        val d = TriggerLogic.diff(items, t.seen, t.filter)
        if (!t.baselineDone) {
            TriggerStore.upsert(app, t.copy(seen = d.seen, baselineDone = true, lastRunAt = now, lastError = null, errorStreak = 0,
                history = (listOf(TriggerEvent(now, 0, "linha de base: ${items.size} item(ns) já existentes")) + t.history).take(20)))
            return "linha de base registrada (${items.size} itens)"
        }
        TriggerStore.upsert(app, t.copy(seen = d.seen, lastRunAt = now, lastError = null, errorStreak = 0,
            lastEventAt = if (d.fresh.isNotEmpty()) now else t.lastEventAt,
            history = if (d.fresh.isEmpty()) t.history else (listOf(TriggerEvent(now, d.fresh.size, "${d.fresh.size} novo(s)")) + t.history).take(20)))
        if (d.fresh.isEmpty()) return "sem novidades"
        wake(app, t, d.fresh)
        return "${d.fresh.size} evento(s) novo(s): sessão acordada"
    }

    /** Acorda a sessão do gatilho (a existente; só cria se ela foi apagada) com os eventos novos. */
    private fun wake(app: CueApp, t: Trigger, fresh: List<JsonElement>) {
        var sid = t.sessionId
        if (app.store.session(sid) == null) {
            val s = Session(title = "⚡ ${t.name}", agentId = t.agentId)
            app.store.upsertSession(s); sid = s.id
            TriggerStore.upsert(app, (TriggerStore.get(app, t.id) ?: t).copy(sessionId = sid))
        }
        val body = fresh.take(10).joinToString("\n") { it.toString().take(1_500) }
        val src = if (t.source == "site") "a página ${t.url} mudou" else "${fresh.size} evento(s) novo(s) em ${HubConnectors.appName(dev.cueopen.app.connectors.HubApi.pieceName(t.piece))} (${t.action})"
        val msg = "[Gatilho · ${t.name}] $src.\n$body" + (if (fresh.size > 10) "\n… e mais ${fresh.size - 10}." else "") +
            "\n\nO que fazer: ${t.instruction.ifBlank { "analise e me diga se precisa de algo." }}\n" +
            "Ações com efeito externo (enviar, responder, comprar, apagar) só depois da minha aprovação."
        app.agent.sendOrQueue(sid, msg)
        NotificationCenter.post("⚡ ${t.name}", src, NotificationCenter.Priority.LOW, NotificationCenter.Category.TRIGGER, sid, dedupeKey = "trigger|${t.id}|${TriggerLogic.sha(body)}")
    }
}

class TriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CueApp
        if (dev.cueopen.app.AppExit.exited(app)) return Result.success()
        val id = inputData.getString("id") ?: return Result.failure()
        TriggerEngine.check(app, id)
        return Result.success()
    }
}
