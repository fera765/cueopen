package dev.fluwy.app.agent

/**
 * OpenBot 0.71: o agente repetindo exatamente a mesma chamada que falha (mesma ferramenta, mesmos argumentos, mesmo
 * erro) gasta tokens e bateria sem chance de dar certo. Na 3ª vez avisa para mudar de abordagem; na 5ª para o turno.
 */
internal class RepeatedToolFailureGuard(private val warnAt: Int = 3, private val stopAt: Int = 5) {
    enum class Verdict { OK, WARN, STOP }
    private val counts = HashMap<String, Int>()

    fun record(tool: String, normalizedArgs: String, output: String): Verdict {
        val key = "$tool|$normalizedArgs"
        if (!isFailure(output)) { counts.remove(key); return Verdict.OK }
        val sig = key + "|" + output.take(160)
        val n = (counts[sig] ?: 0) + 1
        counts[sig] = n
        return when { n >= stopAt -> Verdict.STOP; n == warnAt -> Verdict.WARN; else -> Verdict.OK }
    }

    private fun isFailure(o: String): Boolean {
        val t = o.trimStart()
        return t.startsWith("ERRO", true) || t.startsWith("ERROR", true) || t.startsWith("Erro:") || t.startsWith("❌") || t.startsWith("Falha")
    }

    companion object {
        const val WARN_NOTE = "[Sistema] Esta mesma chamada já falhou 3 vezes do mesmo jeito. Não repita: mude os argumentos, use outra ferramenta ou explique ao usuário o bloqueio."
    }
}
