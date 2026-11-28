package dev.fluwy.app.agent

/** Memoiza pela assinatura do chamado; cada instância vive somente durante uma invocação do loop do agente. */
internal class ExactToolCallMemo<T> {
    private val values = HashMap<String, T>()

    operator fun get(signature: String): T? = values[signature]
    operator fun set(signature: String, result: T) {
        values[signature] = result
    }
}
