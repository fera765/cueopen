package dev.cueopen.app.device

import dev.cueopen.app.data.AgentProfile

/**
 * Liga a autonomia do Agent junto com o acesso Android de que ela depende, num passo só,
 * e protege as configurações do Agent contra sobrescrita por cópias desatualizadas.
 */
object AgentAutonomyPolicy {
    /** Ligar a autonomia já concede ‘Executar em segundo plano’; desligar preserva o acesso Android. */
    fun setAutonomous(profile: AgentProfile, enabled: Boolean): AgentProfile =
        if (enabled) profile.copy(
            autonomous = true,
            consecutiveFailures = 0,
            deviceCaps = (profile.deviceCaps + DeviceCapPolicy.BACKGROUND).distinct(),
        ) else profile.copy(autonomous = false)

    /** Falhas seguidas de ciclos autônomos que desligam a autonomia (fadiga, como as routines do OpenBot). */
    const val MAX_CONSECUTIVE_FAILURES = 10

    /**
     * Registra o resultado de um ciclo. Sucesso zera o contador; a 10ª falha seguida desliga a autonomia.
     * @return perfil atualizado e se a autonomia acabou de ser desligada por fadiga.
     */
    fun recordCycle(profile: AgentProfile, failed: Boolean): Pair<AgentProfile, Boolean> {
        if (!failed) return (if (profile.consecutiveFailures == 0) profile else profile.copy(consecutiveFailures = 0)) to false
        val n = profile.consecutiveFailures + 1
        val trip = n >= MAX_CONSECUTIVE_FAILURES && profile.autonomous
        return profile.copy(consecutiveFailures = if (trip) 0 else n, autonomous = if (trip) false else profile.autonomous) to trip
    }

    /**
     * A tela de edição guarda uma cópia do Agent aberta há minutos. Enquanto isso o runtime muda
     * sessão-base, heartbeat, contadores, memória e a própria autonomia (set_schedule). Salvar a
     * cópia antiga apagava `homeSessionId` e a autonomia "desligava sozinha". Aqui só os campos
     * que o usuário realmente alterou na tela vencem; o resto vem do estado mais recente.
     */
    fun mergeForSave(edited: AgentProfile, original: AgentProfile?, latest: AgentProfile?): AgentProfile {
        if (latest == null) return edited
        val base = original ?: latest
        fun <T> pick(e: T, b: T, l: T): T = if (e == b) l else e
        return edited.copy(
            homeSessionId = latest.homeSessionId,
            lastBeatAt = latest.lastBeatAt,
            beats = latest.beats,
            actions = latest.actions,
            tokensDay = latest.tokensDay,
            tokensToday = latest.tokensToday,
            heartbeatMinutes = latest.heartbeatMinutes,
            consecutiveFailures = latest.consecutiveFailures,
            mission = pick(edited.mission, base.mission, latest.mission),
            memory = pick(edited.memory, base.memory, latest.memory),
            autonomous = pick(edited.autonomous, base.autonomous, latest.autonomous),
            deviceCaps = pick(edited.deviceCaps, base.deviceCaps, latest.deviceCaps),
            browserAutonomy = pick(edited.browserAutonomy, base.browserAutonomy, latest.browserAutonomy),
            browserAutonomyGrantedBackground = pick(
                edited.browserAutonomyGrantedBackground,
                base.browserAutonomyGrantedBackground,
                latest.browserAutonomyGrantedBackground,
            ),
        )
    }
}
