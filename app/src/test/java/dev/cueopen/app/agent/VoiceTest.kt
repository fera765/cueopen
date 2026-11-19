package dev.cueopen.app.agent

import dev.cueopen.app.assistant.DictationVad
import dev.cueopen.app.assistant.VoiceReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceTest {
    @Test fun pausaDepoisDaFalaEncerraODitado() {
        assertEquals(DictationVad.Check.CONTINUE, DictationVad.check(3_000, null))            // ainda não falou
        assertEquals(DictationVad.Check.NO_SPEECH, DictationVad.check(8_000, null))           // nunca falou
        assertEquals(DictationVad.Check.CONTINUE, DictationVad.check(5_000, 1_000))           // pausa curta no meio da frase
        assertEquals(DictationVad.Check.FINISH, DictationVad.check(5_000, 1_800))             // parou de falar → para de gravar
        assertEquals(DictationVad.Check.FINISH, DictationVad.check(60_000, 100))              // limite da gravação
    }

    @Test fun speakableStripsMarkdownAndLinks() {
        val s = VoiceReplies.speakable("**Pronto!** Veja [o relatório](https://x.com/r) em https://a.b/c\n```kotlin\nval x=1\n```")
        assertFalse(s.contains("**") || s.contains("https") || s.contains("val x"))
    }
}

class BestVoiceTest {
    private val v = listOf(
        dev.cueopen.app.assistant.VoiceReplies.VoiceInfo("pt-br-x-afs-local", "pt-BR", 400, false, false),
        dev.cueopen.app.assistant.VoiceReplies.VoiceInfo("pt-br-x-afs-network", "pt-BR", 500, true, false),
        dev.cueopen.app.assistant.VoiceReplies.VoiceInfo("en-us-x", "en-US", 500, false, false),
        dev.cueopen.app.assistant.VoiceReplies.VoiceInfo("pt-br-x-missing", "pt-BR", 500, false, true),
    )
    @org.junit.Test fun picksBestPortugueseVoice() {
        org.junit.Assert.assertEquals("pt-br-x-afs-network", dev.cueopen.app.assistant.VoiceReplies.bestVoice(v, online = true))
        org.junit.Assert.assertEquals("pt-br-x-afs-local", dev.cueopen.app.assistant.VoiceReplies.bestVoice(v, online = false))
    }
}
