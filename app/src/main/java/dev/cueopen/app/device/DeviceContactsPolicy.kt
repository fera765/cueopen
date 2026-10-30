package dev.cueopen.app.device

/** Regras de minimização para a ferramenta de busca de contatos do agente. */
object DeviceContactsPolicy {
    const val MAX_QUERY_LENGTH = 80
    const val MAX_RESULTS = 10

    fun normalizeQuery(query: String): String {
        val term = query.trim().take(MAX_QUERY_LENGTH)
        require(term.count(Char::isLetterOrDigit) >= 2) {
            "Informe pelo menos dois caracteres do nome ou número; a agenda inteira não pode ser listada."
        }
        return term
    }

    fun likePattern(term: String): String = "%" + term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"
}
