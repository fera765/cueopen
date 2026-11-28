package dev.fluwy.app.scheduler

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.MainActivity
import dev.fluwy.app.SessionNotificationRouting
import dev.fluwy.app.data.Repeat
import dev.fluwy.app.data.ScheduledTask
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.now
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Tarefas agendadas (equivalente ao "Scheduled tasks" do Fluwy), via WorkManager. */
object Scheduler {
    private fun name(id: String) = "sched_$id"

    fun schedule(ctx: Context, t: ScheduledTask) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(name(t.id))
        if (!t.enabled) return
        val delayMs = (t.startAt - now()).coerceAtLeast(0)
        val data = workDataOf("id" to t.id)
        when (t.repeat) {
            Repeat.ONCE -> wm.enqueueUniqueWork(name(t.id), ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduledWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setInputData(data).addTag(name(t.id)).build())
            else -> {
                val (n, unit) = when (t.repeat) { Repeat.HALF_HOUR -> 30L to TimeUnit.MINUTES; Repeat.HOURLY -> 1L to TimeUnit.HOURS; Repeat.DAILY -> 1L to TimeUnit.DAYS; else -> 7L to TimeUnit.DAYS }
                wm.enqueueUniquePeriodicWork(name(t.id), ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<ScheduledWorker>(n, unit).setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setInputData(data).addTag(name(t.id)).build())
            }
        }
    }

    fun cancel(ctx: Context, id: String) {
        val workManager = WorkManager.getInstance(ctx)
        workManager.cancelUniqueWork(name(id))
        workManager.cancelAllWorkByTag(name(id))
    }

    fun runNow(ctx: Context, id: String) = WorkManager.getInstance(ctx).enqueue(
        OneTimeWorkRequestBuilder<ScheduledWorker>().setInputData(workDataOf("id" to id)).addTag(name(id)).build())
}

class ScheduledWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FluwyApp
        if (dev.fluwy.app.AppExit.exited(app)) return Result.success()  // o usuário saiu do app: pula esta execução
        val id = inputData.getString("id") ?: return Result.failure()
        val task = app.store.state.value.scheduled.firstOrNull { it.id == id } ?: return Result.failure()
        val session = Session(title = "⏰ ${task.title}", agentId = task.agentId, scheduledTaskId = task.id)
        if (!app.store.beginScheduledRun(id, session)) return Result.failure()
        val job = app.agent.send(session.id, "[Execução agendada · horário local: ${dev.fluwy.app.agent.LocalClock.line()}]\n" +
            "[Se não houver nada novo para relatar nesta execução, termine com ${QuietRun.MARKER} na resposta final: não gera notificação.]\n" + task.prompt)
        try {
            if (isStopped || !app.store.isScheduledRunActive(id, session.id)) {
                app.agent.stop(session.id)
                return Result.failure()
            }
            job.join()
            while (app.agent.isRunning(session.id)) {
                if (isStopped || !app.store.isScheduledRunActive(id, session.id)) {
                    app.agent.stop(session.id)
                    return Result.failure()
                }
                delay(1000)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            app.agent.stop(session.id)
            throw cancelled
        }
        if (!app.store.isScheduledRunActive(id, session.id)) return Result.failure()
        val failed = app.store.session(session.id)?.status == dev.fluwy.app.data.SessionStatus.ERROR
        app.store.setScheduledResult(id, failed)
        app.store.flush()
        // OpenBot 0.71: execução sem novidade termina em silêncio (falha sempre avisa).
        val quiet = !failed && QuietRun.isQuiet(app.store.session(session.id)?.llm?.lastOrNull { it.role == "assistant" }?.content)
        val final = app.store.session(session.id)?.items?.lastOrNull { it is dev.fluwy.app.data.ChatItem.Assistant } as? dev.fluwy.app.data.ChatItem.Assistant
        when {
            failed -> dev.fluwy.app.notify.NotificationCenter.post("${task.title} (falhou)", "A tarefa agendada falhou. Toque para ver o que aconteceu.", dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.FAILURE, session.id)
            quiet -> dev.fluwy.app.notify.NotificationCenter.post(task.title, "Execução sem novidades.", dev.fluwy.app.notify.NotificationCenter.Priority.SILENT, dev.fluwy.app.notify.NotificationCenter.Category.MONITOR, session.id)
            app.store.settings.notifyOnDone -> dev.fluwy.app.notify.NotificationCenter.post("Tarefa agendada concluída", task.title + (final?.text?.let { ": " + it.take(200) } ?: ""), dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL, dev.fluwy.app.notify.NotificationCenter.Category.DONE, session.id)
        }
        return Result.success()
    }

    private fun notify(app: FluwyApp, sessionId: String, title: String) {
        val validId = SessionNotificationRouting.validSessionId(sessionId)
        val key = validId ?: "invalid-session:$title"
        val intent = Intent(app, MainActivity::class.java)
            .setAction(SessionNotificationRouting.ACTION_OPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        validId?.let {
            intent.putExtra(SessionNotificationRouting.EXTRA_SESSION, it)
                .putExtra(SessionNotificationRouting.EXTRA_SESSION_ID, it)
                .setData(android.net.Uri.parse(SessionNotificationRouting.deepLink(it)))
        }
        val stableId = SessionNotificationRouting.stableId(key)
        val pi = PendingIntent.getActivity(app, stableId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, FluwyApp.CH_TASKS).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Tarefa agendada concluída").setContentText(title).setContentIntent(pi).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(stableId, n)
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
        // Fluxos: religa os alarmes dos gatilhos de horário (o sistema apaga alarmes ao reiniciar).
        runCatching { dev.fluwy.app.workflow.WorkflowStore.init(context.filesDir); dev.fluwy.app.workflow.WorkflowScheduler.syncAll(context) }
    }
}


/** Marcador que o agente usa numa execução agendada sem novidades (sem notificação). */
object QuietRun {
    const val MARKER = "[SEM_NOVIDADES]"
    fun isQuiet(finalReply: String?): Boolean = finalReply?.contains(MARKER) == true
}
