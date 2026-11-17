package dev.cueopen.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.IOException

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Armazenamento local (sem login, sem nuvem): um JSON em filesDir com escrita debounce. */
@OptIn(FlowPreview::class)
class Store(context: Context) {
    private data class VersionedState(val revision: Long, val state: AppState)
    private val file = File(context.filesDir, "cueopen_state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var revision = 1L
    private var persistedRevision = 0L
    private val initialState = load()
    private val _state = MutableStateFlow(initialState)
    private val snapshots = MutableStateFlow(VersionedState(1L, initialState))
    val state: StateFlow<AppState> = _state

    init {
        snapshots.drop(1).debounce(400).onEach { save(it) }.launchIn(scope)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun load(): AppState = runCatching {
        val s = file.inputStream().buffered().use { AppJson.decodeFromStream(AppState.serializer(), it) }
        // Garante que skills nativas novas apareçam após updates
        val missing = BuiltInSkills.all.filter { b -> s.skills.none { it.id == b.id } }
        // Tarefas que estavam rodando quando o app morreu viram "interrompidas"
        s.copy(
            skills = (s.skills + missing).filterNot { it.builtIn && it.id in BuiltInSkills.retiredIds },
            // Conectores MCP antigos (catálogo fixo / registro aberto) saem: ficam só os do gateway e os personalizados.
            connectors = s.connectors.filter { it.gatewayRef?.startsWith("hub:") == true || it.description == "Personalizado" },
            pages = dev.cueopen.app.pages.PageOps.prune(s.pages, s.sessions.mapTo(HashSet()) { it.id }, s.agents.mapTo(HashSet()) { it.id }, s.sections.mapTo(HashSet()) { it.id }),
            // Migra uma única vez o consentimento de background dos agentes já autônomos;
            // depois disso, a remoção da capability pelo usuário nunca é revertida no próximo load.
            agents = s.agents.map { agent ->
                val activeCaps = dev.cueopen.app.device.DeviceCapPolicy.removeRetiredCapabilities(agent.deviceCaps)
                val migrated = dev.cueopen.app.device.DeviceCapPolicy.migrateLegacyBackgroundConsent(
                    activeCaps, agent.autonomous, s.settings.deviceBackgroundConsentMigrated)
                (if (migrated == agent.deviceCaps) agent else agent.copy(deviceCaps = migrated)).let { a ->
                    // Skills nativas retiradas deixam de estar ligadas ao agent.
                    if (a.skillIds.any { it in BuiltInSkills.retiredIds }) a.copy(skillIds = a.skillIds.filterNot { it in BuiltInSkills.retiredIds }) else a
                }
            },
            settings = s.settings.let { st ->
                st.copy(
                    deviceBackgroundConsentMigrated = true,
                    maxSteps = if (st.maxSteps <= 120) 1000 else st.maxSteps,
                    sandboxPackages = SandboxPackageDefaults.migrate(st.sandboxPackages),
                    model = if (st.model == "anthropic/claude-sonnet-4.5") "deepseek/deepseek-v4-flash" else st.model,
                    fastModel = if (st.fastModel == "google/gemini-2.5-flash") "deepseek/deepseek-v4-flash" else st.fastModel,
                )
            },
            sessions = s.sessions.map { SessionRetention.trim(it) }.map {
                if (it.status == SessionStatus.RUNNING) { if (it.continuous) it.copy(status = SessionStatus.SLEEPING, wakeAt = now()) else it.copy(status = SessionStatus.STOPPED) } else it
            },
        )
    }.getOrElse { AppState() }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Synchronized
    private fun save(snapshot: VersionedState) {
        if (snapshot.revision <= persistedRevision) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        // Grava em fluxo direto no arquivo: montar o JSON inteiro numa String duplicava a memória e derrubava o app
        // (OutOfMemoryError) quando o estado passava de ~30 MB.
        tmp.outputStream().buffered(64 * 1024).use { AppJson.encodeToStream(AppState.serializer(), snapshot.state, it) }
        if (!tmp.renameTo(file)) throw IOException("Não foi possível concluir a gravação do estado local.")
        persistedRevision = snapshot.revision
    }

    fun flush() {
        synchronized(stateLock) { save(snapshots.value) }
    }

    /** Persiste o snapshot mais recente fora da thread da UI ao encerrar uma Activity. */
    fun flushAsync() {
        scope.launch {
            runCatching { synchronized(stateLock) { save(snapshots.value) } }
                .onFailure { android.util.Log.e("CueOpenStore", "Falha ao salvar o estado local", it) }
        }
    }

    fun update(fn: (AppState) -> AppState) {
        synchronized(stateLock) {
            val current = _state.value
            val next = fn(current).let { n ->
                // Excluiu chat, seção ou agent (por qualquer caminho): as páginas deles saem junto.
                if (n.pages.isNotEmpty() && (n.sessions !== current.sessions || n.agents !== current.agents || n.sections !== current.sections)) {
                    val kept = dev.cueopen.app.pages.PageOps.prune(n.pages, n.sessions.mapTo(HashSet()) { it.id },
                        n.agents.mapTo(HashSet()) { it.id }, n.sections.mapTo(HashSet()) { it.id })
                    if (kept.size != n.pages.size) n.copy(pages = kept) else n
                } else n
            }
            if (next !== current) {
                revision += 1
                _state.value = next
                snapshots.value = VersionedState(revision, next)
            }
        }
    }

    val settings get() = _state.value.settings

    // ---- Sessões ----
    fun session(id: String) = _state.value.sessions.firstOrNull { it.id == id }

    fun upsertSession(s: Session) = update { st ->
        val exists = st.sessions.any { it.id == s.id }
        st.copy(sessions = if (exists) st.sessions.map { if (it.id == s.id) s else it } else listOf(s) + st.sessions)
    }

    fun editSession(id: String, fn: (Session) -> Session) = update { st ->
        st.copy(sessions = st.sessions.map { if (it.id == id) SessionRetention.trim(fn(it).copy(updatedAt = now())) else it })
    }

    fun addItem(sessionId: String, item: ChatItem) = editSession(sessionId) { it.copy(items = it.items + item) }

    /** Adiciona só se não for repetição imediata do mesmo tipo e texto (evita mensagens de erro/aviso duplicadas). */
    fun addItemUnique(sessionId: String, item: ChatItem) = editSession(sessionId) { s ->
        val last = s.items.lastOrNull()
        val dup = when {
            last is ChatItem.Error && item is ChatItem.Error -> last.text.trim() == item.text.trim()
            last is ChatItem.Assistant && item is ChatItem.Assistant -> last.text.trim() == item.text.trim()
            else -> false
        }
        if (dup) s else s.copy(items = s.items + item)
    }

    fun replaceItem(sessionId: String, item: ChatItem) = editSession(sessionId) { s ->
        s.copy(items = s.items.map { if (it.id == item.id) item else it })
    }

    var onDeleteWorkspace: ((String) -> Unit)? = null
    fun deleteSession(id: String) { runCatching { onDeleteWorkspace?.invoke(id) }; update { st -> st.copy(sessions = st.sessions.filterNot { it.id == id }) } }

    /** Remove chat, agendas explicitamente ligadas, execuções dessas agendas e heartbeats órfãos. */
    internal fun deleteSessionAndLinkedWork(id: String): SessionDeletionPlan {
        val plan = synchronized(stateLock) {
            val current = _state.value
            val next = current.planSessionDeletion(id)
            if (next.state != current) {
                revision += 1
                _state.value = next.state
                snapshots.value = VersionedState(revision, next.state)
            }
            next
        }
        return plan
    }

    /** Limpa workspaces somente depois de o chamador parar os jobs do chat removido. */
    internal fun cleanupWorkspaces(sessionIds: Collection<String>) {
        sessionIds.forEach { id -> runCatching { onDeleteWorkspace?.invoke(id) } }
    }

    /** Cria a sessão de execução e atualiza o registro da agenda sob o mesmo lock da exclusão. */
    internal fun beginScheduledRun(taskId: String, session: Session): Boolean = synchronized(stateLock) {
        val current = _state.value
        val task = current.scheduled.firstOrNull { it.id == taskId } ?: return@synchronized false
        if (session.scheduledTaskId != taskId) return@synchronized false
        if (task.sourceSessionId != null && current.sessions.none { it.id == task.sourceSessionId })
            return@synchronized false

        val next = current.copy(
            sessions = if (current.sessions.any { it.id == session.id })
                current.sessions.map { if (it.id == session.id) session else it }
            else listOf(session) + current.sessions,
            scheduled = current.scheduled.map {
                if (it.id == taskId) it.copy(lastRunAt = now(), lastSessionId = session.id) else it
            },
        )
        revision += 1
        _state.value = next
        snapshots.value = VersionedState(revision, next)
        true
    }

    internal fun isScheduledRunActive(taskId: String, sessionId: String): Boolean {
        val current = _state.value
        val task = current.scheduled.firstOrNull { it.id == taskId } ?: return false
        val session = current.sessions.firstOrNull { it.id == sessionId } ?: return false
        return session.scheduledTaskId == taskId &&
            (task.sourceSessionId == null || current.sessions.any { it.id == task.sourceSessionId })
    }

    // ---- Agents / Skills / Agendadas / Knowledge ----
    fun upsertAgent(a: AgentProfile) = update { st ->
        st.copy(agents = if (st.agents.any { it.id == a.id }) st.agents.map { if (it.id == a.id) a else it } else st.agents + a)
    }
    fun deleteAgent(id: String) {
        _state.value.agents.firstOrNull { it.id == id }?.homeSessionId?.let { sid -> runCatching { onDeleteWorkspace?.invoke(sid) }; update { st -> st.copy(sessions = st.sessions.filterNot { it.id == sid }) } }
        update { it.copy(agents = it.agents.filterNot { a -> a.id == id }) }
    }
    fun agent(id: String?) = id?.let { i -> _state.value.agents.firstOrNull { it.id == i } }

    // ---------- Seções
    fun section(id: String?) = id?.let { i -> _state.value.sections.firstOrNull { it.id == i } }
    fun upsertSection(sec: Section) = update { st ->
        st.copy(sections = if (st.sections.any { it.id == sec.id }) st.sections.map { if (it.id == sec.id) sec else it } else st.sections + sec,
            // Quadro do time: o kanban da seção acompanha o plano sozinho.
            pages = runCatching { dev.cueopen.app.pages.PageOps.syncBoard(st.pages, sec) }.getOrDefault(st.pages))
    }

    // ---------- Fluwy Pages
    fun page(id: String) = _state.value.pages.firstOrNull { it.id == id }
    fun pagesIn(spaceId: String) = _state.value.pages.filter { it.spaceId == spaceId }
    fun upsertPage(p: dev.cueopen.app.pages.FluwyPage) = update { st ->
        st.copy(pages = if (st.pages.any { it.id == p.id }) st.pages.map { if (it.id == p.id) p else it } else st.pages + p)
    }
    fun deletePage(id: String) = update { st -> st.copy(pages = st.pages.filterNot { it.id == id }) }
    /** Aplica um lote de operações de forma atômica (dentro do lock do estado): vários agentes não se sobrescrevem. */
    fun applyPageOps(id: String, ops: List<kotlinx.serialization.json.JsonObject>, by: String): Result<dev.cueopen.app.pages.FluwyPage> {
        var out: Result<dev.cueopen.app.pages.FluwyPage> = Result.failure(IllegalArgumentException("página $id não existe (use page_list)"))
        update { st ->
            val p = st.pages.firstOrNull { it.id == id } ?: return@update st
            out = dev.cueopen.app.pages.PageOps.apply(p, ops, by)
            out.getOrNull()?.let { np -> st.copy(pages = st.pages.map { if (it.id == id) np else it }) } ?: st
        }
        return out
    }
    /** Remove a seção, todos os chats dela e os Agents que foram criados para ela. */
    fun deleteSection(id: String): List<String> {
        val sec = section(id) ?: return emptyList()
        val sessionIds = _state.value.sessions.filter { it.sectionId == id || it.id == sec.sessionId }.map { it.id }
        val agentIds = (sec.agentIds + sec.coordinatorAgentId + sec.appAgentId).toSet()
        update { st -> st.copy(sections = st.sections.filterNot { it.id == id }, agents = st.agents.filterNot { it.id in agentIds }) }
        return sessionIds
    }

    fun upsertSkill(s: Skill) = update { st ->
        st.copy(skills = if (st.skills.any { it.id == s.id }) st.skills.map { if (it.id == s.id) s else it } else st.skills + s)
    }
    fun deleteSkill(id: String) = update { it.copy(skills = it.skills.filterNot { s -> s.id == id }) }

    fun upsertScheduled(t: ScheduledTask) = update { st ->
        st.copy(scheduled = if (st.scheduled.any { it.id == t.id }) st.scheduled.map { if (it.id == t.id) t else it } else st.scheduled + t)
    }
    fun deleteScheduled(id: String) = update { it.copy(scheduled = it.scheduled.filterNot { s -> s.id == id }) }

    fun upsertKnowledge(k: KnowledgeItem) = update { st ->
        st.copy(knowledge = if (st.knowledge.any { it.id == k.id }) st.knowledge.map { if (it.id == k.id) k else it } else st.knowledge + k)
    }
    fun deleteKnowledge(id: String) = update { it.copy(knowledge = it.knowledge.filterNot { k -> k.id == id }) }

    fun upsertConnector(c: Connector) = update { st ->
        val prev = st.connectors.firstOrNull { it.id == c.id }
        if (c.lastError != null && c.lastError != prev?.lastError)
            runCatching { dev.cueopen.app.diag.ErrorLog.log("connector", "${c.name} (${c.url}): ${c.lastError}") }
        st.copy(connectors = if (st.connectors.any { it.id == c.id }) st.connectors.map { if (it.id == c.id) c else it } else st.connectors + c)
    }
    fun connector(id: String) = _state.value.connectors.firstOrNull { it.id == id }
    fun clearConnectorLastError(id: String) = update { it.withConnectorLastErrorCleared(id) }
    fun deleteConnector(id: String) = update { it.copy(connectors = it.connectors.filterNot { c -> c.id == id }) }

    fun upsertMemory(m: MemoryItem) = update { st ->
        st.copy(memories = if (st.memories.any { it.id == m.id }) st.memories.map { if (it.id == m.id) m else it } else st.memories + m)
    }
    fun deleteMemory(id: String) = update { it.copy(memories = it.memories.filterNot { m -> m.id == id }) }
    fun deleteMemories(ids: Set<String>) = update { it.copy(memories = it.memories.filterNot { m -> m.id in ids }) }

    fun updateSettings(fn: (Settings) -> Settings) = update { it.copy(settings = fn(it.settings)) }

    fun upsertBrowserLogin(l: BrowserLogin) = update { st ->
        st.copy(browserLogins = if (st.browserLogins.any { it.id == l.id }) st.browserLogins.map { if (it.id == l.id) l else it } else st.browserLogins + l)
    }
    fun deleteBrowserLogin(id: String) = update { it.copy(browserLogins = it.browserLogins.filterNot { l -> l.id == id }) }
    fun editBrowserLogin(id: String, fn: (BrowserLogin) -> BrowserLogin) = update { st -> st.copy(browserLogins = st.browserLogins.map { if (it.id == id) fn(it) else it }) }

    fun upsertUserScript(u: UserScript) = update { st ->
        st.copy(userScripts = if (st.userScripts.any { it.id == u.id }) st.userScripts.map { if (it.id == u.id) u else it } else st.userScripts + u)
    }
    fun deleteUserScript(id: String) = update { it.copy(userScripts = it.userScripts.filterNot { u -> u.id == id }) }
    fun editUserScript(id: String, fn: (UserScript) -> UserScript) = update { st -> st.copy(userScripts = st.userScripts.map { if (it.id == id) fn(it) else it }) }
}

internal fun AppState.withConnectorLastErrorCleared(id: String): AppState {
    val target = connectors.firstOrNull { it.id == id } ?: return this
    if (target.lastError == null) return this
    return copy(connectors = connectors.map { if (it.id == id) it.copy(lastError = null) else it })
}
