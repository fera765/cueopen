package dev.fluwy.app.agent

/**
 * Leituras de saídas arquivadas (.fluwy/out) por tarefa. O agente entrava em ciclo relendo snapshots antigos a cada
 * etapa (inclusive depois de o coordenador mandar parar). Regras por tarefa (até a próxima mensagem real do usuário):
 *  - o mesmo arquivo arquivado é lido no máximo [maxSameFile] vezes;
 *  - no máximo [maxDistinct] arquivos arquivados diferentes;
 *  - arquivo minúsculo (resto de microcompactação) não vale a leitura: a prévia no contexto já é quase tudo.
 */
class ArchiveReadGuard(private val maxSameFile: Int = 1, private val maxDistinct: Int = 6, private val tinyBytes: Long = 600) {
    private data class State(val task: String, val counts: HashMap<String, Int> = HashMap())
    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    /** null = pode ler; texto = recusa (não começa com ERRO: para não contar como falha de arquivo). */
    fun check(sessionId: String, taskKey: String, fileName: String, sizeBytes: Long): String? {
        val st = states.compute(sessionId) { _, cur -> if (cur == null || cur.task != taskKey) State(taskKey) else cur }!!
        synchronized(st) {
            val n = st.counts[fileName] ?: 0
            if (sizeBytes in 1..tinyBytes && n == 0 && st.counts.keys.count { it != fileName } >= 2)
                return "AVISO: esta saída arquivada tem só $sizeBytes bytes — a prévia que já está no contexto mostra praticamente tudo. Não leia; siga com o que tem."
            if (n >= maxSameFile)
                return "AVISO: você já leu $fileName nesta tarefa. Não releia saídas arquivadas: use suas anotações (notes_read) e o que já coletou, e avance para a entrega."
            if (n == 0 && st.counts.size >= maxDistinct)
                return "AVISO: limite de $maxDistinct saídas arquivadas lidas nesta tarefa. Pare de reler snapshots antigos: escreva agora o resultado (file_write) com o que já coletou ou faça uma consulta NOVA se faltar um dado."
            st.counts[fileName] = n + 1
            return null
        }
    }

    fun reset(sessionId: String) { states.remove(sessionId) }
}
