package dev.cueopen.app.agent

import java.text.Normalizer

/**
 * Ordem de PARAR vinda do usuário (como o "interrupt" do OpenBot), com ou sem pedido de entrega final.
 * "pare agora e me mande um resumo" → para tudo (agentes, ciclos, autonomia) e faz só a entrega.
 * Conservador: "pare de usar o Reddit" é ajuste de rumo, não parada; só conta parada do trabalho/agente/processo.
 */
object StopIntent {
    data class Result(val stop: Boolean, val deliver: Boolean)

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    // Verbo de parada + alvo (tudo/agente/processo/sessão…) ou "agora"/fim de frase.
    private val stopWithTarget = Regex(
        "\\b(pare|para|parar|paralis[ea]|interromp[ae]|interromper|encerr[ae]|encerrar|finaliz[ae]|finalizar|cancel[ae]|cancelar|desliga|desligue|suspend[ae]|stop)\\b" +
        "[^.!?\\n]{0,30}?\\b(agora|ja|imediatamente|tudo|todos|todo o|o agente|os agentes|agente|agentes|processo|processos|sessao|secao|execucao|trabalho|tarefa|tarefas|ciclo|ciclos|autonomia|loop|bot|time|equipe|coordenador|isso|it|now|everything)\\b")
    private val stopAlone = Regex("(^|[.!?,;\\n]\\s*)(pare|para|parar|stop|chega|interrompa|encerre)\\s*(ja|agora|tudo)?\\s*([.!?]|$)")
    private val stopDoingTask = Regex("\\b(pare|para|parar) de (trabalhar|rodar|executar|pesquisar|fazer isso|fazer tudo|processar|rodar o ciclo)")
    // "pare de usar X / de responder em inglês" = ajuste, não parada.
    private val adjustOnly = Regex("\\b(pare|para|parar) de (usar|responder|falar|escrever|mandar|enviar|perguntar|repetir|colocar|incluir|citar)\\b")
    private val negated = Regex("\\b(nao|nunca|sem) (pare|para|parar|interromp|encerr)")

    private val deliver = Regex(
        "\\b(entreg[ua]e?|entregar|retorn[ea]|retornar|me (de|da|passe|passa|mande|manda|envie|envia|mostre|mostra|traga|traz|diga|diz|fale|fala)|" +
        "mand[ea] (pra mim|para mim|aqui)|resum[ao]|resumir|relatorio|principais pontos|pontos principais|o que (voce|vc) (fez|ja fez|encontrou|descobriu)|" +
        "cri[ea] (um |o )?arquivo|gere (um |o )?arquivo|salve (um |o |em )?arquivo|escreva (um |o )?(arquivo|documento|relatorio)|consolide|compile|conclusao|parcial)")

    fun parse(text: String): Result {
        val t = norm(text.trim())
        if (t.isBlank() || t.startsWith("/") || negated.containsMatchIn(t)) return Result(false, false)
        val stop = (stopWithTarget.containsMatchIn(t) || stopAlone.containsMatchIn(t) || stopDoingTask.containsMatchIn(t)) &&
            !(adjustOnly.containsMatchIn(t) && !stopDoingTask.containsMatchIn(t) && !Regex("\\b(agora|tudo|agente|processo|sessao|secao)\\b").containsMatchIn(t))
        return Result(stop, stop && deliver.containsMatchIn(t))
    }
}
