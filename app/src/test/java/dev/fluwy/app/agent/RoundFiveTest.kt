package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.web.YouTubeTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundFiveTest {
    @Test fun multipleMessagesAreAllPending() {
        val items = listOf(
            ChatItem.User(text = "pedido antigo"), ChatItem.Assistant(text = "feito", final = true),
            ChatItem.User(text = "crie a planilha"), ChatItem.User(text = "e mande o resumo por email"),
        )
        assertEquals(listOf("crie a planilha", "e mande o resumo por email"), MultiRequest.pending(items, 3))
        val later = items + ChatItem.User(text = "também agende para amanhã")
        assertEquals(3, MultiRequest.pending(later, 3).size)
        assertEquals(1, MultiRequest.pending(listOf(ChatItem.Assistant(text = "x", final = true), ChatItem.User(text = "só um")), 2).size)
    }

    @Test fun combineNumbersMessages() {
        val c = MultiRequest.combine(listOf("a", "b"))
        assertTrue(c.contains("Mensagem 1:\na") && c.contains("Mensagem 2:\nb"))
        assertEquals("só", MultiRequest.combine(listOf("só")))
        assertTrue(MultiRequest.checkPrompt(listOf("x", "y")).contains("2. y"))
    }

    @Test fun uselessCompactionDetected() {
        assertTrue(CompactionProgress.useless(10_000, 9_500, 20_000))   // encolheu < 10%
        assertTrue(CompactionProgress.useless(30_000, 19_000, 20_000))  // ainda acima de 90%
        assertFalse(CompactionProgress.useless(30_000, 8_000, 20_000))
    }

    @Test fun youtubeIdsAndTranscript() {
        assertEquals("dQw4w9WgXcQ", YouTubeTranscript.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", YouTubeTranscript.videoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeTranscript.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(YouTubeTranscript.videoId("https://example.com/watch?v=x"))
        val html = """..."captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en","name":{"simpleText":"English"},"languageCode":"en","kind":"asr"},{"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=pt","name":{"runs":[{"text":"Português"}]},"languageCode":"pt-BR"}],"audioTracks":[]}}..."""
        val tracks = YouTubeTranscript.tracks(html)
        assertEquals(2, tracks.size)
        val pick = YouTubeTranscript.pick(tracks)!!
        assertEquals("pt-BR", pick.lang)
        assertTrue(pick.baseUrl.contains("&lang=pt"))
        val xml = """<transcript><text start="0.5" dur="2">Ol&amp;#39;á mundo</text><text start="40.0" dur="1">segunda &amp;amp; parte</text></transcript>"""
        val lines = YouTubeTranscript.parse(xml)
        assertEquals("Ol'á mundo", lines[0].second)
        val out = YouTubeTranscript.format(lines, 5000)
        assertTrue(out.contains("[00:00] Ol'á mundo") && out.contains("[00:40] segunda & parte"))
        assertEquals(1, YouTubeTranscript.parse("""<timedtext><body><p t="61000" d="1">oi</p></body></timedtext>""").size)
    }
}
