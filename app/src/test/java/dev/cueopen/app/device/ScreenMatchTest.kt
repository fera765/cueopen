package dev.cueopen.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMatchTest {
    private val screen = """
        Tela acessível no app com.whatsapp. snapshot_id=abc-123. IDs temporários (máx. 30s); execute uma ação por vez e releia após cada mudança.
        [1] TextView · Maria Souza
        [2] EditText · Mensagem · editable
        [3] ImageButton · Enviar · clickable
        [4] TextView · Enviar arquivo
        [5] Button · Configurações · clickable
        [6] ViewGroup · clickable
    """.trimIndent()

    @Test fun `parses snapshot id labels and flags`() {
        assertEquals("abc-123", ScreenMatch.snapshotId(screen))
        val els = ScreenMatch.elements(screen)
        assertEquals(6, els.size)
        assertEquals(Triple(3, "Enviar", true), els[2])
        assertEquals(Triple(6, "", true), els[5])
    }

    @Test fun `exact clickable label wins and accents and case are ignored`() {
        assertEquals(3, ScreenMatch.find(screen, "enviar")!!.elementId)
        assertEquals(5, ScreenMatch.find(screen, "CONFIGURACOES")!!.elementId)
        assertEquals(4, ScreenMatch.find(screen, "arquivo")!!.elementId)
        assertEquals(4, ScreenMatch.find(screen, "enviar", occurrence = 2)!!.elementId)
        assertNull(ScreenMatch.find(screen, "não existe"))
        assertNull(ScreenMatch.find("sem snapshot", "enviar"))
    }
}
