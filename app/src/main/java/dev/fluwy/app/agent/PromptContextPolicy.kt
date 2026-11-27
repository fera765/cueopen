package dev.fluwy.app.agent

/**
 * Regras locais de composição de contexto. Mantém instruções persistentes, dados recuperados e
 * handoffs de workers explicitamente separados sem depender de extensões proprietárias do provider.
 */
internal object PromptContextPolicy {
    val instructionHierarchy: String = dev.fluwy.app.prompts.Prompts.render("policy.1", mapOf<String, Any?>("lb" to "{{"))

    /** Limita fatos legados e recuperados ao mesmo orçamento total antes de inseri-los no prompt. */
    fun boundedFacts(
        agentFacts: List<String>,
        recalledFacts: List<String>,
        maxChars: Int,
        maxItems: Int = 20,
    ): List<String> {
        var remaining = maxChars.coerceAtLeast(0)
        if (remaining == 0 || maxItems <= 0) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (raw in agentFacts + recalledFacts) {
            if (out.size >= maxItems || remaining <= 0) break
            val fact = raw.trim().replace('\u0000', ' ').take(600)
            if (fact.isBlank() || !seen.add(fact.lowercase())) continue
            val cost = fact.length + 2 // prefixo "- " usado na apresentação
            if (cost <= remaining) {
                out += fact
                remaining -= cost
            } else if (remaining >= 40) {
                out += fact.take(remaining - 5).trimEnd() + "…"
                remaining = 0
            } else {
                break
            }
        }
        return out
    }

    /** Enquadra conteúdo recuperado como dado; escaping impede que a fonte feche o delimitador. */
    fun untrustedBlock(tag: String, content: String, maxChars: Int): String {
        val safeTag = tag.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,39}")) } ?: "context_data"
        val limit = maxChars.coerceAtLeast(0)
        val clipped = content.length > limit
        val body = content.take(limit)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return buildString {
            append('<').append(safeTag).appendLine('>')
            append(body)
            if (clipped) appendLine(dev.fluwy.app.prompts.Prompts.render("policy.2", mapOf<String, Any?>("lb" to "{{")))
            append("</").append(safeTag).append('>')
        }
    }

    /** Handoff curto: a tarefa é a instrução do worker; contexto do pai é apenas referência delimitada. */
    fun workerTask(
        task: String,
        parentRequest: String = "",
        parentSummary: String = "",
        plan: String = "",
    ): String = buildString {
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.3", mapOf<String, Any?>("lb" to "{{")))
        appendLine(untrustedBlock("assigned_task", task, 2_000))
        if (parentRequest.isNotBlank() || parentSummary.isNotBlank() || plan.isNotBlank()) {
            appendLine()
            appendLine(dev.fluwy.app.prompts.Prompts.render("policy.4", mapOf<String, Any?>("lb" to "{{")))
            if (parentRequest.isNotBlank()) appendLine(untrustedBlock("parent_request", parentRequest, 1_500))
            if (parentSummary.isNotBlank()) appendLine(untrustedBlock("parent_summary", parentSummary, 2_000))
            if (plan.isNotBlank()) appendLine(untrustedBlock("parent_plan", plan, 1_000))
        }
        appendLine()
        append(dev.fluwy.app.prompts.Prompts.render("policy.5", mapOf<String, Any?>("lb" to "{{")))
    }

    /** Tarefa de um agente do exército de build: mesmo workspace do líder, editando por partes. */
    fun squadTask(name: String, role: String, goal: String, task: String, target: String, team: String, parentRequest: String = "", previewUrl: String? = null, projectDir: String? = null, tailwind: Boolean = false, teamPlan: String = ""): String = buildString {
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.6", mapOf<String, Any?>("name" to (name), "role" to (role), "team" to (team), "lb" to "{{")))
        val root = projectDir?.trimEnd('/') ?: "/root/workspace"
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.7", mapOf<String, Any?>("root" to (root), "lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.8", mapOf<String, Any?>("root" to (root), "root_2" to (root), "lb" to "{{")))
        appendLine(untrustedBlock("team_goal", goal, 600))
        appendLine(untrustedBlock("assigned_task", task, 2_000))
        if (target.isNotBlank()) appendLine(dev.fluwy.app.prompts.Prompts.render("policy.9", mapOf<String, Any?>("target" to (target), "lb" to "{{")))
        if (parentRequest.isNotBlank()) appendLine(untrustedBlock("user_request", parentRequest, 1_200))
        if (teamPlan.isNotBlank()) { appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("policy.10", mapOf<String, Any?>("lb" to "{{"))); appendLine(teamPlan.take(6_000)); appendLine(dev.fluwy.app.prompts.Prompts.render("policy.11", mapOf<String, Any?>("lb" to "{{"))) }
        appendLine()
        appendLine("Regras do time:")
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.12", mapOf<String, Any?>("lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.13", mapOf<String, Any?>("lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.14", mapOf<String, Any?>("name" to (name.lowercase().replace(' ', '-')), "lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.15", mapOf<String, Any?>("lb" to "{{")))
        if (tailwind) appendLine(dev.fluwy.app.prompts.Prompts.render("policy.16", mapOf<String, Any?>("lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.17", mapOf<String, Any?>("lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.18", mapOf<String, Any?>("lb" to "{{")))
        if (previewUrl != null && previewUrl.startsWith("http")) appendLine(dev.fluwy.app.prompts.Prompts.render("policy.19", mapOf<String, Any?>("preview_url" to (previewUrl), "trim_end" to (previewUrl.trimEnd('/')), "lb" to "{{")))
        appendLine(dev.fluwy.app.prompts.Prompts.render("policy.20", mapOf<String, Any?>("lb" to "{{")))
        append(dev.fluwy.app.prompts.Prompts.render("policy.21", mapOf<String, Any?>("lb" to "{{")))
    }
}
