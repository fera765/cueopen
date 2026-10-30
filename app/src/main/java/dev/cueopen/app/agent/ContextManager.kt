package dev.cueopen.app.agent

import dev.cueopen.app.data.KnowledgeItem
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.MemoryItem
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.sandbox.Sandbox
import java.io.File

/**
 * Arquitetura de contexto do CueOpen (inspirada no gerenciamento de sessões longas do Claude Code):
 *
 * Camada 1 — Micro-compactação: saídas comuns de ferramentas antigas viram um marcador curto; o conteúdo
 *   completo fica salvo em disco (/root/.cue/out/<id>.txt) e pode ser relido sob demanda. Saídas Android
 *   de acessibilidade são exceção: existem só na memória do loop ativo e são omitidas do histórico/arquivos.
 * Camada 2 — Compactação estrutural: quando o histórico passa do orçamento, a parte antiga é
 *   resumida pelo modelo rápido num resumo estruturado (intenção, decisões, arquivos, erros,
 *   pendências, estado atual). A cauda recente fica literal, sem quebrar pares tool_call/tool.
 *   Circuit breaker: se o resumo falhar, descarta as rodadas mais antigas.
 * Camada 3 — Memória persistente: NOTES.md por tarefa (anotações do próprio agente), memória de
 *   longo prazo global/por Agent e knowledge, recuperadas por relevância (top-k), não inteiras.
 * Divulgação progressiva de skills: no prompt vai só o índice (nome + descrição); as instruções
 *   completas entram via `skill_load` quando necessárias.
 * Prefixo estável: a parte fixa do system prompt vem primeiro (data com granularidade de dia),
 *   o que aumenta o acerto do cache de prompt dos provedores.
 */
class ContextManager(private val store: Store, private val sandbox: Sandbox, private val llm: OpenRouter) {

    /** Estimativa deliberadamente conservadora: inclui uma margem para JSON, nomes de ferramentas e metadados. */
    fun estimateTokens(text: String?): Int = ((text?.length ?: 0) / 3.2).toInt() + 4
    fun estimateTokens(msgs: List<LlmMessage>): Int = msgs.sumOf {
        estimateTokens(it.content) + estimateTokens(it.toolCallsJson) + 8
    }

    // ---------- Camada 1 ----------
    /** Salva saídas comuns e devolve a versão de contexto; acessibilidade usa persistLongOutput=false. */
    fun storeToolOutput(sessionId: String, callId: String, output: String, limit: Int = 8_000, persistLongOutput: Boolean = true): String {
        if (output.length <= limit) return output
        if (!persistLongOutput) {
            val head = output.take(limit / 2)
            val tail = output.takeLast(limit / 2)
            return "$head\n…[${output.length - limit} caracteres omitidos; saída de acessibilidade grande não foi arquivada por privacidade]…\n$tail"
        }
        val dir = File(sandbox.workspaceFor(sessionId), ".cue/out").apply { mkdirs() }
        // nome pelo CONTEÚDO: a mesma saída sempre cai no mesmo arquivo (nada de cópias em cadeia)
        val f = File(dir, "o" + Integer.toHexString(output.hashCode()) + "_" + output.length + ".txt")
        if (!f.exists()) f.writeText(output) else f.setLastModified(System.currentTimeMillis())
        // limpeza: mantém só as 20 saídas mais recentes (evita acúmulo de arquivos quase iguais)
        runCatching { dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() } }
        val head = output.take(limit / 2)
        val tail = output.takeLast(limit / 2)
        val headLines = head.count { it == '\n' } + 1
        return "$head\n…[${output.length - limit} caracteres omitidos — saída completa em .cue/out/${f.name}. Para ver o trecho omitido use file_read path=.cue/out/${f.name} offset=$headLines (lê por partes; não releia do início)]…\n$tail"
    }

    /**
     * Antes de cada chamada: encolhe o que é antigo e irrelevante para NÃO inflar o input, mas sem PERDER nada.
     * Mantém inteiras as últimas [keepRecentTools] respostas de ferramenta; as mais antigas (> 240 chars) viram
     * um resumo curto com um ponteiro file_read para a saída completa salva em .cue/out — o agente só traz de volta
     * o que precisar. Narrações antigas do assistente também são encurtadas (não têm valor de estado). As mensagens
     * do usuário e os resultados recentes ficam intactos, preservando a qualidade.
    */
    fun microCompact(h: List<LlmMessage>, sessionId: String? = null, keepRecentTools: Int = 6): List<LlmMessage> {
        val safeHistory = ToolCallHistoryNormalizer.normalize(h)
        val toolIdx = safeHistory.indices.filter { safeHistory[it].role == "tool" }
        val oldTools = toolIdx.dropLast(keepRecentTools).toSet()
        val lastAssistant = safeHistory.indexOfLast { it.role == "assistant" }
        return safeHistory.mapIndexed { i, m ->
            when {
                i in oldTools && (m.content?.length ?: 0) > 240 -> {
                    val full = m.content ?: ""
                    if (m.name?.startsWith("device_accessibility_") == true) {
                        m.copy(content = "[Saída antiga de acessibilidade omitida do contexto; releia a tela atual se precisar.]")
                    } else {
                        val ptr = if (sessionId != null) stash(sessionId, full) else null
                        val head = full.take(200).trimEnd()
                        m.copy(content = head + "\n…[resultado antigo recolhido para não inflar o contexto" +
                            (if (ptr != null) " — recupere o conteúdo completo com file_read path=$ptr" else "") + "]")
                    }
                }
                // narração antiga do assistente (sem ferramentas): só o começo basta como fio da meada
                m.role == "assistant" && i != lastAssistant && m.toolCallsJson.isNullOrBlank() && (m.content?.length ?: 0) > 280 ->
                    m.copy(content = (m.content ?: "").take(200).trimEnd() + " …[resumido]")
                else -> m
            }
        }
    }

    /** Salva um conteúdo (endereçado pelo hash) em .cue/out e devolve o caminho relativo para file_read. */
    private fun stash(sessionId: String, content: String): String {
        val dir = File(sandbox.workspaceFor(sessionId), ".cue/out").apply { mkdirs() }
        val f = File(dir, "o" + Integer.toHexString(content.hashCode()) + "_" + content.length + ".txt")
        if (!f.exists()) runCatching { f.writeText(content) } else f.setLastModified(System.currentTimeMillis())
        runCatching { dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() } }
        return ".cue/out/${f.name}"
    }

    // ---------- Camada 2 ----------
    /** Compacta se o histórico estimado passar do orçamento. Retorna a sessão atualizada (ou a mesma). */
    suspend fun compactIfNeeded(s: Session, force: Boolean = false): Session {
        val normalized = ToolCallHistoryNormalizer.normalize(s.llm)
        val current = if (normalized != s.llm) {
            store.editSession(s.id) { it.copy(llm = normalized) }
            s.copy(llm = normalized)
        } else s
        val budget = store.settings.contextBudget
        if (!force && (!current.autoCompact || estimateTokens(current.llm) + estimateTokens(current.summary) < budget)) return current
        if (current.llm.size < 4) return current
        val keep = store.settings.keepRecentMessages.coerceAtLeast(4)
        // ponto de corte seguro: uma mensagem user/assistant (nunca no meio das respostas de ferramentas)
        var cut = (current.llm.size - keep).coerceAtLeast(1)
        while (cut > 0 && current.llm[cut].role == "tool") cut--
        if (cut <= 0) return current
        val head = current.llm.subList(0, cut)
        val tail = current.llm.subList(cut, current.llm.size)
        val transcript = buildString {
            head.forEach { m ->
                val body = if (m.role == "tool" && m.name?.startsWith("device_accessibility_") == true)
                    "[Conteúdo da tela omitido do resumo persistente por privacidade.]"
                else (m.content ?: "").take(1500)
                val calls = m.toolCallsJson?.let { " [chamou ferramentas: ${it.take(400)}]" }.orEmpty()
                append("${m.role.uppercase()}${m.name?.let { "($it)" } ?: ""}: $body$calls\n")
            }
        }.takeLast(60_000)
        val prompt = """
            Você está compactando o histórico de um agente autônomo que continuará trabalhando.
            Escreva um RESUMO ESTRUTURADO em português, denso e factual, com as seções:
            ## Intenção do usuário (pedido original e ajustes)
            ## Decisões e descobertas
            ## Arquivos e recursos (caminhos, URLs, comandos importantes)
            ## Erros e como foram resolvidos
            ## Pendências
            ## Estado atual (o que estava sendo feito no momento do corte)
            Preserve números, nomes, caminhos e URLs exatos. Não invente nada.

            Resumo anterior (se houver):
            ${current.summary.ifBlank { "(nenhum)" }}

            Histórico a compactar:
            $transcript
        """.trimIndent()
        val summary = runCatching { llm.quick(prompt, maxTokens = 1500, conversationId = current.id) }.getOrNull()
        val updated = if (!summary.isNullOrBlank()) {
            current.copy(llm = tail, summary = summary, compactions = current.compactions + 1)
        } else {
            // circuit breaker: perde as rodadas mais antigas, mas destrava a sessão
            current.copy(llm = tail, compactions = current.compactions + 1,
                summary = (current.summary + "\n[Parte antiga do histórico descartada por falha ao resumir]").trim())
        }
        store.editSession(current.id) { it.copy(llm = updated.llm, summary = updated.summary, compactions = updated.compactions) }
        return updated
    }

    // ---------- Camada 3 ----------
    fun notesFile(sessionId: String) = File(sandbox.workspaceFor(sessionId), "NOTES.md")
    fun readNotes(sessionId: String): String = notesFile(sessionId).takeIf { it.exists() }?.readText().orEmpty()

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    private fun score(query: Set<String>, text: String, tags: List<String> = emptyList()): Double {
        if (query.isEmpty()) return 0.0
        val w = words(text) + tags.map { it.lowercase() }
        val hit = query.count { it in w }
        return hit / Math.sqrt(w.size.coerceAtLeast(1).toDouble())
    }

    /** Recupera memórias relevantes com limite total de caracteres, não apenas limite por quantidade. */
    fun relevantMemories(query: String, agentId: String?, k: Int = 8, maxChars: Int = 2400): List<MemoryItem> {
        val q = words(query)
        val pool = store.state.value.memories.filter { it.agentId == null || it.agentId == agentId }
        val ranked = pool.map { it to score(q, it.text, it.tags) + it.uses * 0.01 }
            .sortedByDescending { it.second }.filter { it.second > 0 }.map { it.first }
        // Inclui algumas memórias recentes do agente, mas respeita o mesmo orçamento global.
        val recent = if (agentId == null) emptyList() else pool.filter { it.agentId == agentId }
            .sortedByDescending { it.createdAt }.take(3)
        val candidates = (ranked + recent).distinctBy { it.id }.take(k)
        var used = 0
        return candidates.filter { m ->
            val cost = m.text.length + m.tags.sumOf { it.length }
            if (used + cost > maxChars) false else { used += cost; true }
        }
    }

    /** Knowledge ranqueado e truncado por orçamento para impedir que documentos grandes explodam o prompt. */
    fun relevantKnowledge(query: String, k: Int = 5, maxChars: Int = 4500): List<KnowledgeItem> {
        val q = words(query)
        val ranked = store.state.value.knowledge.filter { it.enabled }
            .map { it to score(q, it.title + " " + it.content) }
            .sortedByDescending { it.second }
        var remaining = maxChars
        val result = mutableListOf<KnowledgeItem>()
        for ((item, relevance) in ranked) {
            if (result.size >= k || remaining <= 0) break
            if (relevance <= 0 && result.isNotEmpty()) continue
            val title = item.title.take(180)
            val overhead = title.length + 24
            val bodyLimit = minOf(1500, (remaining - overhead).coerceAtLeast(0))
            if (bodyLimit < 80) break
            val body = item.content.take(bodyLimit)
            result += item.copy(title = title, content = body)
            remaining -= title.length + body.length + 24
        }
        return result
    }

    fun searchMemories(query: String, agentId: String?): List<MemoryItem> = relevantMemories(query, agentId, 15)

    private fun jaccard(a: String, b: String): Double {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** Salva com deduplicação: fatos quase iguais atualizam o existente em vez de duplicar. */
    fun saveMemory(text: String, agentId: String?, tags: List<String> = emptyList()): String {
        val t = text.trim().take(500)
        if (t.isBlank()) return "Nada a salvar."
        val dup = store.state.value.memories.firstOrNull { (it.agentId == agentId) && jaccard(it.text, t) >= 0.7 }
        return if (dup != null) {
            store.upsertMemory(dup.copy(text = if (t.length >= dup.text.length) t else dup.text, tags = (dup.tags + tags).distinct(), lastUsedAt = System.currentTimeMillis()))
            "Memória existente atualizada."
        } else {
            store.upsertMemory(MemoryItem(text = t, agentId = agentId, tags = tags))
            // poda: mantém no máximo 400 memórias (remove as menos usadas e mais antigas)
            val all = store.state.value.memories
            if (all.size > 400) all.sortedWith(compareBy({ it.uses }, { it.lastUsedAt })).take(all.size - 400).forEach { store.deleteMemory(it.id) }
            "Memória salva."
        }
    }

    /** Marca memórias injetadas como usadas (ranking por uso/recência). */
    fun touch(items: List<MemoryItem>) {
        val now = System.currentTimeMillis()
        items.forEach { m -> store.upsertMemory(m.copy(uses = m.uses + 1, lastUsedAt = now)) }
    }

    /**
     * Extração em segundo plano ao concluir uma tarefa (modelo rápido): fatos duradouros sobre o
     * usuário, preferências e projetos viram memória de longo prazo. Barato e não bloqueia o chat.
     */
    suspend fun extractMemories(s: Session) {
        val convo = s.llm.filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
            .takeLast(16).joinToString("\n") { "${it.role}: ${(it.content ?: "").take(800)}" }
        if (convo.length < 200) return
        val existing = store.state.value.memories.takeLast(40).joinToString("\n") { "- ${it.text}" }
        val out = runCatching {
            llm.quick("""
                Extraia da conversa abaixo de 0 a 5 FATOS DURADOUROS úteis em conversas futuras: preferências do usuário,
                dados de projetos, decisões, contas/ferramentas que ele usa. NÃO inclua detalhes da tarefa pontual,
                dados sensíveis (senhas, documentos, saúde) nem o que já está nas memórias existentes.
                Responda só com uma lista, um fato por linha começando com "- ". Se não houver nada, responda NADA.

                Memórias existentes:
                $existing

                Conversa:
                $convo
            """.trimIndent(), maxTokens = 400, conversationId = s.id)
        }.getOrNull() ?: return
        out.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
            .filter { it.length in 8..400 }.take(5).forEach { saveMemory(it, null) }
    }
}
