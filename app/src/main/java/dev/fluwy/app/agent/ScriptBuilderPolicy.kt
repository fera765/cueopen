package dev.fluwy.app.agent

/**
 * Disciplina do criador de User Scripts (puro, testável): descobrir ANTES de escrever, provar o resultado
 * e nunca ficar testando em círculo.
 *  - sem descoberta (userscript_inspect) não há teste;
 *  - [FAILS_BEFORE_REDISCOVERY] falhas seguidas → o próximo teste só sai depois de nova descoberta;
 *  - o mesmo código que já falhou não é testado de novo;
 *  - [MAX_TESTS] testes no total → para e fala com o usuário.
 */
class ScriptBuilderPolicy {
    private var inspected = false
    private var consecutiveFails = 0
    private var tests = 0
    private val failedCodes = HashSet<Int>()
    var lastPassedCode: Int? = null
        private set

    sealed class Gate {
        object Allow : Gate()
        data class Deny(val message: String, val stop: Boolean = false) : Gate()
    }

    fun onInspect() { inspected = true; consecutiveFails = 0 }

    fun beforeTest(code: String): Gate = when {
        !inspected -> Gate.Deny("DESCOBERTA primeiro: chame userscript_inspect na página (seletores candidatos e/ou um texto visível do alvo) e escreva o script com base no DOM REAL. Depois teste.")
        tests >= MAX_TESTS -> Gate.Deny("Limite de $MAX_TESTS testes atingido. PARE: explique ao usuário em 3-5 linhas o que funciona, o que bloqueia (ex.: conteúdo em iframe, dados só via API, login) e as alternativas. Não teste mais.", stop = true)
        code.trim().hashCode() in failedCodes -> Gate.Deny("Este MESMO código já falhou. Não repita: mude a abordagem com base numa nova descoberta (userscript_inspect / browser_js / pesquisa).")
        consecutiveFails >= FAILS_BEFORE_REDISCOVERY -> Gate.Deny("$consecutiveFails falhas seguidas: você está chutando. Volte à DESCOBERTA: userscript_inspect com outros seletores/texto, browser_js para inspecionar o que o script encontra, browser_network se o dado vem de API, ou pesquise a solução (browser_navigate em https://duckduckgo.com/?q=...). Só então teste de novo.")
        else -> Gate.Allow
    }

    fun afterTest(code: String, passed: Boolean) {
        tests++
        if (passed) { consecutiveFails = 0; lastPassedCode = code.trim().hashCode() }
        else { consecutiveFails++; failedCodes.add(code.trim().hashCode()); inspected = consecutiveFails < FAILS_BEFORE_REDISCOVERY }
    }

    /** Só propõe o que passou no teste com prova. */
    fun canPropose(code: String): Boolean = lastPassedCode == code.trim().hashCode()

    /** Depois de parar no limite e o usuário responder, libera uma nova rodada. */
    fun newRound() { tests = 0; consecutiveFails = 0 }

    val testsRun: Int get() = tests

    companion object {
        const val FAILS_BEFORE_REDISCOVERY = 2
        const val MAX_TESTS = 10

        /** Veredito de um teste: script sem erro, sem erros assíncronos e com a prova (verify) verdadeira. */
        fun passed(syncResult: String, asyncErrors: List<String>, verifyTruthy: Boolean): Boolean =
            syncResult == "ok" && asyncErrors.isEmpty() && verifyTruthy
    }
}
