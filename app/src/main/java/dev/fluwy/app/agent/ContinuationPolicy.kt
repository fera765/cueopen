package dev.fluwy.app.agent

import java.text.Normalizer

/**
 * Detecta quando o usuário só quer que o agente siga de onde parou ("continuar", "prossiga", "retome",
 * "pode seguir"…). Nesses casos o app acrescenta uma diretiva de continuidade, para o agente não
 * reinterpretar a mensagem como um pedido novo e refazer todo o trabalho. Puro — testável na JVM.
 */
object ContinuationPolicy {
    private val intent = Regex(
        "^(pode |por favor |pf |ok,? |beleza,? |entao |então )?" +
            "(continu(e|ar|a|ando)|prossig(a|ir)|prosseguir|sig(a|ir)|seguir|segue|avanc(e|ar|a)|retom(e|ar|a)|" +
            "recomec(e|ar) de onde parou|de onde parou|volt(e|ar|a) a trabalhar|go on|keep going|carry on|resume|next|proximo|proxima etapa)" +
            "( de onde parou| daqui| o trabalho| a tarefa| por favor| ai| aí)?[.!]*$"
    )

    fun isContinueIntent(text: String): Boolean {
        val t = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 60) return false
        return intent.matches(t)
    }
}
