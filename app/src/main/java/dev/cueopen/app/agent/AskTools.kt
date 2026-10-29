package dev.cueopen.app.agent

import dev.cueopen.app.data.QuestionOption
import dev.cueopen.app.data.QuestionSpec
import dev.cueopen.app.data.SecureField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `ask_question` (perguntas de múltipla escolha) e `ask_secure` (formulário seguro) — parte pura
 * (validação dos argumentos do modelo e texto das respostas), testável na JVM.
 */
object AskTools {
    const val MAX_QUESTIONS = 4
    const val MAX_FIELDS = 8
    val SECURE_TYPES = setOf("password", "token", "api_key", "email", "text", "number", "url")
    /** Tipos mostrados mascarados (••••). */
    val MASKED = setOf("password", "token", "api_key")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.bool(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: def

    /** Valida ask_question. Devolve (perguntas, erro). */
    fun parseQuestions(args: JsonObject): Pair<List<QuestionSpec>, String?> {
        val arr = args["questions"] as? JsonArray
            ?: return emptyList<QuestionSpec>() to "informe \"questions\": lista de 1 a $MAX_QUESTIONS perguntas."
        if (arr.isEmpty() || arr.size > MAX_QUESTIONS) return emptyList<QuestionSpec>() to "use de 1 a $MAX_QUESTIONS perguntas (recebi ${arr.size})."
        val out = ArrayList<QuestionSpec>()
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return emptyList<QuestionSpec>() to "questions[$i] deve ser um objeto."
            val q = o.str("question")
            if (q.length < 5) return emptyList<QuestionSpec>() to "questions[$i].question vazia."
            val opts = (o["options"] as? JsonArray).orEmpty().mapNotNull { oe ->
                when (oe) {
                    is JsonPrimitive -> oe.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { QuestionOption(it) }
                    is JsonObject -> oe.str("label").takeIf { it.isNotEmpty() }?.let { QuestionOption(it.take(80), oe.str("description").take(140)) }
                    else -> null
                }
            }.distinctBy { it.label.lowercase() }
            if (opts.size < 2 || opts.size > 4) return emptyList<QuestionSpec>() to "questions[$i]: dê de 2 a 4 opções claras e diferentes (recebi ${opts.size}). \"Outra resposta\" já aparece sozinha."
            out += QuestionSpec(q.take(200), o.str("header").take(16), opts, o.bool("multi_select", false), o.bool("allow_other", true))
        }
        return out to null
    }

    /** Valida ask_secure. Devolve (título, motivo, campos, erro). */
    fun parseSecure(args: JsonObject): Triple<String, List<SecureField>, String?> {
        val arr = args["fields"] as? JsonArray ?: return Triple("", emptyList(), "informe \"fields\": lista de 1 a $MAX_FIELDS campos.")
        if (arr.isEmpty() || arr.size > MAX_FIELDS) return Triple("", emptyList(), "use de 1 a $MAX_FIELDS campos (recebi ${arr.size}).")
        val fields = ArrayList<SecureField>()
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return Triple("", emptyList(), "fields[$i] deve ser um objeto.")
            val name = o.str("name").lowercase()
            if (!SecretVault.NAME.matches(name)) return Triple("", emptyList(), "fields[$i].name '$name' inválido: use snake_case [a-z0-9_] (ex.: api_key, email, senha).")
            if (fields.any { it.name == name }) return Triple("", emptyList(), "campo '$name' repetido.")
            val type = o.str("type").lowercase().ifEmpty { "password" }
            if (type !in SECURE_TYPES) return Triple("", emptyList(), "fields[$i].type '$type' inválido (use ${SECURE_TYPES.joinToString()}).")
            fields += SecureField(name, o.str("label").ifEmpty { name }.take(60), type, o.bool("required", true), o.str("hint").take(120))
        }
        return Triple(args.str("title").ifEmpty { "Dados seguros" }.take(80), fields, null)
    }

    /** Texto (visível e para o modelo) com as respostas do ask_question. */
    fun answersMessage(questions: List<QuestionSpec>, answers: List<List<String>>): String = buildString {
        append("Respostas:")
        questions.forEachIndexed { i, q ->
            val a = answers.getOrNull(i).orEmpty().filter { it.isNotBlank() }
            append("\n").append(i + 1).append(". ").append(q.header.ifBlank { q.question }).append(": ")
            append(if (a.isEmpty()) "(pulou — decida você e diga o que assumiu)" else a.joinToString(", "))
        }
    }

    const val DISMISSED_MESSAGE = "Fechei as perguntas sem responder. Siga com suposições razoáveis e diga quais assumiu."

    /**
     * Mensagem que vai para o chat/modelo depois do envio seguro: SÓ os nomes, nunca os valores.
     */
    fun secureSubmittedMessage(names: List<String>): String =
        "🔒 Enviei com segurança: ${names.joinToString()}. " +
            "(Os valores ficam só no aparelho e a IA não os vê. Use " +
            names.joinToString(", ") { "{{secret:$it}}" } + " nos argumentos das ferramentas.)"

    const val SECURE_CANCELLED = "Cancelei o formulário seguro e não enviei os dados."
}
