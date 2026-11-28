package dev.fluwy.app.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.RunStatus
import dev.fluwy.app.data.ScheduledRun
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.now
import dev.fluwy.app.notify.NotificationCenter
import kotlinx.coroutines.delay

/** Executa UMA vez uma tarefa agendada numa conversa nova e registra status/duração/erro. */
class ScheduledWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FluwyApp
        if (dev.fluwy.app.AppExit.exited(app)) return Result.success()  // o usuário saiu do app: pula esta execução
        val id = inputData.getString("id") ?: return Result.failure()
        val manual = inputData.getBoolean("manual", false)
        val task = app.store.state.value.scheduled.firstOrNull { it.id == id } ?: return Result.failure()
        val session = Session(title = "⏰ ${task.title}", agentId = task.agentId, scheduledTaskId = task.id)
        // beginScheduledRun recusa se já houver execução ativa (dedup) ou se o chat de origem sumiu.
        if (!app.store.beginScheduledRun(id, session)) return Result.success()
        val started = now()
        var status = RunStatus.FAILED
        var error = ""
        try {
            val job = app.agent.send(session.id, dev.fluwy.app.prompts.Prompts.render("scheduled_run_header",
                mapOf("clock" to dev.fluwy.app.agent.LocalClock.line(), "marker" to QuietRun.MARKER)) + "\n" + task.prompt)
            job.join()
            while (app.agent.isRunning(session.id)) {
                if (isStopped || !app.store.isScheduledRunActive(id, session.id)) {
                    app.agent.stop(session.id)
                    error = "Execução interrompida (tarefa removida ou cancelada)."
                    return Result.failure()
                }
                delay(1000)
            }
            val s = app.store.session(session.id)
            val failed = s?.status == SessionStatus.ERROR
            error = if (failed) lastError(s) else ""
            status = when {
                failed -> RunStatus.FAILED
                QuietRun.isQuiet(s?.llm?.lastOrNull { it.role == "assistant" }?.content) -> RunStatus.QUIET
                else -> RunStatus.OK
            }
            notify(app, task.title, status, session.id, s)
            return Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            app.agent.stop(session.id)
            error = "Execução cancelada pelo sistema."
            throw cancelled
        } catch (e: Exception) {
            error = e.message?.take(200) ?: e.javaClass.simpleName
            dev.fluwy.app.diag.ErrorLog.log("scheduler", e, "tarefa agendada ${task.id}")
            return Result.success()
        } finally {
            app.store.finishScheduledRun(id, ScheduledRun(startedAt = started, durationMs = now() - started,
                status = status, error = error, sessionId = session.id, manual = manual))
            app.store.flush()
        }
    }

    private fun lastError(s: Session?): String {
        val step = s?.items?.lastOrNull { it is ChatItem.Step && it.status == StepStatus.ERROR } as? ChatItem.Step
        return (step?.let { "${it.title}: ${it.result}" } ?: "A sessão terminou com erro.").take(200)
    }

    private fun notify(app: FluwyApp, title: String, status: RunStatus, sessionId: String, s: Session?) {
        val final = s?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
        when {
            status == RunStatus.FAILED -> NotificationCenter.post("$title (falhou)", "A tarefa agendada falhou. Toque para ver o que aconteceu.", NotificationCenter.Priority.HIGH, NotificationCenter.Category.FAILURE, sessionId)
            status == RunStatus.QUIET -> NotificationCenter.post(title, "Execução sem novidades.", NotificationCenter.Priority.SILENT, NotificationCenter.Category.MONITOR, sessionId)
            app.store.settings.notifyOnDone -> NotificationCenter.post("Tarefa agendada concluída", title + (final?.text?.let { ": " + it.take(200) } ?: ""), NotificationCenter.Priority.NORMAL, NotificationCenter.Category.DONE, sessionId)
        }
    }
}
