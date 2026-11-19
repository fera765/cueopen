package dev.cueopen.app.agent

import dev.cueopen.app.assistant.DictationVad
import dev.cueopen.app.assistant.VoiceReplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceTest {
    @Test fun silenceAfterSpeechEndsDictationWithoutRelistening() {
        assertEquals(DictationVad.Action.COMMIT, DictationVad.onSilence(hasDictation = true, silentRounds = 1))
        assertEquals(DictationVad.Action.LISTEN_AGAIN, DictationVad.onSilence(false, 1))
        assertEquals(DictationVad.Action.GIVE_UP, DictationVad.onSilence(false, 2))
    }

    @Test fun speakableStripsMarkdownAndLinks() {
        val s = VoiceReplies.speakable("**Pronto!** Veja [o relatório](https://x.com/r) em https://a.b/c\n```kotlin\nval x=1\n```")
        assertFalse(s.contains("**") || s.contains("https") || s.contains("val x"))
    }
}
