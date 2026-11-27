package dev.fluwy.app.voice

/**
 * Prepara qualquer texto para a fala, sem lista fixa de palavras.
 * 1) Detecta o idioma de cada frase (pt/en) por estatística de palavras funcionais, acentos e padrões de letras.
 * 2) Converte o que não é "fala" (markdown, código, URLs, e-mails, siglas, identificadores, símbolos) por regras gerais do idioma.
 * 3) Números, horas, datas, moedas e unidades ficam para o normalizador do Phonon do mesmo idioma.
 */
object SpeechText {
    data class Segment(val lang: String, val text: String)

    private val PT = setOf("de", "que", "não", "nao", "você", "voce", "para", "com", "uma", "um", "os", "as", "é", "e", "está", "eu", "isso", "mais", "por",
        "seu", "sua", "como", "já", "também", "olá", "oi", "obrigado", "do", "da", "no", "na", "em", "se", "ao", "foi", "são", "tem", "mas", "pra", "muito", "aqui")
    private val EN = setOf("the", "and", "you", "is", "to", "of", "it", "that", "for", "with", "are", "this", "your", "i'm", "can", "hello", "hi", "thanks",
        "what", "have", "be", "was", "will", "on", "in", "my", "we", "they", "do", "don't", "it's", "there", "would", "about", "just", "here", "i")

    /** Pontua pt × en. Positivo = inglês. */
    private fun score(text: String): Int {
        val low = text.lowercase()
        val w = Regex("[\\p{L}']+").findAll(low).map { it.value }.toList()
        var s = w.count { it in EN } * 2 - w.count { it in PT } * 2
        s -= Regex("[ãõçáéíóúâêôà]").findAll(low).count() * 2          // acentos do português
        s += Regex("\\b\\w*(th|wh|ght|ing|ould|'s|'re|'ll|'ve)\\b").findAll(low).count()
        s -= Regex("\\b\\w*(ção|ções|nh|lh|mente|inho|inha)\\b").findAll(low).count()
        return s
    }

    fun detect(text: String, fallback: String = "pt"): String {
        val s = score(text)
        return when { s >= 2 -> "en"; s <= -1 -> "pt"; else -> fallback }
    }

    /** Divide em frases com idioma; frases curtas/ambíguas herdam o idioma anterior. Junta vizinhas do mesmo idioma. */
    fun segments(text: String, base: String = detect(text)): List<Segment> {
        val parts = Regex("(?<=[.!?…:;\\n])\\s+").split(text).filter { it.isNotBlank() }
        val out = ArrayList<Segment>(); var cur = base
        for (p in parts) {
            val words = Regex("[\\p{L}']+").findAll(p).count()
            if (words >= 3) cur = detect(p, cur)
            val clean = clean(p, cur); if (clean.isBlank()) continue
            if (out.isNotEmpty() && out.last().lang == cur) out[out.lastIndex] = Segment(cur, out.last().text + " " + clean) else out += Segment(cur, clean)
        }
        return out
    }

    private val LETTERS_PT = mapOf('a' to "á", 'b' to "bê", 'c' to "cê", 'd' to "dê", 'e' to "é", 'f' to "éfe", 'g' to "gê", 'h' to "agá", 'i' to "í",
        'j' to "jota", 'k' to "cá", 'l' to "éle", 'm' to "ême", 'n' to "êne", 'o' to "ó", 'p' to "pê", 'q' to "quê", 'r' to "érre", 's' to "ésse",
        't' to "tê", 'u' to "ú", 'v' to "vê", 'w' to "dáblio", 'x' to "xis", 'y' to "ípsilon", 'z' to "zê")

    private fun vowels(s: String) = s.count { it.lowercaseChar() in "aeiou" }

    /** Sigla em maiúsculas: lê como palavra se for pronunciável (ex.: PIX, NASA), senão soletra no idioma. */
    private fun acronym(w: String, lang: String): String {
        val pronounceable = w.length >= 3 && vowels(w) * 2 >= w.length - 1 && !Regex("[^AEIOU]{3}").containsMatchIn(w)
        if (pronounceable) return w.lowercase().replaceFirstChar { it.uppercase() }
        return if (lang == "pt") w.lowercase().map { LETTERS_PT[it] ?: it.toString() }.joinToString(" ")
        else w.toCharArray().joinToString(" ")   // o normalizador em inglês lê letras isoladas pelo nome
    }

    private fun host(h: String, lang: String): String {
        val dot = if (lang == "pt") " ponto " else " dot "
        return h.removePrefix("www.").split('.').filter { it.isNotBlank() }.joinToString(dot) { p -> if (p.length <= 3 && p.all { it.isLetter() }) p.lowercase() else p }
    }

    fun clean(text: String, lang: String): String {
        var t = text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]{1,40})`"), "$1")
            .replace(Regex("`[^`]*`"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("^\\s*(#{1,6}|[-•*+]|\\d+[.)])\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]"), " ")
        // E-mail e site: fala só o essencial.
        t = t.replace(Regex("\\b([\\w.+-]+)@([\\w-]+(?:\\.[\\w-]+)+)\\b")) { m ->
            m.groupValues[1].replace('.', ' ') + (if (lang == "pt") " arroba " else " at ") + host(m.groupValues[2], lang)
        }
        t = t.replace(Regex("\\bhttps?://([\\w-]+(?:\\.[\\w-]+)+)\\S*")) { m -> host(m.groupValues[1], lang) }
        t = t.replace(Regex("\\bwww\\.([\\w-]+(?:\\.[\\w-]+)+)\\S*")) { m -> host(m.groupValues[1], lang) }
        // Identificadores de código: camelCase / snake_case / kebab → palavras.
        t = t.replace(Regex("\\b[a-z]+(?:[A-Z][a-z0-9]+)+\\b")) { m -> m.value.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase() }
        t = t.replace(Regex("\\b\\w+(?:_\\w+)+\\b")) { m -> m.value.replace('_', ' ') }
        // Siglas (2–6 maiúsculas, com plural "s" opcional).
        t = t.replace(Regex("\\b([A-Z]{2,6})(s?)\\b")) { m -> acronym(m.groupValues[1], lang) + m.groupValues[2] }
        // Símbolos soltos que viram palavra; o resto que não se fala sai.
        val and = if (lang == "pt") " e " else " and "
        t = t.replace(Regex("\\s*&\\s*"), and)
            .replace(Regex("(?<=\\p{L})/(?=\\p{L})"), if (lang == "pt") " ou " else " or ")
            .replace(Regex("\\s*(->|=>|→)\\s*"), ", ")
            .replace(Regex("[*_#`>|~^{}\\[\\]\\\\<]+"), " ")
            .replace(Regex("\\(\\s*\\)"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("\\s+"), " ").trim()
        return t
    }
}
