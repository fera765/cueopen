package dev.fluwy.app.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Relógio local para o agente (OpenBot 0.72): execuções agendadas recebem data, hora e fuso do aparelho,
 * para "hoje"/"ontem" não serem lidos em UTC (um resumo às 23:30 em São Paulo já seria o dia seguinte em UTC).
 */
object LocalClock {
    fun offset(tz: TimeZone, now: Long): String {
        val m = tz.getOffset(now) / 60_000
        val sign = if (m < 0) "-" else "+"
        val a = kotlin.math.abs(m)
        return "UTC%s%02d:%02d".format(sign, a / 60, a % 60)
    }

    fun zone(tz: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis()): String = "${tz.id} (${offset(tz, now)})"

    fun line(now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("EEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).apply { timeZone = tz }
        return "${f.format(Date(now))} em ${zone(tz, now)}. Leia \"hoje\", \"ontem\" e \"amanhã\" por este relógio."
    }
}
