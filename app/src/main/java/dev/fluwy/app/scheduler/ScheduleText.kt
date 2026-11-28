package dev.fluwy.app.scheduler

import dev.fluwy.app.data.Repeat
import dev.fluwy.app.data.RunStatus
import dev.fluwy.app.data.ScheduledTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Textos curtos (pt-BR) da agenda, compartilhados pela UI e pelas ferramentas. */
object ScheduleText {
    fun repeat(r: Repeat) = when (r) {
        Repeat.ONCE -> "Uma vez"; Repeat.HALF_HOUR -> "A cada 30 min"; Repeat.HOURLY -> "De hora em hora"
        Repeat.DAILY -> "Diário"; Repeat.WEEKLY -> "Semanal"
    }

    fun rule(t: ScheduledTask): String = buildString {
        append(if (t.cron.isNotBlank()) "cron \"${t.cron.trim()}\"" else repeat(t.repeat))
        if (t.timeZone.isNotBlank()) append(" · ${t.timeZone.trim()}")
        if (t.quietStartHour >= 0 && t.quietEndHour >= 0 && t.quietStartHour != t.quietEndHour)
            append(" · sem execução %02dh–%02dh".format(t.quietStartHour, t.quietEndHour))
    }

    fun status(s: RunStatus) = when (s) {
        RunStatus.OK -> "concluída"; RunStatus.QUIET -> "sem novidades"
        RunStatus.FAILED -> "falhou"; RunStatus.SKIPPED -> "pulada"
    }

    fun duration(ms: Long): String = when {
        ms < 1_000 -> "<1 s"
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min ${(ms / 1000) % 60} s"
        else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
    }

    private val fmt = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", java.util.Locale("pt", "BR"))
    fun at(ms: Long, zone: ZoneId): String = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))
}
