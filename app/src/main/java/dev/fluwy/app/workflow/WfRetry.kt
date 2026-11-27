package dev.fluwy.app.workflow

/**
 * Nova tentativa só para leituras (GET/HEAD) com falha passageira: rede, 408, 425, 429 e 5xx.
 * Envio (POST/PUT/...) nunca repete sozinho — evita e-mail/pedido duplicado.
 */
object WfRetry {
    const val MAX_RETRIES = 2

    fun transient(error: String?): Boolean {
        val e = error ?: return false
        if (e.startsWith("Falha de rede")) return true
        val code = Regex("^HTTP (\\d{3})").find(e)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code == 408 || code == 425 || code == 429 || code in 500..599
    }

    fun shouldRetry(method: String, error: String?, attempt: Int): Boolean =
        attempt < MAX_RETRIES && method.uppercase() in setOf("GET", "HEAD") && transient(error)

    /** 1,5 s e depois 4 s. */
    fun backoffMs(attempt: Int): Long = if (attempt <= 0) 1_500 else 4_000
}
