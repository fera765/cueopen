package dev.fluwy.app.agent

import kotlinx.serialization.json.Json

/** Verificação de sintaxe de arquivos estruturados após gravar/editar (o "linter na edição" do SWE-agent). */
object SyntaxCheck {
    private val json = Json

    /** Descrição do problema de sintaxe, ou null se o arquivo é válido ou o tipo não é verificado. */
    fun problem(name: String, content: String?): String? {
        if (content == null || content.isBlank()) return null
        val n = name.lowercase()
        return when {
            n.endsWith(".json") -> runCatching { json.parseToJsonElement(content); null }.getOrElse { "JSON inválido: " + (it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "erro de leitura") }
            n.endsWith(".jsonl") || n.endsWith(".ndjson") -> content.lineSequence().withIndex().filter { it.value.isNotBlank() }
                .firstOrNull { (_, line) -> runCatching { json.parseToJsonElement(line) }.isFailure }?.let { "JSONL inválido na linha ${it.index + 1}" }
            else -> null
        }
    }

    /** Problema introduzido pela edição: o arquivo era válido antes e deixou de ser. */
    fun brokenBy(name: String, before: String?, after: String?): String? =
        if (problem(name, before) == null) problem(name, after) else null
}
