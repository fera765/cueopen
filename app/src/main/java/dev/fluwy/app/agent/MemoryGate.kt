package dev.fluwy.app.agent

import java.text.Normalizer

/**
 * Só vale chamar o modelo para extrair memórias quando o usuário disse algo duradouro (preferência, identidade,
 * projeto, pedido para lembrar). Tarefas comuns não geram essa chamada extra.
 */
object MemoryGate {
    private val signals = Regex(
        "\\b(prefiro|prefer|sempre|nunca|meu nome|me chamo|eu sou|sou (o|a|um|uma)|trabalho (com|na|no|em)|minha empresa|meu (projeto|app|site|canal|perfil|email|cliente|negocio|time)|" +
        "lembr|anot[ae]|guard[ae]|nao gosto|gosto de|odeio|use sempre|evite|chama-se|moro|meu fuso|idioma|responda (em|sempre)|formato|tom de voz|daqui (pra|para) frente|a partir de agora)")

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun worthExtracting(userTexts: List<String>): Boolean = userTexts.any { signals.containsMatchIn(norm(it)) }
}
