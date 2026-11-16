package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class StopIntentTest {
    private fun p(s: String) = StopIntent.parse(s).let { it.stop to it.deliver }

    @Test fun `stop with delivery`() {
        assertEquals(true to true, p("Quero que você me retorne agora os principais pontos e pare agora o agente"))
        assertEquals(true to true, p("crie um arquivo com o resumo do que você fez e pare o processo"))
        assertEquals(true to true, p("Para tudo e me manda o relatório parcial"))
        assertEquals(true to true, p("pare de trabalhar e me diga o que você encontrou"))
    }

    @Test fun `plain stop`() {
        assertEquals(true to false, p("Pare."))
        assertEquals(true to false, p("para agora"))
        assertEquals(true to false, p("encerre a sessão"))
        assertEquals(true to false, p("interrompa os agentes"))
    }

    @Test fun `adjustments and negations are not a stop`() {
        assertEquals(false to false, p("pare de usar o Reddit, procure no Google"))
        assertEquals(false to false, p("não pare, continue até o fim"))
        assertEquals(false to false, p("me mande o resumo"))
        assertEquals(false to false, p("o app para de funcionar quando abro a câmera"))
        assertEquals(false to false, p("/pause"))
    }
}
