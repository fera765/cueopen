package dev.fluwy.app.uiconnector.custom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.text.Normalizer

/** Evidência não secreta de que a definição exata passou pelo pipeline local offline. */
@Serializable
data class ConnectorValidationAttestation(
    val definitionSha256: String,
    val policyVersion: Int,
    val testedAtMillis: Long,
    val checks: List<String>,
    val summary: String = "",
)

object ConnectorAttestationPolicy {
    const val CURRENT_VERSION = 2
    val REQUIRED_CHECKS = setOf(
        "schema",
        "static_guardrails",
        "mock_requests",
        "mock_outputs",
        "capability_dry_run",
        "external_effects_held",
    )

    fun digest(yaml: String): String {
        val parsed = ConnectorYamlCodec.parse(CustomDefParser.stripFences(yaml))
        val canonical = canonical(parsed)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun issue(yaml: String, checks: Set<String>, now: Long = System.currentTimeMillis()): ConnectorValidationAttestation =
        ConnectorValidationAttestation(
            definitionSha256 = digest(yaml),
            policyVersion = CURRENT_VERSION,
            testedAtMillis = now,
            checks = checks.sorted(),
            summary = "Dry-run YAML offline: esquema, guardrails, requests e saídas simulados; sem chamadas externas ou execução de código gerado.",
        )

    fun isInstallable(yaml: String, proof: ConnectorValidationAttestation?): Boolean {
        if (proof == null || proof.policyVersion != CURRENT_VERSION) return false
        if (proof.testedAtMillis <= 0L || proof.definitionSha256.length != 64) return false
        if (!proof.checks.containsAll(REQUIRED_CHECKS)) return false
        return runCatching { digest(yaml) == proof.definitionSha256 }.getOrDefault(false)
    }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }
}

data class VslValidationResult(
    val passed: Boolean,
    val wordCount: Int,
    val minWords: Int,
    val maxWords: Int,
    val errors: List<String>,
)

/** Checagem verificável de contrato VSL; duração é estimativa por palavras, não garantia de tempo exato. */
object VslOutputGuardrails {
    private val wordPattern = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val languageWords = mapOf(
        "pt" to setOf("ao", "as", "com", "da", "das", "de", "do", "dos", "e", "em", "esta", "este", "mais", "mas", "na", "nas", "no", "nos", "o", "os", "para", "por", "que", "se", "seu", "sua", "um", "uma", "você"),
        "en" to setOf("a", "an", "and", "are", "as", "be", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was", "we", "with", "you", "your"),
        "es" to setOf("a", "al", "con", "como", "de", "del", "el", "en", "es", "esta", "la", "las", "lo", "los", "mas", "para", "por", "que", "se", "su", "un", "una", "y"),
        "fr" to setOf("au", "avec", "ce", "ces", "dans", "de", "des", "du", "elle", "et", "est", "la", "le", "les", "mais", "ou", "par", "pour", "que", "sur", "un", "une", "vous"),
    )

    fun validate(text: String, args: Map<String, String>, policy: VslOutputPolicy): VslValidationResult {
        val errors = ArrayList<String>()
        val minutes = args[policy.durationParam]?.trim()?.replace(',', '.')?.toDoubleOrNull()
        if (minutes == null || minutes !in 1.0..240.0) {
            errors += "A duração em '${policy.durationParam}' precisa estar entre 1 e 240 minutos."
        }
        val words = wordPattern.findAll(text.take(1_000_000)).count()
        if (text.length > 1_000_000) errors += "A saída excede o limite de 1 MB para validação."
        val min = if (minutes != null && minutes in 1.0..240.0) (minutes * policy.wordsPerMinute * (1.0 - policy.tolerance)).toInt() else 0
        val max = if (minutes != null && minutes in 1.0..240.0) kotlin.math.ceil(minutes * policy.wordsPerMinute * (1.0 + policy.tolerance)).toInt() else 0
        if (min > 0 && words !in min..max) errors += "Duração estimada fora da faixa: $words palavras; esperado $min–$max para ${minutes!!.toInt()} min a ${policy.wordsPerMinute} palavras/min (±${(policy.tolerance * 100).toInt()}%)."

        val language = args[policy.languageParam].orEmpty().trim()
        val langKey = languageKey(language)
        if (language.isBlank()) errors += "Informe o idioma em '${policy.languageParam}'."
        else if (langKey == null) errors += "Validação local de idioma ainda não cobre '$language'; escolha português, inglês, espanhol ou francês."
        else {
            val tokens = wordPattern.findAll(normalize(text)).map { it.value }.toList()
            val hits = tokens.count { it in languageWords.getValue(langKey) }
            val threshold = maxOf(2, (tokens.size * 0.012).toInt())
            if (hits < threshold) errors += "A saída não parece estar em $language (heurística local: $hits marcadores; mínimo $threshold)."
        }

        val normalizedText = " " + normalize(text) + " "
        val headings = text.lineSequence().map { normalize(it.trim().trimStart('#').trim().removeSuffix(":").trim()) }.filter { it.isNotBlank() }.toList()
        var lastSectionIndex = -1
        for (section in policy.requiredSections) {
            val normalizedSection = normalize(section)
            val index = headings.indexOfFirst { it == normalizedSection || it.startsWith("$normalizedSection ") }
            if (normalizedSection.isNotBlank() && index < 0) {
                errors += "Falta a seção/cabeçalho obrigatório '${section.take(80)}'."
            } else if (index >= 0 && index <= lastSectionIndex) {
                errors += "A seção '${section.take(80)}' está fora da ordem definida em requiredSections."
            } else if (index >= 0) {
                lastSectionIndex = index
            }
        }
        if (policy.forbiddenTermsParam.isNotBlank()) {
            val forbidden = args[policy.forbiddenTermsParam].orEmpty()
                .split(Regex("[,;\\n\\r]"))
                .map { normalize(it.trim()) }
                .filter { it.isNotBlank() }
            for (term in forbidden) {
                if (normalizedText.contains(" $term ")) errors += "A saída contém termo proibido: '$term'."
            }
        }
        return VslValidationResult(errors.isEmpty(), words, min, max, errors)
    }

    fun sample(minutes: Int, language: String, policy: VslOutputPolicy, args: Map<String, String> = emptyMap()): String {
        val target = (minutes * policy.wordsPerMinute).coerceAtLeast(1)
        val sectionText = policy.requiredSections.joinToString("\n") { "## $it\n" }
        val seed = when (languageKey(language)) {
            "en" -> "The offer helps you build a clear, practical plan for your goals, with useful steps and realistic expectations. "
            "es" -> "La propuesta ayuda a crear un plan claro y práctico para tus objetivos, con pasos útiles y expectativas realistas. "
            "fr" -> "Cette proposition aide à construire un plan clair et pratique, avec des étapes utiles et des attentes réalistes. "
            else -> "A proposta ajuda você a construir um plano claro e prático, com passos úteis e expectativas realistas para sua rotina. "
        }
        val rawContent = buildString {
            append(sectionText)
            val current = wordPattern.findAll(this).count()
            val perSeed = wordPattern.findAll(seed).count().coerceAtLeast(1)
            repeat(kotlin.math.ceil((target - current).coerceAtLeast(0).toDouble() / perSeed).toInt()) { append(seed) }
        }
        val forbidden = if (policy.forbiddenTermsParam.isBlank()) emptyList() else args[policy.forbiddenTermsParam].orEmpty().split(Regex("[,;\\n\\r]")).map { it.trim() }.filter { it.isNotBlank() }
        return forbidden.fold(rawContent) { text, term ->
            val replacement = List(wordPattern.findAll(term).count().coerceAtLeast(1)) { "alternativa" }.joinToString(" ")
            text.replace(Regex(Regex.escape(term), RegexOption.IGNORE_CASE), replacement)
        }
    }

    private fun languageKey(language: String): String? {
        val l = normalize(language)
        return when {
            l == "pt" || l.startsWith("pt ") || l.contains("portugues") || l.startsWith("brasileiro") -> "pt"
            l == "en" || l.startsWith("en ") || l.contains("english") || l.contains("ingles") -> "en"
            l == "es" || l.startsWith("es ") || l.contains("espanol") || l.contains("castellano") -> "es"
            l == "fr" || l.startsWith("fr ") || l.contains("frances") -> "fr"
            else -> null
        }
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
