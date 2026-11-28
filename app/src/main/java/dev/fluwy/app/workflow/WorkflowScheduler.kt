package dev.fluwy.app.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Agenda os gatilhos de horário. Funciona com o app aberto, em segundo plano ou fechado:
 * - alarme exato do sistema (setExactAndAllowWhileIdle, acorda mesmo no Doze) → [WorkflowAlarmReceiver];
 * - reserva no WorkManager 90 s depois (caso o alarme seja perdido) — o disparo duplicado é descartado;
 * - último/próximo disparo persistidos ([WfScheduleState]): o intervalo não reinicia ao abrir o app e
 *   o card mostra sempre a última e a próxima execução.
 * Religado no boot (BootReceiver) e ao abrir o app (FluwyApp → syncAll).
 */
object WorkflowScheduler {
    private fun tag(wfId: String) = "wf_$wfId"
    private fun name(wfId: String, nodeId: String) = "wf_${wfId}_$nodeId"
    const val ACTION = "com.fluwy.bot.WORKFLOW_FIRE"

    private fun pi(ctx: Context, wfId: String, nodeId: String, create: Boolean = true): android.app.PendingIntent? {
        val i = android.content.Intent(ctx, WorkflowAlarmReceiver::class.java).setAction(ACTION)
            .putExtra("wf", wfId).putExtra("node", nodeId).setData(android.net.Uri.parse("fluwy://wf/$wfId/$nodeId"))
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or (if (create) android.app.PendingIntent.FLAG_UPDATE_CURRENT else android.app.PendingIntent.FLAG_NO_CREATE)
        return android.app.PendingIntent.getBroadcast(ctx, "$wfId/$nodeId".hashCode(), i, flags)
    }

    fun sync(ctx: Context, w: Workflow) {
        cancel(ctx, w)
        if (!w.enabled) return
        w.nodes.filter { it.type == "trigger_schedule" }.forEach { n -> scheduleNext(ctx, w.id, n, keepPending = true) }
        // Webhook: confere a issue a cada minuto (mesmo alarme exato + reserva do agendamento).
        w.nodes.filter { it.type == "trigger_webhook" }.forEach { n -> scheduleNext(ctx, w.id, n, keepPending = true) }
        // Mensagem no WhatsApp: publicar o fluxo liga o webhook do WhatsApp (escuta em tempo real).
        if (w.nodes.any { it.type == "trigger_whatsapp" }) runCatching {
            val c = dev.fluwy.app.browser.WhatsAppHub.config(ctx)
            if (!c.webhook) dev.fluwy.app.browser.WhatsAppHub.save(ctx, c.copy(webhook = true)) else dev.fluwy.app.browser.WhatsAppHub.start(ctx)
        }
    }

    private fun cancel(ctx: Context, w: Workflow) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(w.id))
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        w.nodes.forEach { n -> pi(ctx, w.id, n.id, create = false)?.let { am.cancel(it) } }
        if (!w.enabled) WfScheduleState.clearNext(ctx, w.id)
    }

    fun cancel(ctx: Context, wfId: String) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(wfId))
        WorkflowStore.get(wfId)?.let { w ->
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            w.nodes.forEach { n -> pi(ctx, wfId, n.id, create = false)?.let { am.cancel(it) } }
        }
        WfScheduleState.clearNext(ctx, wfId)
    }

    fun syncAll(ctx: Context) = WorkflowStore.flows.value.forEach { runCatching { sync(ctx, it) } }

    /**
     * Agenda o próximo disparo. [keepPending] = mantém um próximo já agendado (mesma config) que ainda está no
     * futuro — abrir o app ou reiniciar não empurra a agenda.
     */
    fun scheduleNext(ctx: Context, wfId: String, n: WfNode, keepPending: Boolean = false) {
        val now = System.currentTimeMillis()
        val sig = n.config.toSortedMap().toString()
        val st = WfScheduleState.get(ctx, wfId, n.id)
        val at = if (keepPending && st.sig == sig && st.next != null && st.next > now) st.next
            else WfLogic.nextFire(if (n.type == "trigger_webhook") mapOf("mode" to "interval", "minutes" to "1") else n.config, now, lastFire = st.last.takeIf { st.sig == sig }) ?: return
        WfScheduleState.setNext(ctx, wfId, n.id, at, sig)
        // 1) alarme exato
        runCatching {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val p = pi(ctx, wfId, n.id)!!
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p)
                else am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p)
            } catch (_: SecurityException) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p) }
        }
        // 2) reserva no WorkManager
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInitialDelay((at - now + 90_000L).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("wf" to wfId, "node" to n.id, "due" to at))
            .addTag(tag(wfId)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(wfId, n.id), ExistingWorkPolicy.REPLACE, req)
    }

    /** Dispara agora (alarme chegou): roda num trabalho do WorkManager para o Android manter o processo vivo. */
    fun fireNow(ctx: Context, wfId: String, nodeId: String) {
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInputData(workDataOf("wf" to wfId, "node" to nodeId, "due" to System.currentTimeMillis()))
            .addTag(tag(wfId)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("wf_fire_${wfId}_$nodeId", ExistingWorkPolicy.KEEP, req)
    }

    /** Próximo disparo de cada gatilho de horário do fluxo (o agendado de verdade; senão o calculado). */
    fun nextRuns(w: Workflow, ctx: Context? = null): List<Long> = if (!w.enabled) emptyList()
        else w.nodes.filter { it.type == "trigger_schedule" }.mapNotNull { n ->
            val now = System.currentTimeMillis()
            val st = ctx?.let { WfScheduleState.get(it, w.id, n.id) }
            st?.next?.takeIf { it > now - 120_000 && st.sig == n.config.toSortedMap().toString() }
                ?: WfLogic.nextFire(n.config, now, lastFire = st?.last)
        }.sorted()

    /** Último disparo agendado do fluxo (qualquer gatilho de horário). */
    fun lastFire(w: Workflow, ctx: Context): Long? = w.nodes.filter { it.type == "trigger_schedule" }
        .mapNotNull { WfScheduleState.get(ctx, w.id, it.id).last }.maxOrNull()
}

/** Último/próximo disparo por gatilho (SharedPreferences: leve e lido também com o app fechado). */
object WfScheduleState {
    data class St(val last: Long?, val next: Long?, val sig: String?)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("wf_schedule", Context.MODE_PRIVATE)
    fun get(ctx: Context, wf: String, node: String): St = sp(ctx).let {
        St(it.getLong("$wf|$node|last", 0).takeIf { v -> v > 0 }, it.getLong("$wf|$node|next", 0).takeIf { v -> v > 0 }, it.getString("$wf|$node|sig", null))
    }
    fun setNext(ctx: Context, wf: String, node: String, at: Long, sig: String) =
        sp(ctx).edit().putLong("$wf|$node|next", at).putString("$wf|$node|sig", sig).apply()
    fun setLast(ctx: Context, wf: String, node: String, at: Long) = sp(ctx).edit().putLong("$wf|$node|last", at).apply()
    fun clearNext(ctx: Context, wf: String) = sp(ctx).let { p ->
        p.edit().apply { p.all.keys.filter { it.startsWith("$wf|") && it.endsWith("|next") }.forEach { remove(it) } }.apply()
    }
}

/** Alarme exato do gatilho de horário chegou (app aberto, em segundo plano ou fechado). */
class WorkflowAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val wf = intent.getStringExtra("wf") ?: return
        val node = intent.getStringExtra("node") ?: return
        WorkflowScheduler.fireNow(context.applicationContext, wf, node)
    }
}

class WorkflowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wfId = inputData.getString("wf") ?: return Result.success()
        val nodeId = inputData.getString("node") ?: return Result.success()
        val due = inputData.getLong("due", System.currentTimeMillis())
        val ctx = applicationContext
        WorkflowStore.init(ctx.filesDir)
        val w = WorkflowStore.get(wfId) ?: return Result.success()
        val node = w.nodes.firstOrNull { it.id == nodeId && (it.type == "trigger_schedule" || it.type == "trigger_webhook") } ?: return Result.success()
        if (!w.enabled) return Result.success()
        // Disparo duplicado (alarme + reserva): este horário já rodou.
        val st = WfScheduleState.get(ctx, wfId, nodeId)
        if (!WfLogic.shouldFire(due, st.last, st.next, System.currentTimeMillis())) return Result.success()
        WfScheduleState.setLast(ctx, wfId, nodeId, System.currentTimeMillis())
        // Agenda o próximo ANTES de rodar: uma execução longa ou com erro não apaga a agenda.
        WorkflowScheduler.scheduleNext(ctx, wfId, node)
        if (node.type == "trigger_webhook") {
            // Uma execução por chamada nova, em ordem; nada de rodar sem chamada.
            WfWebhook.poll(ctx, wfId, node).forEach { h ->
                WorkflowEngine.start(wfId, trigger = "webhook", triggerNodeId = nodeId, payload = h.payload).onSuccess { WorkflowEngine.awaitRun(it, 4 * 60_000L) }
            }
            return Result.success()
        }
        // Espera a execução (até ~9 min) para o Android manter o processo vivo; já rodando = este disparo é pulado.
        WorkflowEngine.start(wfId, trigger = "schedule", triggerNodeId = nodeId).onSuccess { WorkflowEngine.awaitRun(it, 9 * 60_000L) }
        return Result.success()
    }
}
