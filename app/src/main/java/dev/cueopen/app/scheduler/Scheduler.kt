package dev.cueopen.app.scheduler

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
import dev.cueopen.app.CueApp
import dev.cueopen.app.MainActivity
import dev.cueopen.app.data.Repeat
import dev.cueopen.app.data.ScheduledTask
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.now
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Tarefas agendadas (equivalente ao "Scheduled tasks" do Cue), via WorkManager. */
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
                OneTimeWorkRequestBuilder<ScheduledWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setInputData(data).build())
            else -> {
                val (n, unit) = when (t.repeat) { Repeat.HALF_HOUR -> 30L to TimeUnit.MINUTES; Repeat.HOURLY -> 1L to TimeUnit.HOURS; Repeat.DAILY -> 1L to TimeUnit.DAYS; else -> 7L to TimeUnit.DAYS }
                wm.enqueueUniquePeriodicWork(name(t.id), ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<ScheduledWorker>(n, unit).setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setInputData(data).build())
            }
        }
    }

    fun cancel(ctx: Context, id: String) = WorkManager.getInstance(ctx).cancelUniqueWork(name(id))

    fun runNow(ctx: Context, id: String) = WorkManager.getInstance(ctx).enqueue(
        OneTimeWorkRequestBuilder<ScheduledWorker>().setInputData(workDataOf("id" to id)).build())
}

class ScheduledWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CueApp
        val id = inputData.getString("id") ?: return Result.failure()
        val task = app.store.state.value.scheduled.firstOrNull { it.id == id } ?: return Result.failure()
        val session = Session(title = "⏰ ${task.title}", agentId = task.agentId, scheduledTaskId = task.id)
        app.store.upsertSession(session)
        app.store.upsertScheduled(task.copy(lastRunAt = now(), lastSessionId = session.id))
        val job = app.agent.send(session.id, task.prompt)
        job.join()
        while (app.agent.isRunning(session.id)) delay(1000)
        app.store.flush()
        if (app.store.settings.notifyOnDone) notify(app, session.id, task.title)
        return Result.success()
    }

    private fun notify(app: CueApp, sessionId: String, title: String) {
        val pi = PendingIntent.getActivity(app, sessionId.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("session", sessionId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, CueApp.CH_TASKS).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Tarefa agendada concluída").setContentText(title).setContentIntent(pi).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(sessionId.hashCode(), n)
    }
}


/**
 * Garantia de vida dos agentes autônomos: a cada 15 min o WorkManager acorda o app (se o Android
 * tiver matado o processo), o que reinicia o loop de heartbeats e o serviço em foreground.
 */
class AutonomyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        dev.cueopen.app.scheduler.Heartbeat.rescheduleAll(applicationContext)
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
        AutonomyWorker.ensure(context)
        dev.cueopen.app.scheduler.Heartbeat.rescheduleAll(context)
        dev.cueopen.app.uiconnector.iqoption.IqSignalAlarms.rescheduleAll(context)
    }
}
