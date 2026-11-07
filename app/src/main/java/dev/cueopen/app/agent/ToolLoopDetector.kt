package dev.cueopen.app.agent

/**
 * Detector de loop de ferramentas, puro (testável na JVM).
 *
 * Só acusa loop quando não há progresso. Progresso = a chamada trouxe informação nova:
 *  - um resultado que esta ferramenta ainda não tinha devolvido na janela, ou
 *  - argumentos novos com resposta curta (ações como clicar/digitar devolvem "OK" genérico).
 *
 * A cada progresso a contagem recomeça. Assim, chamadas com argumentos diferentes e respostas
 * diferentes nunca disparam, e repetições espaçadas por trabalho útil também não. Continua
 * pegando o loop real: mesma chamada + mesmo resultado, ou alternância A/B sem nada novo.
 */
class ToolLoopDetector(private val window: Int = 24) {
    enum class Kind { SAME_CALL, SAME_CONTENT }
    data class Trip(val tool: String, val kind: Kind)

    private data class Entry(val seq: Long, val callKey: String, val call: String, val result: String)

    private val entries = ArrayDeque<Entry>()
    private var seq = 0L
    private var progressSeq = 0L

    /**
     * @param callThreshold quantas vezes a mesma chamada (nome+args+resultado) pode aparecer sem progresso.
     * @param contentThreshold se não nulo, quantas vezes o mesmo conteúdo longo pode voltar por argumentos
     *   diferentes (ex.: reler cópias do mesmo arquivo). Só para leituras puras.
     */
    fun record(
        name: String,
        normArgs: String,
        resultHash: Int,
        longResult: Boolean,
        callThreshold: Int,
        contentThreshold: Int? = null,
    ): Trip? {
        val s = ++seq
        val callKey = "$name|$normArgs"
        val call = "$callKey|$resultHash"
        val result = "$name#$resultHash"
        val newResult = entries.none { it.result == result }
        val newArgs = entries.none { it.callKey == callKey }
        if (newResult || (newArgs && !longResult)) progressSeq = s
        entries.addLast(Entry(s, callKey, call, result))
        while (entries.size > window) entries.removeFirst()

        val sinceProgress = entries.filter { it.seq >= progressSeq }
        if (sinceProgress.count { it.call == call } >= callThreshold) return Trip(name, Kind.SAME_CALL)
        if (contentThreshold != null && longResult && sinceProgress.count { it.result == result } >= contentThreshold)
            return Trip(name, Kind.SAME_CONTENT)
        return null
    }

    /** Houve progresso desde a última verificação? (usado para zerar avisos ao modelo) */
    fun progressedAt(sequence: Long): Boolean = progressSeq > sequence

    val sequence: Long get() = seq

    fun reset() {
        entries.clear(); seq = 0; progressSeq = 0
    }
}
