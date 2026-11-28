package dev.fluwy.app.llm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Uso de LLM por dia, por modelo e por agent (painel de uso, como o do OpenBot). Fica num arquivo pequeno do aparelho,
 * com os últimos 31 dias.
 */
object UsageLedger {
    @Serializable data class Counts(val calls: Int = 0, val input: Long = 0, val output: Long = 0)
    @Serializable data class Day(val models: Map<String, Counts> = emptyMap(), val agents: Map<String, Counts> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private const val PREFS = "usage_ledger"
    @Volatile private var cache: MutableMap<String, Day>? = null

    fun day(now: Long = System.currentTimeMillis()): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))

    private fun load(ctx: Context): MutableMap<String, Day> = cache ?: synchronized(this) {
        cache ?: runCatching {
            json.decodeFromString<Map<String, Day>>(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("days", "{}").orEmpty())
        }.getOrDefault(emptyMap()).toMutableMap().also { cache = it }
    }

    fun add(days: MutableMap<String, Day>, day: String, agent: String, modelKey: String, input: Long, output: Long) {
        val d = days[day] ?: Day()
        fun Map<String, Counts>.plus1(k: String) = this + (k to (this[k] ?: Counts()).let { it.copy(calls = it.calls + 1, input = it.input + input, output = it.output + output) })
        days[day] = d.copy(models = d.models.plus1(modelKey), agents = d.agents.plus1(agent))
        days.keys.sorted().dropLast(31).forEach { days.remove(it) }
    }

    fun record(ctx: Context, agent: String, model: String, providerId: String, input: Long, output: Long) = runCatching {
        val (pid, bare) = ProviderRoute.split(model)
        val key = (ProviderCatalog.byId(pid ?: providerId)?.name ?: "Personalizado") + " · " + bare
        synchronized(this) {
            val days = load(ctx)
            add(days, day(), agent, key, input, output)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("days", json.encodeToString(days.toMap())).apply()
        }
    }

    /** Soma dos últimos [n] dias (1 = hoje). */
    fun total(days: Map<String, Day>, n: Int, now: Long = System.currentTimeMillis()): Day {
        val wanted = (0 until n).map { day(now - it * 86_400_000L) }.toSet()
        fun merge(a: Map<String, Counts>, b: Map<String, Counts>) = (a.keys + b.keys).associateWith { k ->
            val x = a[k] ?: Counts(); val y = b[k] ?: Counts(); Counts(x.calls + y.calls, x.input + y.input, x.output + y.output)
        }
        return days.filterKeys { it in wanted }.values.fold(Day()) { acc, d -> Day(merge(acc.models, d.models), merge(acc.agents, d.agents)) }
    }

    fun snapshot(ctx: Context): Map<String, Day> = synchronized(this) { load(ctx).toMap() }
}
