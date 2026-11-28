package dev.fluwy.app.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CronExpressionTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun ms(s: String, z: ZoneId = sp) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private fun next(expr: String, from: String, z: ZoneId = sp) = CronExpression.parse(expr).getOrThrow().next(ms(from, z), z)

    @Test fun `dias uteis as 9h pula fim de semana`() {
        // 2026-11-28 é sábado
        assertEquals(ms("2026-11-30T09:00"), next("0 9 * * 1-5", "2026-11-28T10:00"))
        assertEquals(ms("2026-11-30T09:00"), next("0 9 * * mon-fri", "2026-11-28T10:00"))
    }

    @Test fun `passos e listas`() {
        assertEquals(ms("2026-11-28T10:15"), next("*/15 * * * *", "2026-11-28T10:00"))
        assertEquals(ms("2026-11-28T12:30"), next("30 8-18/2 * * *", "2026-11-28T10:30"))
        assertEquals(ms("2026-12-01T00:00"), next("0 0 1,15 * *", "2026-11-28T10:00"))
    }

    @Test fun `estritamente depois do instante`() {
        assertEquals(ms("2026-11-29T09:00"), next("0 9 * * *", "2026-11-28T09:00"))
    }

    @Test fun `dia do mes OU dia da semana quando ambos restritos`() {
        // dia 1 ou domingo: 2026-11-29 é domingo
        assertEquals(ms("2026-11-29T00:00"), next("0 0 1 * 0", "2026-11-28T10:00"))
    }

    @Test fun `domingo como 7 e macros`() {
        assertEquals(ms("2026-11-29T00:00"), next("0 0 * * 7", "2026-11-28T10:00"))
        assertEquals(ms("2026-11-29T00:00"), next("@daily", "2026-11-28T10:00"))
    }

    @Test fun `data impossivel nao trava`() {
        assertNull(next("0 0 31 2 *", "2026-11-28T10:00"))
    }

    @Test fun `validacao com mensagens`() {
        assertNotNull(CronExpression.validate("0 9 * *"))
        assertNotNull(CronExpression.validate("60 * * * *"))
        assertNotNull(CronExpression.validate("* * * * foo"))
        assertNotNull(CronExpression.validate("5-1 * * * *"))
        assertNull(CronExpression.validate("0 9 * * 1-5"))
    }

    @Test fun `upcoming lista as proximas`() {
        val list = CronExpression.parse("0 */6 * * *").getOrThrow().upcoming(ms("2026-11-28T01:00"), sp, 3)
        assertEquals(listOf(ms("2026-11-28T06:00"), ms("2026-11-28T12:00"), ms("2026-11-28T18:00")), list)
    }
}
