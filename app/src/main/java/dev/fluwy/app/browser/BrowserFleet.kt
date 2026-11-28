package dev.fluwy.app.browser

import android.os.Handler
import android.os.Looper
import dev.fluwy.app.FluwyApp

/**
 * Coordenação global dos WebViews (todos os chats + API Hub). Ver [BrowserFleetPolicy].
 * Chamar [tick] e [onMemoryPressure] na thread principal.
 */
object BrowserFleet {
    private val deaths = BrowserFleetPolicy.DeathTracker()
    private val stagger = BrowserFleetPolicy.RecoveryStagger()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lowMemoryLogged = false

    fun limits(now: Long = System.currentTimeMillis()): BrowserFleetPolicy.Limits =
        if (synchronized(deaths) { deaths.lowMemory(now) }) BrowserFleetPolicy.LOW_MEMORY else BrowserFleetPolicy.NORMAL

    /** Revisão periódica: estaciona o que passou do orçamento, está ocioso ou aberto há horas. */
    fun tick(app: FluwyApp, reason: String = "revisão", now: Long = System.currentTimeMillis()) {
        val hosts = app.openBrowsers()
        val states = hosts.flatMap { (id, host) -> host.fleetStates(running = isRunning(app, id), pinned = isPinned(id)) }
        val park = BrowserFleetPolicy.toPark(states, now, limits(now))
        park.forEach { t -> hosts[t.hostId]?.parkById(t.tabId) }
        if (park.isNotEmpty()) dev.fluwy.app.diag.ErrorLog.log("browser",
            "Frota de WebViews ($reason, ${limits(now).let { if (it == BrowserFleetPolicy.LOW_MEMORY) "modo pouca memória" else "normal" }}): " +
                "${park.size} aba(s) estacionada(s) de ${states.count { it.live }} viva(s) — " + park.joinToString { "${it.hostId.take(10)}/${it.tabId}" })
    }

    fun onMemoryPressure(app: FluwyApp, level: Int) {
        tick(app, "pressão de memória nível $level")
        app.openBrowsers().values.forEach { runCatching { it.onMemoryPressure(level) } }
    }

    /**
     * Renderer morreu. Devolve o atraso (ms) para recriar a aba, ou null para recriar só quando for usada.
     * Abas sem uso imediato ficam estacionadas: recriar tudo de uma vez causaria a próxima queda.
     */
    fun onRendererDeath(app: FluwyApp, hostId: String, neededNow: Boolean, now: Long = System.currentTimeMillis()): Long? {
        val newEvent = synchronized(deaths) { deaths.record(now) }
        if (newEvent && synchronized(deaths) { deaths.lowMemory(now) } && !lowMemoryLogged) {
            lowMemoryLogged = true
            dev.fluwy.app.diag.ErrorLog.log("browser", "Quedas repetidas do renderer: modo de pouca memória ligado por 30 min (menos abas vivas, reciclagem mais cedo).")
            main.postDelayed({ lowMemoryLogged = false }, 30 * 60_000L)
        }
        if (newEvent) main.postDelayed({ runCatching { tick(app, "após queda do renderer") } }, 500)
        return if (neededNow) synchronized(stagger) { stagger.delayFor(now) } else null
    }

    fun isRunning(app: FluwyApp, hostId: String): Boolean =
        runCatching { app.agent.isRunning(hostId) }.getOrDefault(false)

    /** O WhatsApp Web do API Hub precisa ficar vivo para ouvir mensagens (webhook). */
    fun isPinned(hostId: String): Boolean = hostId == WebApiRunner.WA_KEY

    internal fun post(delayMs: Long, block: () -> Unit) { main.postDelayed({ runCatching(block) }, delayMs) }
}
