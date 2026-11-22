package dev.cueopen.app.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Portão de qualidade dos fluxos (puro, testável na JVM):
 *  - estrutura antes de testar/publicar (destinatário de e-mail real, gatilho webhook completo);
 *  - conteúdo na execução: nada vazio, sem {{…}} sobrando, sem JSON cru para o usuário, destinatário válido.
 * Uma falha aqui vira erro do nó → o teste falha → o fluxo não pode ser publicado.
 */
object WfQuality {
    private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val PLACEHOLDER = Regex("(@(exemplo|example|teste|test|dominio|domain)\\.(com|org|net)(\\.br)?$)|(^(x|seu|your|email|fulano|nome|cliente|destino|user|usuario)@(y|email|dominio|domain)\\.)", RegexOption.IGNORE_CASE)

    fun isEmail(s: String) = EMAIL.matches(s.trim())
    fun isPlaceholderEmail(s: String) = PLACEHOLDER.containsMatchIn(s.trim())

    fun isEmailSend(n: WfNode) = n.type == "connector" && (n.config["acao"].orEmpty().contains("send", true) && (n.config["acao"].orEmpty().contains("mail", true) || n.config["conector"].orEmpty().contains("mail", true)))

    /** Destinatários de um modelo de campos (receiver/to/para), como texto. */
    fun recipients(campos: String): List<String> {
        val o = runCatching { Json.parseToJsonElement(campos) as JsonObject }.getOrNull() ?: return emptyList()
        val v = listOf("receiver", "to", "para", "recipients", "email").firstNotNullOfOrNull { o[it] } ?: return emptyList()
        return when (v) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> v.content.split(',', ';')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Problemas de estrutura (antes de testar/publicar). */
    fun structural(w: Workflow): List<String> {
        val out = mutableListOf<String>()
        w.nodes.forEach { n ->
            val lb = WfLogic.label(n)
            if (isEmailSend(n)) {
                val rec = recipients(n.config["campos"].orEmpty())
                when {
                    rec.isEmpty() -> out += "$lb: falta o e-mail de destino. Pergunte ao usuário (ask_question, ou ask_secure se for privado) e coloque em campos.receiver."
                    rec.any { !it.contains("{{") && (isPlaceholderEmail(it) || !isEmail(it)) } ->
                        out += "$lb: o destinatário \"${rec.first { !it.contains("{{") && (isPlaceholderEmail(it) || !isEmail(it)) }}\" não é um e-mail real. Pergunte ao usuário (ask_question/ask_secure)."
                }
                val o = runCatching { Json.parseToJsonElement(n.config["campos"].orEmpty()) as JsonObject }.getOrNull()
                if (o != null && listOf("subject", "assunto").none { k -> o[k]?.let { (it as? JsonPrimitive)?.content?.isNotBlank() } == true })
                    out += "$lb: o e-mail precisa de assunto (campos.subject)."
                if (o != null && listOf("body_text", "body", "body_html", "corpo", "text").none { k -> o[k]?.let { (it as? JsonPrimitive)?.content?.isNotBlank() } == true })
                    out += "$lb: o e-mail precisa de corpo (campos.body_text)."
            }
            if (n.type == "trigger_webhook") {
                val repo = n.config["repo"].orEmpty().trim()
                if (!Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(repo)) out += "$lb: informe o repositório no formato dono/repo."
                if ((n.config["issue"]?.toIntOrNull() ?: 0) < 1) out += "$lb: informe o número da issue que recebe as chamadas."
                if (n.config["secret"].orEmpty().trim().length < 12) out += "$lb: o segredo precisa de pelo menos 12 caracteres."
            }
            if (n.type == "notify" && n.config["text"].orEmpty().isBlank() && n.config["detail"].orEmpty().isBlank())
                out += "$lb: escreva o texto da notificação."
        }
        return out.distinct()
    }

    /** Conteúdo final que vai para o usuário (notificação, modal, página, chat, e-mail). null = ok. */
    fun contentProblem(what: String, text: String, allowJson: Boolean = false): String? {
        val t = text.trim()
        return when {
            t.isEmpty() -> "$what ficou vazio."
            Regex("\\{\\{[^}]*\\}\\}").containsMatchIn(t) -> "$what tem campos não preenchidos: ${Regex("\\{\\{[^}]*\\}\\}").find(t)!!.value}. Corrija o caminho e teste de novo."
            t.equals("null", true) || t.equals("undefined", true) || t == "[]" || t == "{}" -> "$what ficou sem conteúdo ($t)."
            !allowJson && WfFormat.isJson(t) -> "$what está como dados brutos (JSON). Use um nó de IA ou {{id | text}} para deixar legível."
            Regex("^\\s*(ERRO|ERROR|Exception|Traceback)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "$what contém uma mensagem de erro em vez do conteúdo."
            else -> null
        }
    }

    private val MISSING_INPUT = Regex(
        "(?i)(n[ãa]o (foi incluíd|foram incluíd|recebi|veio|há (nenhum|conteúdo))|está vazi|veio vazi|ficou vazi|campo .{0,30} vazio|" +
            "(cole|envie|forneça|mande) (o|a|os|as) (conteúdo|lista|texto|xml|feed|dados|títulos)|" +
            "(please )?(provide|paste) the (content|list|text|data)|no (content|data) (was )?provided)"
    )
    private val UNFINISHED = Regex(
        "(?i)^(vou|agora vou|vamos|deixa eu|preciso|primeiro,? vou|em seguida|let me|i('| a)ll|now i)\\b|" +
            "(vou (buscar|confirmar|verificar|abrir|pesquisar|tentar|checar|ler)[^.]*[:.…]?)$"
    )

    /** Saída de IA/agente que reclama de entrada ausente — sinal de caminho {{…}} errado. */
    fun aiOutputProblem(what: String, text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "$what ficou vazio."
        if (MISSING_INPUT.containsMatchIn(t.take(600))) return "$what diz que não recebeu os dados (\"${t.take(120)}\"). Confira o caminho {{…}} do nó anterior e teste de novo."
        return null
    }

    /** Resposta final do agente que é só narração de trabalho em andamento ("Vou confirmar…:"). */
    fun agentUnfinished(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val lastLine = t.lines().last { it.isNotBlank() }.trim()
        return (t.length < 600 && (UNFINISHED.containsMatchIn(t) || lastLine.endsWith(":"))) || UNFINISHED.containsMatchIn(lastLine) && lastLine.endsWith(":")
    }

    /** Checagem do e-mail já preenchido, logo antes de enviar. */
    fun emailProblem(campos: JsonObject): String? {
        val rec = when (val v = listOf("receiver", "to", "para").firstNotNullOfOrNull { campos[it] }) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            is JsonPrimitive -> v.content.split(',', ';').map { it.trim() }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
        if (rec.isEmpty()) return "E-mail sem destinatário."
        rec.firstOrNull { !isEmail(it) || isPlaceholderEmail(it) }?.let { return "Destinatário inválido: \"$it\"." }
        val subj = listOf("subject", "assunto").firstNotNullOfOrNull { campos[it]?.let { e -> (e as? JsonPrimitive)?.content } }.orEmpty()
        contentProblem("O assunto do e-mail", subj)?.let { return it }
        val body = listOf("body_text", "body", "body_html", "corpo", "text").firstNotNullOfOrNull { campos[it]?.let { e -> (e as? JsonPrimitive)?.content } }.orEmpty()
        contentProblem("O corpo do e-mail", body)?.let { return it }
        return null
    }

    // ───────── Webhook (ponte pelo GitHub: cada chamada = um comentário numa issue) ─────────
    data class Hook(val id: Long, val payload: String)

    /**
     * Comentários da issue (JSON da API do GitHub) → chamadas válidas e novas.
     * Só aceita comentário cujo corpo começa com o segredo (comparação em tempo constante); o resto é o payload.
     */
    fun parseHooks(json: String, secret: String, afterId: Long): List<Hook> {
        if (secret.length < 12) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(json) as JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            if (id <= afterId) return@mapNotNull null
            val body = (o["body"] as? JsonPrimitive)?.content.orEmpty().trim()
            val first = body.substringBefore('\n').trim()
            if (!constantEq(first, secret)) return@mapNotNull null
            Hook(id, body.substringAfter('\n', "").trim().take(20_000))
        }.sortedBy { it.id }
    }

    fun newestId(json: String): Long = runCatching {
        (Json.parseToJsonElement(json) as JsonArray).maxOfOrNull { (it as JsonObject)["id"]!!.jsonPrimitive.content.toLong() } ?: 0L
    }.getOrDefault(0L)

    private fun constantEq(a: String, b: String): Boolean {
        val x = a.toByteArray(); val y = b.toByteArray()
        var d = x.size xor y.size
        for (i in 0 until maxOf(x.size, y.size)) d = d or ((x.getOrElse(i) { 0 }.toInt()) xor (y.getOrElse(i) { 0 }.toInt()))
        return d == 0
    }

    // ───────── Teste "mock real": leituras de verdade, envios externos simulados ─────────
    private val READ_ACTION = Regex("(^|[_\\-. ])(get|list|search|read|find|fetch|query|retrieve|lookup|count|download|describe|show|view|check|status|watch|poll|info)([_\\-. ]|s?$)", RegexOption.IGNORE_CASE)
    private val SHELL_SEND = Regex("(curl\\b[^|;&]*(\\s-X\\s*(POST|PUT|PATCH|DELETE)|\\s(-d|--data|-F|--form|-T|--upload-file)\\b))|\\bwget\\b[^|;&]*--post|\\b(sendmail|mailx?|mutt|ssmtp|scp|rsync|sftp|ftp|nc|ncat|telnet)\\b|\\bgit\\s+push\\b|\\bgh\\s+(issue|pr|release|api)\\b", RegexOption.IGNORE_CASE)

    /**
     * true = o nó manda algo para fora (e-mail, POST, webhook, ação de escrita num app). No teste ele NÃO é acionado:
     * o conteúdo é validado e o envio é simulado. Na dúvida, conta como envio (mais seguro).
     */
    fun isExternalSend(type: String, config: Map<String, String>, renderedMethod: String = config["method"].orEmpty()): Boolean = when (type) {
        "connector" -> !READ_ACTION.containsMatchIn(config["acao"].orEmpty().ifBlank { "send" })
        "mcp" -> !READ_ACTION.containsMatchIn(config["tool"].orEmpty().ifBlank { "send" })
        "http" -> renderedMethod.trim().uppercase().ifBlank { "GET" } !in setOf("GET", "HEAD", "OPTIONS")
        "shell" -> SHELL_SEND.containsMatchIn(config["command"].orEmpty())
        else -> false
    }
}
