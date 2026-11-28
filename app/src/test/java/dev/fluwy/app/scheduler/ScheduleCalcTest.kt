package dev.fluwy.app.scheduler

import dev.fluwy.app.data.MissedPolicy
import dev.fluwy.app.data.Repeat
import dev.fluwy.app.data.RunStatus
import dev.fluwy.app.data.ScheduledRun
import dev.fluwy.app.data.ScheduledTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleCalcTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun ms(s: String, z: ZoneId = sp) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private fun task(repeat: Repeat = Repeat.DAILY, start: String = "2026-11-01T08:00", tz: String = "America/Sao_Paulo") =
        ScheduledTask(title = "t", prompt = "p", repeat = repeat, startAt = ms(start), timeZone = tz)

    @Test fun `diario mantem horario local`() {
        assertEquals(ms("2026-11-29T08:00"), ScheduleCalc.next(task(), ms("2026-11-28T09:00")))
        assertEquals(ms("2026-11-28T08:00"), ScheduleCalc.next(task(), ms("2026-11-28T07:59")))
    }

    @Test fun `diario atravessa horario de verao sem mudar a hora local`() {
        val ny = ZoneId.of("America/New_York")
        val t = ScheduledTask(title = "t", prompt = "p", repeat = Repeat.DAILY, startAt = ms("2026-03-07T08:00", ny), timeZone = "America/New_York")
        assertEquals(ms("2026-03-09T08:00", ny), ScheduleCalc.next(t, ms("2026-03-08T09:00", ny)))
    }

    @Test fun `semanal e intervalos fixos`() {
        assertEquals(ms("2026-11-29T08:00"), ScheduleCalc.next(task(Repeat.WEEKLY), ms("2026-11-22T08:00")))
        assertEquals(ms("2026-11-28T10:30"), ScheduleCalc.next(task(Repeat.HALF_HOUR), ms("2026-11-28T10:10")))
        assertEquals(ms("2026-11-28T11:00"), ScheduleCalc.next(task(Repeat.HOURLY), ms("2026-11-28T10:00")))
    }

    @Test fun `uma vez so antes de rodar`() {
        val t = task(Repeat.ONCE, start = "2026-11-30T10:00")
        assertEquals(t.startAt, ScheduleCalc.next(t, ms("2026-11-28T10:00")))
        assertNull(ScheduleCalc.next(t.copy(lastRunAt = 1), ms("2026-11-28T10:00")))
        assertNull(ScheduleCalc.next(t, ms("2026-12-01T10:00")))
    }

    @Test fun `cron tem prioridade sobre repeat e respeita o fuso`() {
        val t = task().copy(cron = "0 9 * * *", timeZone = "UTC")
        assertEquals(ms("2026-11-28T09:00", ZoneId.of("UTC")), ScheduleCalc.next(t, ms("2026-11-28T08:00", ZoneId.of("UTC"))))
    }

    @Test fun `janela de silencio adia para o fim`() {
        val t = task(Repeat.HOURLY).copy(quietStartHour = 23, quietEndHour = 7)
        assertEquals(ms("2026-11-29T07:00"), ScheduleCalc.next(t, ms("2026-11-28T23:30")))
        assertEquals(ms("2026-11-29T07:00"), ScheduleCalc.next(t, ms("2026-11-29T02:10")))
        assertEquals(ms("2026-11-28T15:00"), ScheduleCalc.next(t, ms("2026-11-28T14:10")))
        assertEquals(ms("2026-11-28T14:00"), ScheduleCalc.applyQuiet(ms("2026-11-28T13:00"), sp, 12, 14))
    }

    @Test fun `disparo perdido roda uma vez ou pula`() {
        val now = ms("2026-11-28T12:00")
        val missed = task().copy(nextRunAt = ms("2026-11-25T08:00"))
        val once = ScheduleCalc.onWake(missed, now)
        assertTrue(once.runMissed)
        assertEquals(ms("2026-11-29T08:00"), once.next)
        assertFalse(ScheduleCalc.onWake(missed.copy(missedPolicy = MissedPolicy.SKIP), now).runMissed)
        // atraso dentro da tolerância não é "perdido": dispara já
        val late = ScheduleCalc.onWake(task().copy(nextRunAt = now - 60_000), now)
        assertFalse(late.runMissed); assertEquals(now, late.next)
        assertNull(ScheduleCalc.onWake(missed.copy(enabled = false), now).next)
    }

    @Test fun `dedup bloqueia execucao paralela salvo travada`() {
        assertTrue(ScheduleCalc.canStart(null, 1000))
        assertFalse(ScheduleCalc.canStart(1000, 2000))
        assertTrue(ScheduleCalc.canStart(0, ScheduleCalc.STALE_RUN_MS + 1))
    }

    @Test fun `historico limitado e mais recente primeiro`() {
        var runs = emptyList<ScheduledRun>()
        repeat(25) { runs = ScheduleCalc.appendRun(runs, ScheduledRun(startedAt = it.toLong(), status = RunStatus.OK)) }
        assertEquals(ScheduleCalc.MAX_RUNS, runs.size)
        assertEquals(24L, runs.first().startedAt)
    }

    @Test fun `validacao de configuracao`() {
        assertNotNull(ScheduleCalc.validate(task().copy(cron = "x")))
        assertNotNull(ScheduleCalc.validate(task(tz = "Marte/Base")))
        assertNotNull(ScheduleCalc.validate(task().copy(quietStartHour = 3)))
        assertNull(ScheduleCalc.validate(task(tz = "")))
        assertEquals(3, ScheduleCalc.upcoming(task(), ms("2026-11-28T09:00"), 3).size)
    }
}
