package dev.fluwy.app.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Várias chaves (contas) por provedor, uma por linha: quando a chave em uso bate o limite (429), passa para a próxima.
 * Depois de dar a volta em todas sem sucesso, desiste e deixa o Modelo alternativo / a espera agirem.
 */
object KeyPool {
    private data class State(val index: Int, val tried: Int)
    private val states = ConcurrentHashMap<String, State>()

    fun keys(raw: String): List<String> = raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun id(raw: String) = keys(raw).joinToString("|").hashCode().toString()

    /** Chave a usar agora. */
    fun current(raw: String): String {
        val k = keys(raw)
        if (k.size <= 1) return k.firstOrNull() ?: raw.trim()
        return k[(states[id(raw)]?.index ?: 0) % k.size]
    }

    /** A chave atual bateu o limite: passa para a próxima. False se não há outra ou todas já foram tentadas. */
    fun rotate(raw: String): Boolean {
        val k = keys(raw)
        if (k.size <= 1) return false
        val st = states[id(raw)] ?: State(0, 0)
        if (st.tried >= k.size - 1) return false
        states[id(raw)] = State((st.index + 1) % k.size, st.tried + 1)
        return true
    }

    /** Uma chamada deu certo: a chave atual continua, e a volta de tentativas zera. */
    fun onSuccess(raw: String) {
        val key = id(raw)
        states[key]?.let { states[key] = it.copy(tried = 0) }
    }

    /** "2 contas" ou "" para mostrar na tela. */
    fun describe(raw: String): String = keys(raw).size.let { if (it > 1) "$it contas" else "" }

    fun reset() = states.clear()
}
