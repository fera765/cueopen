package dev.fluwy.app.scheduler

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Expressão cron padrão de 5 campos: `minuto hora dia-do-mês mês dia-da-semana`.
 * Aceita `*`, listas (`1,15`), intervalos (`1-5`), passos (`0-59/15`, `8-18/2`, asterisco/passo) e nomes
 * (`jan`…`dec`, `sun`…`sat`; 0 e 7 = domingo). Como no cron clássico, se dia-do-mês e
 * dia-da-semana forem ambos restritos, basta um deles casar.
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    private val weekdays: Set<Int>,
    private val dayRestricted: Boolean,
    private val weekdayRestricted: Boolean,
) {
    /** Próximo instante estritamente depois de [afterMillis] no fuso [zone]; null se não houver em ~5 anos. */
    fun next(afterMillis: Long, zone: ZoneId): Long? {
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t.isBefore(limit)) {
            if (t.monthValue !in months) { t = t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1); continue }
            if (!dayMatches(t)) { t = t.truncatedTo(ChronoUnit.DAYS).plusDays(1); continue }
            if (t.hour !in hours) { t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1); continue }
            if (t.minute !in minutes) { t = t.plusMinutes(1); continue }
            return t.toInstant().toEpochMilli()
        }
        return null
    }

    /** As próximas [count] execuções (para preview na UI). */
    fun upcoming(afterMillis: Long, zone: ZoneId, count: Int): List<Long> {
        val out = mutableListOf<Long>()
        var cur = afterMillis
        repeat(count) { cur = next(cur, zone) ?: return out; out += cur }
        return out
    }

    private fun dayMatches(t: ZonedDateTime): Boolean {
        val dom = t.dayOfMonth in days
        val dow = (t.dayOfWeek.value % 7) in weekdays
        return when {
            dayRestricted && weekdayRestricted -> dom || dow
            dayRestricted -> dom
            weekdayRestricted -> dow
            else -> true
        }
    }

    companion object {
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        /** Atalhos comuns. */
        private val MACROS = mapOf(
            "@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *",
            "@weekly" to "0 0 * * 0", "@monthly" to "0 0 1 * *", "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *",
        )

        fun parse(expr: String): Result<CronExpression> = runCatching {
            val src = MACROS[expr.trim().lowercase()] ?: expr.trim()
            val f = src.split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(f.size == 5) { "a expressão precisa de 5 campos (minuto hora dia mês dia-da-semana); recebi ${f.size}" }
            val wd = field(f[4], 0, 7, DAYS, "dia da semana").map { it % 7 }.toSet()
            CronExpression(
                field(f[0], 0, 59, null, "minuto"), field(f[1], 0, 23, null, "hora"),
                field(f[2], 1, 31, null, "dia do mês"), field(f[3], 1, 12, MONTHS, "mês", offset = 1), wd,
                dayRestricted = f[2] != "*" && f[2] != "?", weekdayRestricted = f[4] != "*" && f[4] != "?",
            )
        }

        /** Mensagem de erro em pt-BR, ou null se válida. */
        fun validate(expr: String): String? = parse(expr).exceptionOrNull()?.let { "Cron inválido: ${it.message}" }

        private fun field(text: String, min: Int, max: Int, names: List<String>?, label: String, offset: Int = 0): Set<Int> {
            val out = sortedSetOf<Int>()
            for (part in text.lowercase().split(',')) {
                require(part.isNotEmpty()) { "$label vazio" }
                val (range, stepTxt) = part.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step = stepTxt?.let { s -> s.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("passo inválido em $label: $s") } ?: 1
                val (lo, hi) = when {
                    range == "*" || range == "?" -> min to max
                    '-' in range -> range.split('-', limit = 2).let { value(it[0], min, max, names, label, offset) to value(it[1], min, max, names, label, offset) }
                    else -> value(range, min, max, names, label, offset).let { it to if (stepTxt != null) max else it }
                }
                require(lo <= hi) { "intervalo invertido em $label: $range" }
                for (v in lo..hi step step) out += v
            }
            return out
        }

        private fun value(t: String, min: Int, max: Int, names: List<String>?, label: String, offset: Int): Int {
            val v = t.toIntOrNull() ?: names?.indexOf(t)?.takeIf { it >= 0 }?.plus(offset)
                ?: throw IllegalArgumentException("valor inválido em $label: $t")
            require(v in min..max) { "$label fora do intervalo $min-$max: $v" }
            return v
        }
    }
}
