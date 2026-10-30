package dev.cueopen.app.agent

/** Memoiza resultados apenas pela assinatura exata do chamado; cada instância vive no loop de uma sessão. */
internal class ExactToolCallMemo<T> {
    private val values = HashMap<String, T>()

    operator fun get(signature: String): T? = values[signature]
    operator fun set(signature: String, result: T) {
        values[signature] = result
    }
}
