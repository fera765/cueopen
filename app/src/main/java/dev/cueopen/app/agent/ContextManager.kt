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
 * Camada 1 — Micro-compactação: saídas de ferramentas antigas viram um marcador curto; o conteúdo
 *   completo fica salvo em disco (/root/.cue/out/<id>.txt) e pode ser relido sob demanda.
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

    fun estimateTokens(text: String?): Int = ((text?.length ?: 0) / 3.6).toInt() + 4
    fun estimateTokens(msgs: List<LlmMessage>): Int = msgs.sumOf { estimateTokens(it.content) + estimateTokens(it.toolCallsJson) }

    // ---------- Camada 1 ----------
    /** Salva a saída completa da ferramenta e devolve a versão que entra no contexto. */
    fun storeToolOutput(sessionId: String, callId: String, output: String, limit: Int = 8_000): String {
        if (output.length <= limit) return output
        val dir = File(sandbox.publicDir, ".cue/out").apply { mkdirs() }
        val f = File(dir, "${sessionId}_${callId.takeLast(12)}.txt")
        f.writeText(output)
        val head = output.take(limit / 2)
        val tail = output.takeLast(limit / 2)
        return "$head\n…[${output.length - limit} caracteres omitidos — saída completa em /root/.cue/out/${f.name}; use file_read]…\n$tail"
    }

    /** Antes de cada chamada: limpa resultados de ferramentas antigos (mantém os mais recentes). */
    fun microCompact(h: List<LlmMessage>, keepRecentTools: Int = 6): List<LlmMessage> {
        val toolIdx = h.indices.filter { h[it].role == "tool" }
        val old = toolIdx.dropLast(keepRecentTools).toSet()
        return h.mapIndexed { i, m ->
            if (i in old && (m.content?.length ?: 0) > 300)
                m.copy(content = (m.content ?: "").take(220).trimEnd() + "\n…[resultado antigo compactado]")
            else m
        }
    }

    // ---------- Camada 2 ----------
    /** Compacta se o histórico estimado passar do orçamento. Retorna a sessão atualizada (ou a mesma). */
    suspend fun compactIfNeeded(s: Session, force: Boolean = false): Session {
        val budget = store.settings.contextBudget
        if (!force && (!s.autoCompact || estimateTokens(s.llm) + estimateTokens(s.summary) < budget)) return s
        if (s.llm.size < 4) return s
        val keep = store.settings.keepRecentMessages.coerceAtLeast(4)
        // ponto de corte seguro: uma mensagem user/assistant (nunca no meio das respostas de ferramentas)
        var cut = (s.llm.size - keep).coerceAtLeast(1)
        while (cut > 0 && s.llm[cut].role == "tool") cut--
        if (cut <= 0) return s
        val head = s.llm.subList(0, cut)
        val tail = s.llm.subList(cut, s.llm.size)
        val transcript = buildString {
            head.forEach { m ->
                val body = (m.content ?: "").take(1500)
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
            ${s.summary.ifBlank { "(nenhum)" }}

            Histórico a compactar:
            $transcript
        """.trimIndent()
        val summary = runCatching { llm.quick(prompt, maxTokens = 1500) }.getOrNull()
        val updated = if (!summary.isNullOrBlank()) {
            s.copy(llm = tail, summary = summary, compactions = s.compactions + 1)
        } else {
            // circuit breaker: perde as rodadas mais antigas, mas destrava a sessão
            s.copy(llm = tail, compactions = s.compactions + 1,
                summary = (s.summary + "\n[Parte antiga do histórico descartada por falha ao resumir]").trim())
        }
        store.editSession(s.id) { it.copy(llm = updated.llm, summary = updated.summary, compactions = updated.compactions) }
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

    fun relevantMemories(query: String, agentId: String?, k: Int = 8): List<MemoryItem> {
        val q = words(query)
        val pool = store.state.value.memories.filter { it.agentId == null || it.agentId == agentId }
        val ranked = pool.map { it to score(q, it.text, it.tags) + it.uses * 0.01 }.sortedByDescending { it.second }
        // sempre inclui as 3 mais recentes do Agent para continuidade
        val recent = pool.filter { it.agentId == agentId && agentId != null }.sortedByDescending { it.createdAt }.take(3)
        return (ranked.filter { it.second > 0 }.map { it.first }.take(k) + recent).distinctBy { it.id }.take(k)
    }

    fun relevantKnowledge(query: String, k: Int = 5): List<KnowledgeItem> {
        val q = words(query)
        val all = store.state.value.knowledge.filter { it.enabled }
        if (all.size <= k) return all
        return all.map { it to score(q, it.title + " " + it.content) }.sortedByDescending { it.second }.take(k).map { it.first }
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
            """.trimIndent(), maxTokens = 400)
        }.getOrNull() ?: return
        out.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
            .filter { it.length in 8..400 }.take(5).forEach { saveMemory(it, null) }
    }
}
