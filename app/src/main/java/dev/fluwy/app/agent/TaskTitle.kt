package dev.fluwy.app.agent

/** Título da conversa a partir do próprio pedido, sem chamar o modelo (uma chamada a menos por conversa). */
object TaskTitle {
    private val lead = Regex(
        "^(oi|ol[aá]|bom dia|boa tarde|boa noite|e a[ií]|hey|hello|hi|por favor|pf|ent[aã]o|bom|beleza|vamos l[aá]|seguinte|claude|fluwy)[,!.:\\s]+",
        RegexOption.IGNORE_CASE)
    private val ask = Regex(
        "^(eu\\s+)?(quero|queria|gostaria|preciso|necessito)( muito)?( que (voc[eê]|vc))?( me)?\\s+|^(voc[eê]|vc) (pode|poderia|consegue)( me)?\\s+|^(me )?ajud[ae] a\\s+|^(pode|poderia)( me)?\\s+",
        RegexOption.IGNORE_CASE)

    fun make(text: String, max: Int = 48): String {
        var t = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
        repeat(3) { t = t.replace(lead, "").trim() }
        t = t.replace(ask, "").trim().trimEnd('.', '!', '?', ',', ';', ':')
        if (t.isBlank()) return "Nova conversa"
        if (t.length > max) t = t.take(max).substringBeforeLast(' ').ifBlank { t.take(max) } + "…"
        return t.replaceFirstChar { it.uppercase() }
    }
}
