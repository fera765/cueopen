package dev.cueopen.app.ui.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** O que o agente está fazendo — cada mascote reage do seu jeito. */
enum class MascotState { IDLE, THINKING, TALKING, SLEEPING }

/** Movimento global aplicado a mascotes importados (os nativos têm animação própria). */
enum class MascotMotion(val label: String) {
    FLOAT("Flutuar"), BOUNCE("Pular"), SWAY("Balançar"), PULSE("Pulsar"), JELLY("Gelatina"), NONE("Parado");

    companion object {
        fun of(id: String?): MascotMotion = entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: FLOAT
    }
}

/** Mascote nativo: nome exibido e "personalidade" (como se move). */
data class BuiltinMascot(val id: String, val name: String, val personality: String)

object Mascots {
    const val CUSTOM_PREFIX = "custom:"

    val builtins: List<BuiltinMascot> = listOf(
        BuiltinMascot("jelly", "Geleia", "Estica e encolhe; solta bolhas quando pensa"),
        BuiltinMascot("bot", "Pixel", "Paira no ar; antena pisca e os olhos de LED fazem varredura"),
        BuiltinMascot("ghost", "Bú", "Flutua com a barra da roupa ondulando"),
        BuiltinMascot("cat", "Mia", "Pisca devagar, mexe a orelha e balança o rabo"),
        BuiltinMascot("owl", "Corujo", "Inclina a cabeça e olha em volta com olhos grandes"),
        BuiltinMascot("octo", "Polvo", "Tentáculos em onda contínua"),
        BuiltinMascot("sprout", "Broto", "Folhas balançam ao vento; giram quando pensa"),
        BuiltinMascot("cloud", "Nimbo", "Deriva devagar; chove quando pensa"),
        BuiltinMascot("star", "Estelar", "Pulsa e gira com brilhos ao redor"),
        BuiltinMascot("bee", "Zum", "Asas batendo rápido em voo zigue-zague"),
        BuiltinMascot("flame", "Chama", "Labareda tremeluzente"),
        BuiltinMascot("orbit", "Orbi", "Planetinha com uma lua em órbita"),
    )

    val builtinIds: List<String> = builtins.map { it.id }

    /** IDs dos mascotes antigos (30 formas) → novos. Agents existentes trocam de visual sem perder nada. */
    private val legacy = mapOf(
        "wink" to "jelly", "blob" to "jelly", "drop" to "jelly", "smile" to "bot", "square" to "bot", "robot" to "bot",
        "gear" to "bot", "octagon" to "bot", "ghost" to "ghost", "side" to "ghost", "pill" to "ghost", "cat" to "cat",
        "bear" to "cat", "turtle" to "cat", "owl" to "owl", "shield" to "owl", "hex" to "owl", "spiral" to "octo",
        "butterfly" to "bee", "dot" to "orbit", "circle" to "orbit", "moon" to "orbit", "comet" to "orbit",
        "leaf" to "sprout", "flower" to "sprout", "cloud" to "cloud", "heart" to "jelly", "star" to "star",
        "spark" to "star", "crown" to "star", "diamond" to "star", "sun" to "flame", "rocket" to "flame",
        "triangle" to "flame",
    )

    fun isCustom(face: String): Boolean = face.startsWith(CUSTOM_PREFIX)
    fun customId(face: String): String = face.removePrefix(CUSTOM_PREFIX)

    /** Resolve qualquer valor salvo em `AgentProfile.face` para um mascote nativo válido. */
    fun resolveBuiltin(face: String): String = when {
        face in builtinIds -> face
        else -> legacy[face] ?: builtinIds[face.hashCode().mod(builtinIds.size)]
    }

    fun name(face: String): String = builtins.firstOrNull { it.id == resolveBuiltin(face) }?.name ?: "Mascote"
}

/** Pose calculada a partir do tempo: piscadas, olhar, fala. Pura, para ser determinística. */
data class MascotPose(
    val t: Float,
    val state: MascotState,
    val blink: Float,
    val lookX: Float,
    val lookY: Float,
    val talk: Float,
) {
    companion object {
        fun at(t: Float, seed: Float, state: MascotState, animated: Boolean): MascotPose {
            if (!animated) return MascotPose(0f, state, if (state == MascotState.SLEEPING) 1f else 0f, 0f, 0f, 0f)
            val period = 3.4f + (seed % 1.7f)
            val cycle = (t + seed * 1.3f) % period
            val blinkWindow = 0.16f
            val baseBlink = if (cycle < blinkWindow) sin(cycle / blinkWindow * PI.toFloat()) else 0f
            val blink = when (state) {
                MascotState.SLEEPING -> 1f
                MascotState.THINKING -> maxOf(baseBlink, .25f)
                else -> baseBlink
            }
            val (lx, ly) = when (state) {
                MascotState.THINKING -> (.55f + sin(t * 1.8f) * .25f) to -.7f
                MascotState.TALKING -> sin(t * 1.1f + seed) * .15f to 0f
                MascotState.SLEEPING -> 0f to .3f
                MascotState.IDLE -> sin(t * .55f + seed) * .45f to sin(t * .37f + seed * 2f) * .2f
            }
            val talk = if (state == MascotState.TALKING) abs(sin(t * 9f)) * (.55f + .45f * abs(sin(t * 2.3f))) else 0f
            return MascotPose(t, state, blink, lx, ly, talk)
        }
    }
}
