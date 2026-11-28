package dev.fluwy.app.workflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WfScheduleTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(sp).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun parseFreeText() {
        assertEquals(mapOf("mode" to "interval", "minutes" to "2"), WfLogic.parseSchedule("a cada 2 minutos"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "5"), WfLogic.parseSchedule("A cada 5 min"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "60"), WfLogic.parseSchedule("de hora em hora"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "60"), WfLogic.parseSchedule("A cada hora"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "180"), WfLogic.parseSchedule("a cada 3 horas"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "2880"), WfLogic.parseSchedule("a cada 2 dias"))
        assertEquals(mapOf("mode" to "interval", "minutes" to "10"), WfLogic.parseSchedule("every 10 minutes"))
        assertEquals(mapOf("mode" to "daily", "time" to "08:00"), WfLogic.parseSchedule("1x por dia às 8h"))
        assertEquals(mapOf("mode" to "daily", "time" to "18:30"), WfLogic.parseSchedule("todo dia 18:30"))
        assertEquals(mapOf("mode" to "daily", "time" to "09:00"), WfLogic.parseSchedule("Diariamente"))
        assertEquals(mapOf("mode" to "weekly", "weekday" to "1", "time" to "08:30"), WfLogic.parseSchedule("toda segunda às 08:30"))
        assertEquals(mapOf("mode" to "weekly", "weekday" to "5", "time" to "17:00"), WfLogic.parseSchedule("sexta 17h"))
        assertEquals(mapOf("mode" to "weekly", "weekday" to "6", "time" to "10:00"), WfLogic.parseSchedule("Sábado 10:00"))
        assertNull(WfLogic.parseSchedule("blá blá"))
        assertNull(WfLogic.parseSchedule(""))
    }

    @Test fun normalizeKeepsStructuredAndParsesWhen() {
        assertEquals(mapOf("mode" to "interval", "minutes" to "15"), WfLogic.normalizeSchedule(mapOf("mode" to "interval", "minutes" to "15")))
        assertEquals(mapOf("mode" to "interval", "minutes" to "2"), WfLogic.normalizeSchedule(mapOf("when" to "a cada 2 minutos", "mode" to "daily", "time" to "09:00")))
        assertEquals(mapOf("mode" to "interval", "minutes" to "2"), WfLogic.normalizeSchedule(mapOf("mode" to "a cada 2 minutos")))
        assertEquals(mapOf("mode" to "daily", "time" to "07:00"), WfLogic.normalizeSchedule(mapOf("time" to "07:00")))
        assertNull(WfLogic.normalizeSchedule(mapOf("when" to "quando der vontade")))
        assertNull(WfLogic.normalizeSchedule(mapOf("mode" to "interval", "minutes" to "0")))
    }

    @Test fun intervalAnchoredOnLastFire() {
        val cfg = mapOf("mode" to "interval", "minutes" to "2")
        val now = 10_000_000L
        assertEquals(now + 120_000, WfLogic.nextFire(cfg, now))
        assertEquals(now + 60_000, WfLogic.nextFire(cfg, now, lastFire = now - 60_000))
        assertEquals(now + 1_000, WfLogic.nextFire(cfg, now, lastFire = now - 10 * 60_000)) // atrasado: roda já
    }

    @Test fun dailyAndWeekly() {
        val now = at(2026, 11, 22, 10, 0) // domingo
        assertEquals(at(2026, 11, 23, 9, 0), WfLogic.nextFire(mapOf("mode" to "daily", "time" to "09:00"), now, sp))
        assertEquals(at(2026, 11, 22, 18, 0), WfLogic.nextFire(mapOf("mode" to "daily", "time" to "18:00"), now, sp))
        assertEquals(at(2026, 11, 23, 8, 30), WfLogic.nextFire(mapOf("mode" to "weekly", "weekday" to "1", "time" to "08:30"), now, sp))
    }

    @Test fun duplicateFireIsSkipped() {
        val due = 1_000_000L
        assertTrue(WfLogic.shouldFire(due, last = null, next = due, now = due))
        assertFalse(WfLogic.shouldFire(due, last = due + 5_000, next = due + 120_000, now = due + 90_000)) // reserva do WorkManager
        assertTrue(WfLogic.shouldFire(due + 120_000, last = due, next = due + 120_000, now = due + 120_000))
        assertFalse(WfLogic.shouldFire(due, last = due - 200_000, next = due + 600_000, now = due + 1_000)) // disparo velho
    }

    @Test fun labels() {
        val now = at(2026, 11, 22, 10, 0)
        assertEquals("agora", WfLogic.whenLabel(now + 10_000, now, sp))
        assertEquals("em 2 min", WfLogic.whenLabel(now + 120_000, now, sp))
        assertEquals("há 5 min", WfLogic.whenLabel(now - 300_000, now, sp))
        assertEquals("hoje 18:00", WfLogic.whenLabel(at(2026, 11, 22, 18, 0), now, sp))
        assertEquals("amanhã 09:00", WfLogic.whenLabel(at(2026, 11, 23, 9, 0), now, sp))
        assertEquals("ontem 08:15", WfLogic.whenLabel(at(2026, 11, 21, 8, 15), now, sp))
        assertEquals("25/11 07:00", WfLogic.whenLabel(at(2026, 11, 25, 7, 0), now, sp))
        assertEquals("A cada 2 min", WfLogic.scheduleLabel(mapOf("mode" to "interval", "minutes" to "2")))
        assertEquals("A cada hora", WfLogic.scheduleLabel(mapOf("mode" to "interval", "minutes" to "60")))
        assertEquals("A cada 2 dias", WfLogic.scheduleLabel(mapOf("mode" to "interval", "minutes" to "2880")))
    }
}
