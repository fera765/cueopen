package dev.fluwy.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.RunStatus
import dev.fluwy.app.data.ScheduledRun
import dev.fluwy.app.data.ScheduledTask
import dev.fluwy.app.data.now
import java.util.concurrent.TimeUnit

/**
 * Tarefas agendadas: cada agenda tem UM alarme (AlarmManager, exato quando permitido) no
 * próximo disparo calculado por [ScheduleCalc]. O alarme enfileira a execução como trabalho
 * único (`schedrun_<id>`, KEEP = nunca duas em paralelo) e já reagenda o seguinte.
 * [syncAll] (abertura do app, boot, a cada 15 min) recupera disparos perdidos conforme a política.
 */
object Scheduler {
    const val ACTION = "com.fluwy.bot.SCHEDULED_FIRE"
    const val EXTRA_ID = "id"
    private fun legacyName(id: String) = "sched_$id"
    private fun runName(id: String) = "schedrun_$id"

    private fun pi(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ScheduleAlarmReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id)
            .setData(android.net.Uri.parse("fluwy://sched/$id"))
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** (Re)calcula o próximo disparo, grava em `nextRunAt` e arma o alarme (ou cancela). */
    fun schedule(ctx: Context, t: ScheduledTask) {
        val app = ctx.applicationContext as? FluwyApp
        val next = if (t.enabled) ScheduleCalc.next(t, now() - 1) else null
        app?.store?.patchScheduled(t.id) { it.copy(nextRunAt = next) }
        arm(ctx, t.id, next)
    }

    internal fun arm(ctx: Context, id: String, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, id)
        if (at == null) { am.cancel(p); return }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(ctx: Context, id: String) {
        arm(ctx, id, null)
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(legacyName(id))
        wm.cancelUniqueWork(runName(id))
        wm.cancelAllWorkByTag(legacyName(id))
    }

    /** Executa já (fora do horário). Se já houver execução ativa, é ignorado (dedup). */
    fun runNow(ctx: Context, id: String) = enqueueRun(ctx, id, manual = true)

    internal fun enqueueRun(ctx: Context, id: String, manual: Boolean) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(runName(id), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScheduledWorker>().setInputData(workDataOf("id" to id, "manual" to manual))
                .addTag(legacyName(id)).build())
    }

    /** Pausa (mantém histórico) ou retoma (recalcula o próximo disparo a partir de agora). */
    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val app = ctx.applicationContext as? FluwyApp ?: return
        app.store.patchScheduled(id) { it.copy(enabled = on) }
        app.store.state.value.scheduled.firstOrNull { it.id == id }?.let { schedule(ctx, it) }
    }

    /** Alinha todos os alarmes; aplica a política de disparos perdidos. */
    fun syncAll(ctx: Context) {
        val app = ctx.applicationContext as? FluwyApp ?: return
        val wm = WorkManager.getInstance(ctx)
        val t0 = now()
        app.store.state.value.scheduled.forEach { t ->
            wm.cancelUniqueWork(legacyName(t.id))  // migra agendas antigas do WorkManager periódico
            val w = ScheduleCalc.onWake(t, t0)
            if (w.runMissed) {
                enqueueRun(ctx, t.id, manual = false)
            } else if (t.enabled && t.nextRunAt != null && t.nextRunAt < t0 - ScheduleCalc.MISSED_GRACE_MS) {
                app.store.patchScheduled(t.id) { it.copy(runs = ScheduleCalc.appendRun(it.runs,
                    ScheduledRun(startedAt = t.nextRunAt, status = RunStatus.SKIPPED, error = "Disparo perdido (aparelho desligado ou em economia); pulado pela política."))) }
            }
            app.store.patchScheduled(t.id) { it.copy(nextRunAt = w.next) }
            arm(ctx, t.id, w.next)
        }
    }
}

/** Alarme da agenda: enfileira a execução (dedup) e arma o próximo disparo imediatamente. */
class ScheduleAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (dev.fluwy.app.AppExit.exited(context)) return
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        val app = context.applicationContext as? FluwyApp ?: return
        val t = app.store.state.value.scheduled.firstOrNull { it.id == id } ?: return Scheduler.arm(context, id, null)
        if (!t.enabled) return Scheduler.arm(context, id, null)
        val fired = now()
        // Reagenda primeiro: o próximo disparo não depende do sucesso desta execução.
        val next = ScheduleCalc.next(t.copy(lastRunAt = fired), fired)
        app.store.patchScheduled(id) { it.copy(nextRunAt = next) }
        Scheduler.arm(context, id, next)
        Scheduler.enqueueRun(context, id, manual = false)
    }
}

/**
 * Garantia de vida dos agentes autônomos: a cada 15 min o WorkManager acorda o app (se o Android
 * tiver matado o processo), o que reinicia o loop de heartbeats e o serviço em foreground.
 */
class AutonomyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (dev.fluwy.app.AppExit.exited(applicationContext)) return Result.success()
        dev.fluwy.app.scheduler.Heartbeat.rescheduleAll(applicationContext)
        runCatching { Scheduler.syncAll(applicationContext) }
        return Result.success()
    }
    companion object {
        fun ensure(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("autonomy", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutonomyWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}

/** Após reiniciar o celular, religa os agentes autônomos. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        // criar a Application já chama startAutonomy(); aqui só garantimos o worker
        if (dev.fluwy.app.AppExit.exited(context)) return
        AutonomyWorker.ensure(context)
        dev.fluwy.app.scheduler.Heartbeat.rescheduleAll(context)
        runCatching { Scheduler.syncAll(context) }
        // Fluxos: religa os alarmes dos gatilhos de horário (o sistema apaga alarmes ao reiniciar).
        runCatching { dev.fluwy.app.workflow.WorkflowStore.init(context.filesDir); dev.fluwy.app.workflow.WorkflowScheduler.syncAll(context) }
    }
}


/** Marcador que o agente usa numa execução agendada sem novidades (sem notificação). */
object QuietRun {
    const val MARKER = "[SEM_NOVIDADES]"
    fun isQuiet(finalReply: String?): Boolean = finalReply?.contains(MARKER) == true
}
