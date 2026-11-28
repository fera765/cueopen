package dev.fluwy.app.agent

import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.MemoryItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Store
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.sandbox.Sandbox
import java.io.File

/**
 * Arquitetura de contexto do Fluwy (inspirada no gerenciamento de sessões longas do Claude Code):
 *
 * Camada 1 — Micro-compactação: saídas comuns de ferramentas antigas viram um marcador curto; o conteúdo
 *   completo fica salvo em disco (/root/.fluwy/out/<id>.txt) e pode ser relido sob demanda. Saídas Android
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
    /**
     * Arquivos de .fluwy/out que guardam SNAPSHOTS DO BROWSER (nome do arquivo → quando foi capturado). Uma página
     * muda; reler um snapshot velho faz o agente agir sobre um estado que não existe mais. O file_read consulta
     * este registro para barrar a releitura, a não ser que ela seja pedida de propósito.
     */
    private val browserSnapshots = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Ferramentas cujo resultado é um retrato da página naquele instante. */
    fun isBrowserSnapshotTool(name: String?): Boolean = name != null && name.startsWith("browser_") && name != "browser_network" && name != "browser_console"

    private fun markBrowserSnapshot(path: String) {
        browserSnapshots.putIfAbsent(path.substringAfterLast('/'), System.currentTimeMillis())
        if (browserSnapshots.size > 600) browserSnapshots.entries.sortedBy { it.value }.take(200).forEach { browserSnapshots.remove(it.key) }
    }

    /** Minutos desde a captura, se [path] aponta para um snapshot de browser arquivado; null caso contrário. */
    fun staleBrowserSnapshotMinutes(path: String): Long? {
        if (!path.contains(".fluwy/out/")) return null
        val at = browserSnapshots[path.substringAfterLast('/')] ?: return null
        return (System.currentTimeMillis() - at) / 60_000
    }

    fun storeToolOutput(sessionId: String, callId: String, output: String, limit: Int = 8_000, persistLongOutput: Boolean = true, toolName: String? = null): String {
        if (output.length <= limit) return output
        if (!persistLongOutput) {
            val head = output.take(limit / 2)
            val tail = output.takeLast(limit / 2)
            return "$head\n…[${output.length - limit} caracteres omitidos; saída de acessibilidade grande não foi arquivada por privacidade]…\n$tail"
        }
        val dir = File(sandbox.workspaceFor(sessionId), ".fluwy/out").apply { mkdirs() }
        // nome pelo CONTEÚDO: a mesma saída sempre cai no mesmo arquivo (nada de cópias em cadeia)
        val f = File(dir, "o" + Integer.toHexString(output.hashCode()) + "_" + output.length + ".txt")
        if (!persistArchive(f, output)) {
            val head = output.take(limit / 2)
            val tail = output.takeLast(limit / 2)
            return "$head\n…[${output.length - limit} caracteres omitidos; a saída completa não pôde ser arquivada neste workspace]…\n$tail"
        }
        cleanupArchivedOutputs(sessionId, dir)
        if (isBrowserSnapshotTool(toolName)) markBrowserSnapshot(f.name)
        val head = output.take(limit / 2)
        val tail = output.takeLast(limit / 2)
        val headLines = head.count { it == '\n' } + 1
        return "$head\n…[${output.length - limit} caracteres omitidos — saída completa em /root/workspace/.fluwy/out/${f.name} (no shell use esse caminho absoluto). Para ver o trecho omitido use file_read path=.fluwy/out/${f.name} offset=$headLines (lê por partes; não releia do início)]…\n$tail"
    }

    /**
     * Antes de cada chamada: encolhe o que é antigo e irrelevante para NÃO inflar o input, mas sem PERDER nada.
     * Mantém inteiras as últimas [keepRecentTools] respostas de ferramenta; as mais antigas (> 240 chars) viram
     * um resumo curto com um ponteiro file_read para a saída completa salva em .fluwy/out — o agente só traz de volta
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
                    } else if (isBrowserSnapshotTool(m.name)) {
                        // Snapshot antigo do browser: fica só o cabeçalho (URL/título). Não há ponteiro de releitura no
                        // contexto — o estado que vale é o da página agora. A cópia continua arquivada para pedidos explícitos.
                        if (sessionId != null) stash(sessionId, full)?.let { markBrowserSnapshot(it) }
                        m.copy(content = full.take(200).trimEnd() + "\n…[snapshot antigo do browser recolhido: a página pode ter mudado desde então. " +
                            "Para o estado atual, use browser_read; o que você precisava guardar dele deve estar nas suas anotações.]")
                    } else if (full.length <= 1_200 || (m.name == "conector_executar" && full.length <= 4_000)) {
                        // Resultado de conector já vem resumido (~2 KB): recolher obrigaria o agente a reler o arquivo.
                        // Pequeno: fica inteiro (um ponteiro de releitura custava uma chamada a mais e virava ciclo de leituras).
                        m
                    } else {
                        val ptr = if (sessionId != null) stash(sessionId, full) else null
                        if (sessionId != null && ptr == null) m else {
                            val head = full.take(200).trimEnd()
                            m.copy(content = head + "\n…[resultado antigo recolhido para não inflar o contexto. Trabalhe com as informações mais recentes" +
                                (if (ptr != null) "; recupere este conteúdo com file_read path=$ptr somente se precisar exatamente dele e não houver versão mais nova" else "") + "]")
                        }
                    }
                }
                // narração antiga do assistente (sem ferramentas): só o começo basta como fio da meada
                m.role == "assistant" && i != lastAssistant && m.toolCallsJson.isNullOrBlank() && (m.content?.length ?: 0) > 280 ->
                    m.copy(content = (m.content ?: "").take(200).trimEnd() + " …[resumido]")
                else -> m
            }
        }
    }

    /** Arquiva o conteúdo completo (ex.: JSON original de um conector antes de compactar) e devolve o caminho. */
    fun archive(sessionId: String, content: String): String? = stash(sessionId, content)

    /** Salva um conteúdo (endereçado pelo hash) em .fluwy/out e devolve o caminho relativo para file_read. */
    private fun stash(sessionId: String, content: String): String? {
        val dir = File(sandbox.workspaceFor(sessionId), ".fluwy/out").apply { mkdirs() }
        val f = File(dir, "o" + Integer.toHexString(content.hashCode()) + "_" + content.length + ".txt")
        if (!persistArchive(f, content)) return null
        cleanupArchivedOutputs(sessionId, dir)
        return ".fluwy/out/${f.name}"
    }

    private fun persistArchive(file: File, content: String): Boolean = runCatching {
        if (!file.isFile || runCatching { file.readText() }.getOrNull() != content) file.writeText(content)
        // Mesmo conteúdo (nome por hash) = acabou de ser citado de novo: renova a data para a limpeza não apagá-lo.
        else file.setLastModified(System.currentTimeMillis())
        file.isFile && file.readText() == content
    }.getOrDefault(false)

    private fun cleanupArchivedOutputs(sessionId: String, dir: File) {
        val files = dir.listFiles()?.toList() ?: return
        val session = store.session(sessionId) ?: return
        val referenced = mutableSetOf<String>()
        fun collect(text: String?) {
            if (text == null) return
            OUTPUT_POINTER.findAll(text).forEach { match -> referenced += match.groupValues[1] }
        }
        collect(session.summary)
        session.llm.forEach { collect(it.content); collect(it.toolCallsJson) }
        session.items.forEach { collect(it.toString()) }
        ContextOutputRetentionPolicy.filesToDelete(files, referenced).forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        private val OUTPUT_POINTER = Regex("""\.fluwy/out/(o[0-9a-f]+_\d+\.txt)""")
        const val MEMORY_MAX_CHARS = MemoryCurator.MAX_CHARS

        /** Erro para o agente quando a memória passa do limite (null = cabe). */
        fun memoryTooLong(text: String): String? = text.trim().length.takeIf { it > MEMORY_MAX_CHARS }?.let {
            "ERRO: uma memória tem no máximo $MEMORY_MAX_CHARS caracteres e este texto tem $it. Salve cada fato como uma memória separada e mais curta."
        }
    }

    // ---------- Camada 2 ----------
    /** Compacta se o histórico estimado passar do orçamento. Retorna a sessão atualizada (ou a mesma). */
    /**
     * "Memory flush" antes de descartar o histórico (ideia do OpenClaw): grava o resumo no workspace, então
     * mesmo após várias compactações o agente pode reler o que foi decidido em vez de alucinar.
     */
    private fun flushToWorkspace(s: Session, summary: String): String? = runCatching {
        if (sandbox.isConnectorSession(s.id)) return null
        val dir = java.io.File(sandbox.workspaceFor(s.id), ".fluwy/compactions").apply { mkdirs() }
        val name = "compact_${s.compactions + 1}.md"
        java.io.File(dir, name).writeText(summary)
        ".fluwy/compactions/$name"
    }.getOrNull()

    /**
     * Plano B da compactação, sem LLM e sem regex: mantém só as mensagens recentes (sem separar tool_call do
     * resultado) e registra no resumo que a parte antiga saiu. Nunca lança.
     */
    fun safeTrim(s: Session): Session = runCatching {
        val llmAll = ToolCallHistoryNormalizer.normalize(s.llm)
        val keep = store.settings.keepRecentMessages.coerceAtLeast(4)
        var cut = (llmAll.size - keep).coerceAtLeast(0)
        while (cut > 0 && cut < llmAll.size && llmAll[cut].role == "tool") cut--
        if (cut <= 0) return@runCatching s
        val tail = llmAll.subList(cut, llmAll.size).toList()
        val note = "[Histórico antigo removido para caber no contexto. Arquivos do trabalho continuam no workspace; confira-os com file_list antes de refazer algo.]"
        val updated = s.copy(llm = tail, summary = (s.summary.takeLast(6_000) + "\n" + note).trim(), compactions = s.compactions + 1)
        store.editSession(s.id) { it.copy(llm = updated.llm, summary = updated.summary, compactions = updated.compactions) }
        updated
    }.getOrDefault(s)

    /**
     * Quando a compactação normal não libera espaço (o peso está nas mensagens recentes, que ela preserva):
     * encurta resultados grandes de ferramentas também no fim do histórico e, se ainda não couber, fica só com as 4 últimas.
     */
    fun hardShrink(s: Session): Session = runCatching {
        val budget = store.settings.contextBudget
        fun size(l: List<LlmMessage>) = estimateTokens(l) + estimateTokens(s.summary)
        var llm = ToolCallHistoryNormalizer.normalize(s.llm).map { m ->
            val c = m.content ?: return@map m
            if (m.role == "tool" && c.length > 3_000) m.copy(content = c.take(1_200) + "\n[… ${c.length - 2_400} caracteres omitidos para caber no contexto; releia o trecho se precisar …]\n" + c.takeLast(1_200)) else m
        }
        if (size(llm) >= budget * 7 / 10) {
            var cut = (llm.size - 4).coerceAtLeast(0)
            while (cut > 0 && cut < llm.size && llm[cut].role == "tool") cut--
            if (cut > 0) llm = llm.subList(cut, llm.size).toList()
        }
        val summary = if (estimateTokens(s.summary) > budget / 3) s.summary.takeLast(budget) else s.summary
        val updated = s.copy(llm = llm, summary = summary, compactions = s.compactions + 1)
        store.editSession(s.id) { it.copy(llm = updated.llm, summary = updated.summary, compactions = updated.compactions) }
        updated
    }.getOrDefault(s)

    /** Tamanho logo após a última compactação, por sessão (histerese). */
    private val postCompactSize = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Quantas limpezas de resultados já houve na sessão (a releitura de um trecho limpo volta a valer). */
    private val clearEpoch = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun clearEpochOf(sessionId: String): Int = clearEpoch[sessionId] ?: 0

    suspend fun compactIfNeeded(s: Session, force: Boolean = false): Session {
        val normalized = ToolCallHistoryNormalizer.normalize(s.llm)
        val current = if (normalized != s.llm) {
            store.editSession(s.id) { it.copy(llm = normalized) }
            s.copy(llm = normalized)
        } else s
        val budget = store.settings.contextBudget
        // Antes de resumir (chamada ao modelo), limpa resultados antigos de ferramentas quando o contexto passa de 60%.
        if (!force && current.autoCompact && estimateTokens(current.llm) + estimateTokens(current.summary) >= budget * 6 / 10) {
            val cleared = ContextPruner.clearOld(current.llm, keepLast = 4, clearAtLeastChars = (budget * 3.2 * 0.1).toInt())
            if (cleared !== current.llm) {
                store.editSession(current.id) { if (it.llm.size == cleared.size) it.copy(llm = cleared) else it }
                clearEpoch.merge(current.id, 1, Int::plus)
                val after = current.copy(llm = cleared)
                if (estimateTokens(after.llm) + estimateTokens(after.summary) < budget) return after
                return compactIfNeeded(after, force)
            }
        }
        if (!force && (!current.autoCompact ||
                !MemoryHygiene.shouldCompact(estimateTokens(current.llm) + estimateTokens(current.summary), budget, postCompactSize[current.id]))) return current
        if (current.llm.size < 4) return current
        val keep = store.settings.keepRecentMessages.coerceAtLeast(4)
        // ponto de corte seguro: uma mensagem user/assistant (nunca no meio das respostas de ferramentas)
        var cut = (current.llm.size - keep).coerceAtLeast(1)
        while (cut > 0 && current.llm[cut].role == "tool") cut--
        if (cut <= 0) return current
        // Primeiro sem LLM: encurta resultados antigos de ferramentas. Se couber com folga, não precisa resumir.
        if (!force) {
            val pruned = ContextPruner.prune(current.llm, cut)
            if (pruned !== current.llm && estimateTokens(pruned) + estimateTokens(current.summary) < budget * 3 / 4) {
                store.editSession(current.id) { if (it.llm.size == pruned.size) it.copy(llm = pruned) else it }
                return current.copy(llm = pruned)
            }
        }
        val head = current.llm.subList(0, cut)
        val tail = current.llm.subList(cut, current.llm.size)
        val transcript = buildString {
            head.forEach { m ->
                val raw = m.content ?: ""
                val body = when {
                    m.role == "tool" && m.name?.startsWith("device_accessibility_") == true -> "[Conteúdo da tela omitido do resumo persistente por privacidade.]"
                    // Como no OpenClaw: resultado longo vira início + fim (o fim costuma ter o erro/conclusão).
                    m.role == "tool" -> runCatching { CompactionIdentifiers.project(raw, 2_000) }.getOrDefault(raw.take(2_000))
                    m.role == "user" -> raw.take(2_000)
                    else -> raw.take(1_500)
                }
                val calls = m.toolCallsJson?.let { " [chamou ferramentas: ${it.take(400)}]" }.orEmpty()
                append("${m.role.uppercase()}${m.name?.let { "($it)" } ?: ""}: $body$calls\n")
            }
        }.takeLast(60_000)
        val prompt = dev.fluwy.app.prompts.Prompts.render("context.2", mapOf<String, Any?>("prior_summary" to (PromptContextPolicy.untrustedBlock("prior_summary", current.summary.ifBlank { "(nenhum)" }.takeLast(6_000), 6_000)), "transcript" to (PromptContextPolicy.untrustedBlock("transcript", transcript, 60_000)), "lb" to "{{"))
        val identifiers = runCatching { CompactionIdentifiers.extract(current.summary + "\n" + head.joinToString("\n") { (it.content ?: "") + " " + (it.toolCallsJson ?: "") }) }.getOrDefault(emptyList())
        val summary = runCatching { llm.quick(prompt, maxTokens = 1500, conversationId = current.id) }.getOrNull()
            ?.let { sum -> runCatching { CompactionIdentifiers.ensure(sum, identifiers) }.getOrDefault(sum) }
            ?.let { sum -> sum + flushToWorkspace(current, sum)?.let { "\n\n(Resumo completo salvo em $it — use file_read se precisar de detalhes.)" }.orEmpty() }
        val updated = if (!summary.isNullOrBlank()) {
            current.copy(llm = tail, summary = summary, compactions = current.compactions + 1)
        } else {
            // circuit breaker: perde as rodadas mais antigas, mas destrava a sessão
            current.copy(llm = tail, compactions = current.compactions + 1,
                summary = (current.summary + "\n" + MemoryHygiene.excerptSummary(head)).trim()
                    .let { base -> runCatching { CompactionIdentifiers.ensure(base, identifiers) }.getOrDefault(base) })
        }
        store.editSession(current.id) { it.copy(llm = updated.llm, summary = updated.summary, compactions = updated.compactions) }
        postCompactSize[current.id] = estimateTokens(updated.llm) + estimateTokens(updated.summary)
        return updated
    }

    // ---------- Camada 3 ----------
    fun notesFile(sessionId: String) = File(sandbox.workspaceFor(sessionId), "NOTES.md")
    fun readNotes(sessionId: String): String = notesFile(sessionId).takeIf { it.exists() }?.readText().orEmpty()

    /** Recupera memórias relevantes com limite total de caracteres, não apenas limite por quantidade. */
    fun relevantMemories(query: String, agentId: String?, k: Int = 8, maxChars: Int = 2400): List<MemoryItem> {
        // Memórias com validade vencida ("· até AAAA-MM-DD") não entram mais no contexto.
        val active = store.state.value.memories.filter { MemoryHygiene.active(it.text) }
        val picked = MemoryRecallPolicy.select(active, query, agentId, k, maxChars)
        // Segundo cérebro: notas ligadas às escolhidas entram junto (contexto conectado), dentro do orçamento.
        val visible = active.filter { it.agentId == null || it.agentId == agentId }
        var budget = maxChars - picked.sumOf { it.text.length + 4 }
        val extra = MemoryGraph.expand(picked, visible, (k / 2).coerceAtLeast(2)).drop(picked.size)
            .filter { budget -= it.text.length + 4; budget >= 0 }
        return picked + extra
    }

    /** Linhas com título, categoria e ligações de cada nota (para prompt e memory_search). */
    fun describeMemories(items: List<MemoryItem>): List<String> {
        val all = store.state.value.memories
        val e = MemoryGraph.edges(all)
        return items.map { MemoryGraph.describe(it, all, e) }
    }

    /** Knowledge ranqueado e truncado por orçamento para impedir que documentos grandes explodam o prompt. */
    fun relevantKnowledge(query: String, k: Int = 5, maxChars: Int = 4500): List<KnowledgeItem> {
        val ranked = MemoryCurator.rankKnowledge(store.state.value.knowledge, query)
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

    /** Salva com deduplicação/merge: fatos parecidos no mesmo escopo atualizam o existente. Resposta curta para o agente. */
    fun saveMemory(text: String, agentId: String?, tags: List<String> = emptyList(), title: String = "", kind: String = "", links: List<String> = emptyList(),
                   source: String = MemoryCurator.SOURCE_AGENT, sessionId: String? = null): String {
        val t = text.trim().take(MEMORY_MAX_CHARS)
        if (t.isBlank()) return "ERRO: fact vazio. Envie o fato em uma frase."
        val all = store.state.value.memories
        val dup = MemoryCurator.findDuplicate(all, t, agentId)
        val linkIds = MemoryGraph.linkIds(links + MemoryGraph.wikiTargets(t).map { "[[$it]]" }, all, dup?.id)
        val now = System.currentTimeMillis()
        return if (dup != null) {
            val merged = MemoryCurator.merge(dup, t, tags, title, kind, linkIds, now)
            store.upsertMemory(merged)
            "Atualizada (fundida com nota parecida ${dup.id.take(8)} «${MemoryGraph.titleOf(merged)}»)."
        } else {
            val item = MemoryItem(text = t, agentId = agentId, tags = MemoryCurator.cleanTags(tags), title = title.trim().take(MemoryCurator.MAX_TITLE),
                kind = MemoryCurator.canonicalKind(kind), links = linkIds, source = source, sessionId = sessionId)
            store.upsertMemory(item)
            MemoryCurator.overflow(store.state.value.memories).forEach { store.deleteMemory(it) }
            "Salva: ${item.id.take(8)} «${MemoryGraph.titleOf(item)}» (${MemoryGraph.kindOf(item)}" +
                (if (linkIds.isNotEmpty()) ", ${linkIds.size} ligação(ões)" else "") + ")."
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
        // Sem nada duradouro dito pelo usuário, não gasta uma chamada ao modelo.
        if (!MemoryGate.worthExtracting(s.llm.filter { it.role == "user" }.map { it.content.orEmpty() })) return
        val existing = store.state.value.memories.takeLast(40).joinToString("\n") { "- [[${MemoryGraph.titleOf(it)}]] ${MemoryGraph.plain(it.text)}" }
        val out = runCatching {
            llm.quick(dev.fluwy.app.prompts.Prompts.render("context.1", mapOf<String, Any?>("kinds" to (MemoryGraph.KINDS.joinToString("/")), "existing_memories" to (PromptContextPolicy.untrustedBlock("existing_memories", existing, 4_000)), "conversation" to (PromptContextPolicy.untrustedBlock("conversation", convo, 12_000)), "lb" to "{{")), maxTokens = 400, conversationId = s.id)
        }.getOrNull() ?: return
        val userTexts = s.llm.filter { m -> m.role == "user" }.map { m -> m.content.orEmpty() }
        val kindRx = Regex("^\\[([a-zA-Zçãéí]+)\\]\\s*(.+)$")
        out.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
            .map { line -> kindRx.find(line)?.let { it.groupValues[1] to it.groupValues[2] } ?: ("" to line) }
            .filter { it.second.length in 8..400 }
            // Anti-alucinação: número/URL/caminho e palavras do fato precisam ter vindo do próprio usuário.
            .filter { MemoryHygiene.factBackedByUser(MemoryGraph.plain(it.second), userTexts) }
            .take(5).forEach { (kind, fact) -> saveMemory(fact, s.agentId, kind = kind, source = MemoryCurator.SOURCE_AUTO, sessionId = s.id) }
    }
}

/**
 * Política "strict" de identificadores do OpenClaw: URLs, caminhos, IDs e marcadores de segredo citados no
 * histórico sobrevivem à compactação LITERALMENTE — o resumidor pode parafrasear, mas a lista vai junto.
 */
object CompactionIdentifiers {
    // Cada padrão é compilado isoladamente: no Android (ICU) um padrão inválido lançaria no init da classe
    // (ExceptionInInitializerError) e quebraria TODA compactação. Aqui ele é só ignorado.
    private val patterns: List<Regex> = listOf<() -> Regex>(
        { Regex("https?://[^\\s\"'<>)\\]]+") },
        { Regex("\\{\\{secret:[a-z][a-z0-9_]{0,31}\\}\\}") },
        { Regex("(?:/root|/home|/public)/[\\w./-]+") },
        { Regex("(?<![\\w/.-])[\\w-]+(?:/[\\w.-]+)*\\.(?:md|txt|html|css|js|ts|json|py|kt|csv|yaml|yml|sh|png|jpg|pdf)\\b") },
        { Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b") },
        { Regex("\\b\\d{10,20}\\b") },
    ).mapNotNull { runCatching(it).getOrNull() }
    const val MAX = 40

    fun extract(text: String): List<String> {
        // Ordena pela posição no texto e mantém a ÚLTIMA ocorrência: os mais recentes valem mais.
        val hits = patterns.flatMap { p -> p.findAll(text).map { it.range.first to it.value.trimEnd('.', ',', ';', ':') }.toList() }
            .sortedBy { it.first }.map { it.second }.filter { it.length in 4..200 && !it.contains(".fluwy/out/") }
        val lastFirst = LinkedHashSet<String>()
        hits.asReversed().forEach { lastFirst.add(it) }
        // Um caminho relativo que é sufixo de um absoluto já listado é redundante.
        val ids = lastFirst.toList().take(MAX).asReversed()
        return ids.filter { id -> ids.none { other -> other != id && other.endsWith("/$id") } }
    }

    /** Acrescenta ao resumo os identificadores que o resumidor deixou de copiar. */
    fun ensure(summary: String, ids: List<String>): String {
        val missing = ids.filter { !summary.contains(it) }
        if (missing.isEmpty()) return summary
        return summary.trimEnd() + "\n\n## Identificadores exatos (copie literalmente)\n" + missing.joinToString("\n") { "- $it" }
    }

    /** Projeção início+fim de um resultado longo. */
    fun project(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max / 2) + "\n…[${text.length - max} caracteres omitidos — não adivinhe o conteúdo]…\n" + text.takeLast(max / 2 - 100)
}

