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

    private fun load(): AppState = runCatching {
        val s = AppJson.decodeFromString(AppState.serializer(), file.readText())
        // Garante que skills nativas novas apareçam após updates
        val missing = BuiltInSkills.all.filter { b -> s.skills.none { it.id == b.id } }
        // Tarefas que estavam rodando quando o app morreu viram "interrompidas"
        s.copy(
            skills = s.skills + missing,
            // Migra uma única vez o consentimento de background dos agentes já autônomos;
            // depois disso, a remoção da capability pelo usuário nunca é revertida no próximo load.
            agents = s.agents.map { agent ->
                val activeCaps = dev.cueopen.app.device.DeviceCapPolicy.removeRetiredCapabilities(agent.deviceCaps)
                val migrated = dev.cueopen.app.device.DeviceCapPolicy.migrateLegacyBackgroundConsent(
                    activeCaps, agent.autonomous, s.settings.deviceBackgroundConsentMigrated)
                if (migrated == agent.deviceCaps) agent else agent.copy(deviceCaps = migrated)
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
            sessions = s.sessions.map {
                if (it.status == SessionStatus.RUNNING) { if (it.continuous) it.copy(status = SessionStatus.SLEEPING, wakeAt = now()) else it.copy(status = SessionStatus.STOPPED) } else it
            },
        )
    }.getOrElse { AppState() }

    @Synchronized
    private fun save(snapshot: VersionedState) {
        if (snapshot.revision <= persistedRevision) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(AppState.serializer(), snapshot.state))
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
            val next = fn(current)
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
        st.copy(sessions = st.sessions.map { if (it.id == id) fn(it).copy(updatedAt = now()) else it })
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
        st.copy(sections = if (st.sections.any { it.id == sec.id }) st.sections.map { if (it.id == sec.id) sec else it } else st.sections + sec)
    }
    /** Remove a seção, todos os chats dela e os Agents que foram criados para ela. */
    fun deleteSection(id: String): List<String> {
        val sec = section(id) ?: return emptyList()
        val sessionIds = _state.value.sessions.filter { it.sectionId == id || it.id == sec.sessionId }.map { it.id }
        val agentIds = (sec.agentIds + sec.coordinatorAgentId).toSet()
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
        st.copy(connectors = if (st.connectors.any { it.id == c.id }) st.connectors.map { if (it.id == c.id) c else it } else st.connectors + c)
    }
    fun connector(id: String) = _state.value.connectors.firstOrNull { it.id == id }
    fun clearConnectorLastError(id: String) = update { it.withConnectorLastErrorCleared(id) }
    fun deleteConnector(id: String) = update { it.copy(connectors = it.connectors.filterNot { c -> c.id == id }) }

    fun upsertMemory(m: MemoryItem) = update { st ->
        st.copy(memories = if (st.memories.any { it.id == m.id }) st.memories.map { if (it.id == m.id) m else it } else st.memories + m)
    }
    fun deleteMemory(id: String) = update { it.copy(memories = it.memories.filterNot { m -> m.id == id }) }

    fun updateSettings(fn: (Settings) -> Settings) = update { it.copy(settings = fn(it.settings)) }
}

internal fun AppState.withConnectorLastErrorCleared(id: String): AppState {
    val target = connectors.firstOrNull { it.id == id } ?: return this
    if (target.lastError == null) return this
    return copy(connectors = connectors.map { if (it.id == id) it.copy(lastError = null) else it })
}
