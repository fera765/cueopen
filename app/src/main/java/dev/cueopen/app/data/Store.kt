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
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Armazenamento local (sem login, sem nuvem): um JSON em filesDir com escrita debounce. */
@OptIn(FlowPreview::class)
class Store(context: Context) {
    private val file = File(context.filesDir, "cueopen_state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state

    init {
        _state.drop(1).debounce(400).onEach { save(it) }.launchIn(scope)
    }

    private fun load(): AppState = runCatching {
        val s = AppJson.decodeFromString(AppState.serializer(), file.readText())
        // Garante que skills nativas novas apareçam após updates
        val missing = BuiltInSkills.all.filter { b -> s.skills.none { it.id == b.id } }
        // Tarefas que estavam rodando quando o app morreu viram "interrompidas"
        s.copy(
            skills = s.skills + missing,
            settings = s.settings.let { st ->
                st.copy(
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
    private fun save(s: AppState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(AppState.serializer(), s))
        tmp.renameTo(file)
    }

    fun flush() = save(_state.value)

    fun update(fn: (AppState) -> AppState) = _state.update(fn)

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

    fun replaceItem(sessionId: String, item: ChatItem) = editSession(sessionId) { s ->
        s.copy(items = s.items.map { if (it.id == item.id) item else it })
    }

    fun deleteSession(id: String) = update { st -> st.copy(sessions = st.sessions.filterNot { it.id == id }) }

    // ---- Agents / Skills / Agendadas / Knowledge ----
    fun upsertAgent(a: AgentProfile) = update { st ->
        st.copy(agents = if (st.agents.any { it.id == a.id }) st.agents.map { if (it.id == a.id) a else it } else st.agents + a)
    }
    fun deleteAgent(id: String) = update { it.copy(agents = it.agents.filterNot { a -> a.id == id }) }
    fun agent(id: String?) = id?.let { i -> _state.value.agents.firstOrNull { it.id == i } }

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

    fun upsertMemory(m: MemoryItem) = update { st ->
        st.copy(memories = if (st.memories.any { it.id == m.id }) st.memories.map { if (it.id == m.id) m else it } else st.memories + m)
    }
    fun deleteMemory(id: String) = update { it.copy(memories = it.memories.filterNot { m -> m.id == id }) }

    fun updateSettings(fn: (Settings) -> Settings) = update { it.copy(settings = fn(it.settings)) }
}
