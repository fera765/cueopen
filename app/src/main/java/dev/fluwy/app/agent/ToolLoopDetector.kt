package dev.fluwy.app.agent

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
    /**
     * Inspirado no tools.loopDetection do OpenClaw: genericRepeat (SAME_CALL/SAME_CONTENT), pingPong,
     * disjuntor global sem progresso (NO_PROGRESS), ferramenta inexistente repetida (UNKNOWN_TOOL) e a
     * guarda pós-compactação (POST_COMPACTION): se logo após compactar o agente volta a emitir a mesma
     * tripla (ferramenta, args, resultado) de antes, a compactação não quebrou o loop.
     */
    enum class Kind { SAME_CALL, SAME_CONTENT, PING_PONG, NO_PROGRESS, UNKNOWN_TOOL, POST_COMPACTION }
    data class Trip(val tool: String, val kind: Kind)

    private data class Entry(val seq: Long, val callKey: String, val call: String, val result: String)

    private val entries = ArrayDeque<Entry>()
    private var seq = 0L
    private var progressSeq = 0L
    private var postGuard: Set<String>? = null
    private var postLeft = 0
    private var postHits = 0
    private val unknown = HashMap<String, Int>()

    /** Arma a guarda pós-compactação para as próximas [calls] chamadas. */
    fun armPostCompaction(calls: Int = POST_COMPACTION_WINDOW) {
        postGuard = entries.map { it.call }.toSet(); postLeft = calls; postHits = 0
    }

    /** Chamada a ferramenta que não existe/não está liberada neste passo. */
    fun unknownTool(name: String): Trip? {
        val n = unknown.merge(name, 1, Int::plus) ?: 1
        return if (n >= UNKNOWN_TOOL_THRESHOLD) Trip(name, Kind.UNKNOWN_TOOL) else null
    }

    /** Zera a contagem de uma ferramenta indisponível (depois de explicar ao modelo que ela não existe). */
    fun clearUnknown(name: String) { unknown.remove(name) }

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

        postGuard?.let { before ->
            if (call in before) postHits++
            if (--postLeft <= 0) postGuard = null
            if (postHits >= 2) { postGuard = null; return Trip(name, Kind.POST_COMPACTION) }
        }
        val sinceProgress = entries.filter { it.seq >= progressSeq }
        if (sinceProgress.count { it.call == call } >= callThreshold) return Trip(name, Kind.SAME_CALL)
        // Ping-pong: A,B,A,B,A,B com as duas chamadas e resultados idênticos, sem nada novo.
        // (a primeira ida e volta conta como progresso; daí em diante nada pode ser novo)
        if (entries.size >= PING_PONG_CALLS) {
            val last = entries.takeLast(PING_PONG_CALLS)
            val tail = last.map { it.call }
            val a = tail[0]; val b = tail[1]
            if (a != b && progressSeq <= last[1].seq && tail.withIndex().all { (i, c) -> c == if (i % 2 == 0) a else b })
                return Trip(name, Kind.PING_PONG)
        }
        // Disjuntor global: muitas chamadas seguidas sem nenhuma informação nova, seja qual for o padrão.
        if (sinceProgress.size >= NO_PROGRESS_CALLS) return Trip(name, Kind.NO_PROGRESS)
        if (contentThreshold != null && longResult && sinceProgress.count { it.result == result } >= contentThreshold)
            return Trip(name, Kind.SAME_CONTENT)
        return null
    }

    /** Houve progresso desde a última verificação? (usado para zerar avisos ao modelo) */
    fun progressedAt(sequence: Long): Boolean = progressSeq > sequence

    val sequence: Long get() = seq

    fun reset() {
        entries.clear(); seq = 0; progressSeq = 0; postGuard = null; postLeft = 0; postHits = 0; unknown.clear()
    }

    companion object {
        const val PING_PONG_CALLS = 6
        const val NO_PROGRESS_CALLS = 14
        const val UNKNOWN_TOOL_THRESHOLD = 3
        const val POST_COMPACTION_WINDOW = 3
    }
}
