package dev.cueopen.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.cueopen.app.CueApp

/**
 * Agendamento REAL e automático dos agentes autônomos via AlarmManager com alarme exato
 * (setExactAndAllowWhileIdle), que o Android dispara mesmo com o app fechado ou em Doze.
 * Quando o alarme toca, o receiver sobe o serviço em foreground e roda o heartbeat do agente,
 * depois reagenda o próximo. Isso substitui o loop de 60s (que morria quando o app era morto).
 */
object Heartbeat {
    const val ACTION = "com.fluwy.bot.HEARTBEAT"
    const val EXTRA_AGENT = "agent"

    private fun pi(ctx: Context, agentId: String): PendingIntent {
        val i = Intent(ctx, HeartbeatReceiver::class.java).setAction(ACTION).putExtra(EXTRA_AGENT, agentId).setData(android.net.Uri.parse("cue://beat/$agentId"))
        return PendingIntent.getBroadcast(ctx, agentId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Agenda o próximo disparo do agente para daqui a [minutes] (ou no horário absoluto [atMillis]). */
    fun schedule(ctx: Context, agentId: String, minutes: Int, atMillis: Long? = null) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fireAt = atMillis ?: (System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L)
        val p = pi(ctx, agentId)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, p) // inexato, mas ainda acorda
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, p)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, p)
        }
    }

    fun cancel(ctx: Context, agentId: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, agentId))
    }

    /** (Re)agenda todos os agentes autônomos — usado no boot e ao abrir o app. */
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext as? CueApp ?: return
        app.store.state.value.agents.filter { it.autonomous && it.homeSessionId != null }.forEach { a ->
            val next = (a.lastBeatAt ?: System.currentTimeMillis()) + a.heartbeatMinutes * 60_000L
            schedule(ctx, a.id, a.heartbeatMinutes, atMillis = maxOf(next, System.currentTimeMillis() + 5_000))
        }
    }
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val agentId = intent.getStringExtra(Heartbeat.EXTRA_AGENT) ?: return
        val app = context.applicationContext as? CueApp ?: return
        val agent = app.store.agent(agentId) ?: return
        // 1) REAGENDA O PRÓXIMO ALARME IMEDIATAMENTE. Antes, o ciclo inteiro rodava dentro do
        //    receiver (goAsync ~10 s); se o ciclo demorasse, o Android matava o receiver e o
        //    reagendamento no finally não rodava — a corrente quebrava e o agente nunca mais acordava.
        //    Reagendar primeiro garante que o próximo ciclo sempre aconteça, não importa a duração.
        if (agent.autonomous) Heartbeat.schedule(context, agentId, agent.heartbeatMinutes)
        if (!agent.autonomous) return
        // 2) Mantém o processo vivo e dispara o ciclo no escopo do app (sem bloquear o receiver).
        dev.cueopen.app.sandbox.SandboxService.start(context, "${agent.name}: ciclo autônomo")
        runCatching { app.agent.triggerHeartbeatFromAlarm(agentId) }
    }
}
