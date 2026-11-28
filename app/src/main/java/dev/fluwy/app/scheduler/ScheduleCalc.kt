package dev.fluwy.app.scheduler

import dev.fluwy.app.data.MissedPolicy
import dev.fluwy.app.data.Repeat
import dev.fluwy.app.data.ScheduledRun
import dev.fluwy.app.data.ScheduledTask
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Lógica pura da agenda: próximo disparo, fuso, janela de silêncio, execuções perdidas e dedup. */
object ScheduleCalc {
    /** Tolerância antes de considerar um disparo "perdido" (Doze pode atrasar alguns minutos). */
    const val MISSED_GRACE_MS = 10 * 60_000L
    /** Execução "em andamento" mais velha que isso é considerada travada e não bloqueia uma nova. */
    const val STALE_RUN_MS = 3 * 60 * 60_000L
    const val MAX_RUNS = 20

    fun zone(t: ScheduledTask, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        t.timeZone.trim().takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    /** Mensagem de erro em pt-BR ou null se o fuso é válido (vazio = fuso do aparelho). */
    fun validateZone(id: String): String? =
        if (id.isBlank() || runCatching { ZoneId.of(id.trim()) }.isSuccess) null else "Fuso inválido: use um nome IANA, ex.: America/Sao_Paulo ou UTC."

    /** Erro de configuração da tarefa (cron, fuso, janela) ou null. */
    fun validate(t: ScheduledTask): String? {
        if (t.cron.isNotBlank()) CronExpression.validate(t.cron)?.let { return it }
        validateZone(t.timeZone)?.let { return it }
        if ((t.quietStartHour >= 0) != (t.quietEndHour >= 0)) return "Janela sem execução incompleta: informe início e fim."
        if (t.quietStartHour !in -1..23 || t.quietEndHour !in -1..23) return "Horas da janela devem ficar entre 0 e 23."
        return null
    }

    /** Próximo disparo estritamente depois de [after], já respeitando a janela de silêncio. */
    fun next(t: ScheduledTask, after: Long, fallback: ZoneId = ZoneId.systemDefault()): Long? {
        val z = zone(t, fallback)
        val raw = rawNext(t, after, z) ?: return null
        return applyQuiet(raw, z, t.quietStartHour, t.quietEndHour)
    }

    fun upcoming(t: ScheduledTask, after: Long, count: Int, fallback: ZoneId = ZoneId.systemDefault()): List<Long> {
        val out = mutableListOf<Long>()
        var cur = after
        repeat(count) {
            val n = next(t, cur, fallback) ?: return out
            if (out.lastOrNull() != n) out += n
            cur = n
        }
        return out
    }

    internal fun rawNext(t: ScheduledTask, after: Long, z: ZoneId): Long? {
        if (t.cron.isNotBlank()) return CronExpression.parse(t.cron).getOrNull()?.next(after, z)
        return when (t.repeat) {
            Repeat.ONCE -> t.startAt.takeIf { t.lastRunAt == null && it > after }
            Repeat.HALF_HOUR -> fixed(t.startAt, after, 30 * 60_000L)
            Repeat.HOURLY -> fixed(t.startAt, after, 60 * 60_000L)
            Repeat.DAILY -> calendar(t.startAt, after, z, 1)
            Repeat.WEEKLY -> calendar(t.startAt, after, z, 7)
        }
    }

    private fun fixed(start: Long, after: Long, step: Long): Long =
        if (start > after) start else start + ((after - start) / step + 1) * step

    /** Passo em dias de calendário: mantém o horário local mesmo com horário de verão. */
    private fun calendar(start: Long, after: Long, z: ZoneId, days: Long): Long {
        val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), z)
        if (start > after) return start
        val elapsed = ChronoUnit.DAYS.between(s.toLocalDate(), ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), z).toLocalDate())
        var k = (elapsed / days).coerceAtLeast(0)
        while (true) {
            val c = s.plusDays(k * days).toInstant().toEpochMilli()
            if (c > after) return c
            k++
        }
    }

    /** Se [ms] cair na janela [qs, qe) (pode cruzar a meia-noite), adia para o fim dela. */
    fun applyQuiet(ms: Long, z: ZoneId, qs: Int, qe: Int): Long {
        if (qs < 0 || qe < 0 || qs == qe) return ms
        val d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), z)
        val h = d.hour
        val inside = if (qs < qe) h in qs until qe else h >= qs || h < qe
        if (!inside) return ms
        var end = d.truncatedTo(ChronoUnit.DAYS).withHour(qe)
        if (!end.isAfter(d)) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
    }

    /** Decisão ao acordar/sincronizar: rodar o disparo perdido agora? E quando é o próximo? */
    data class Wake(val runMissed: Boolean, val next: Long?)

    fun onWake(t: ScheduledTask, now: Long, fallback: ZoneId = ZoneId.systemDefault()): Wake {
        if (!t.enabled) return Wake(false, null)
        val due = t.nextRunAt ?: return Wake(false, next(t, now - 1, fallback))
        if (due >= now - MISSED_GRACE_MS) return Wake(false, if (due >= now) due else now)
        // Perdeu um ou mais disparos: no máximo UMA execução de recuperação, nunca uma rajada.
        return Wake(t.missedPolicy == MissedPolicy.RUN_ONCE, next(t, now, fallback))
    }

    /** Dedup: só começa se não houver execução ativa (ou se a anterior travou). */
    fun canStart(runningSince: Long?, now: Long): Boolean = runningSince == null || now - runningSince > STALE_RUN_MS

    fun appendRun(runs: List<ScheduledRun>, run: ScheduledRun): List<ScheduledRun> = (listOf(run) + runs).take(MAX_RUNS)
}
