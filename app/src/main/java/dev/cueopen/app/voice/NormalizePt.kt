package dev.cueopen.app.voice

/** Normalizador de texto pt-BR para a voz (espelho de tools/voice_lab3/normalize_pt.py): números, dinheiro, horas, datas, %, ordinais, abreviações. */
object NormalizePt {
    private val UN = "zero um dois três quatro cinco seis sete oito nove dez onze doze treze catorze quinze dezesseis dezessete dezoito dezenove".split(" ")
    private val DEZ = "_ _ vinte trinta quarenta cinquenta sessenta setenta oitenta noventa".split(" ")
    private val CEN = "_ cento duzentos trezentos quatrocentos quinhentos seiscentos setecentos oitocentos novecentos".split(" ")
    private val MES = "janeiro fevereiro março abril maio junho julho agosto setembro outubro novembro dezembro".split(" ")
    private val ORD = mapOf(1 to "primeiro", 2 to "segundo", 3 to "terceiro", 4 to "quarto", 5 to "quinto", 6 to "sexto", 7 to "sétimo", 8 to "oitavo", 9 to "nono", 10 to "décimo")
    private val ABBR = linkedMapOf("Sr." to "senhor", "Sra." to "senhora", "Dr." to "doutor", "Dra." to "doutora", "Av." to "avenida", "nº" to "número", "n.º" to "número",
        "km" to "quilômetros", "kg" to "quilos", "min" to "minutos", "etc." to "etcétera", "obs." to "observação", "tel." to "telefone",
        "vc" to "você", "pq" to "porque", "tb" to "também", "WhatsApp" to "uótsap", "e-mails" to "i-meils", "emails" to "i-meils", "e-mail" to "i-meil", "email" to "i-meil", "OK" to "oquei", "ok" to "oquei")
    private const val FEM = "(horas?|semanas?|pessoas?|vez|vezes|mensagens?|tarefas?|reuniões|reunião|notificações|notificação|páginas?)"

    private fun ate999(n: Int): String {
        if (n == 100) return "cem"
        val p = ArrayList<String>(); val c = n / 100; val r = n % 100
        if (c > 0) p += CEN[c]
        if (r > 0) p += if (r < 20) UN[r] else DEZ[r / 10] + (if (r % 10 > 0) " e " + UN[r % 10] else "")
        return p.joinToString(" e ")
    }

    fun num(v: Long, fem: Boolean = false): String {
        if (v < 0) return "menos " + num(-v, fem)
        if (v == 0L) return "zero"
        var n = v; val parts = ArrayList<String>()
        for ((value, sg, pl) in listOf(Triple(1_000_000_000L, "um bilhão", "bilhões"), Triple(1_000_000L, "um milhão", "milhões"), Triple(1000L, "mil", "mil"))) {
            val q = n / value; n %= value
            if (q > 0) parts += if (q == 1L) sg else "${if (q < 1000) ate999(q.toInt()) else num(q)} $pl"
        }
        if (n > 0) parts += ate999(n.toInt())
        var s = if (parts.size > 1 && n > 0 && (n < 100 || n % 100 == 0L)) parts.dropLast(1).joinToString(", ") + " e " + parts.last() else parts.joinToString(" ")
        if (fem) s = s.replace(Regex("\\bum\\b"), "uma").replace(Regex("\\bdois\\b"), "duas").replace("ntos", "ntas")
        return s
    }

    private fun int(s: String) = s.replace(".", "").toLongOrNull() ?: 0L
    private fun dec(s: String): String {
        if (',' in s) { val (a, b) = s.split(",", limit = 2); return "${num(int(a))} vírgula ${if (b.length > 2) b.map { UN[it - '0'] }.joinToString(" ") else num(b.toLong())}" }
        return num(int(s))
    }

    fun normalize(input: String): String {
        var t = input
        t = t.replace(Regex("https?://\\S+"), "um link")
        t = t.replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")) { it.value.replace("@", " arroba ").replace(".", " ponto ") }
        t = t.replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
        t = t.replace(Regex("[*_#`>|]+"), " ")
        t = t.replace(Regex("R\\$\\s?(\\d{1,3}(?:\\.\\d{3})*|\\d+)(?:,(\\d{1,2}))?")) { m ->
            val r = int(m.groupValues[1]); val c = m.groupValues[2].padEnd(2, '0').take(2).toIntOrNull() ?: 0
            var s = if (r > 0) "${num(r)} ${if (r == 1L) "real" else "reais"}" else ""
            if (c > 0) s += (if (s.isNotEmpty()) " e " else "") + "${num(c.toLong())} ${if (c == 1) "centavo" else "centavos"}"
            s.ifEmpty { "zero reais" }
        }
        t = t.replace(Regex("\\b(\\d{1,2})h(\\d{2})?\\b|\\b(\\d{1,2}):(\\d{2})\\b")) { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toLong(); val mi = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toLongOrNull() ?: 0
            "${num(h, true)} ${if (h == 1L) "hora" else "horas"}" + (if (mi > 0) " e ${num(mi)}" else "")
        }
        t = t.replace(Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3]
            if (mo !in 1..12 || d !in 1..31) m.value
            else "${if (d == 1) "primeiro" else num(d.toLong())} de ${MES[mo - 1]}" + (if (y.isNotEmpty()) " de ${num(if (y.length == 4) y.toLong() else 2000 + y.toLong())}" else "")
        }
        t = t.replace(Regex("(\\d+(?:,\\d+)?)\\s?%")) { "${dec(it.groupValues[1])} por cento" }
        t = t.replace(Regex("\\b(\\d{1,2})([ºª°])")) { m ->
            val n = m.groupValues[1].toInt(); val w = ORD[n] ?: num(n.toLong())
            if (m.groupValues[2] == "ª" && w.endsWith("o")) w.dropLast(1) + "a" else w
        }
        for ((k, v) in ABBR) t = t.replace(Regex("(?<!\\w)" + Regex.escape(k) + "(?!\\w)"), v)
        t = t.replace(Regex("\\b\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?\\b|\\b\\d+,\\d+\\b|\\b\\d+\\b")) { dec(it.value) }
        t = t.replace(Regex("\\bum $FEM")) { "uma ${it.groupValues[1]}" }.replace(Regex("\\bdois $FEM")) { "duas ${it.groupValues[1]}" }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
