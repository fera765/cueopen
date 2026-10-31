package dev.cueopen.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationStatusTest {
    @Test
    fun mainFrameHttpErrorsRemainErrorsWhenPageFinishes() {
        listOf(404, 410, 500, 503).forEach { statusCode ->
            val status = BrowserNavigationStatus()
            val error = status.recordHttpError(isMainFrame = true, statusCode = statusCode)
                ?: throw AssertionError("Expected an HTTP failure for main-frame status $statusCode")

            assertTrue(error, error.startsWith("ERROR: HTTP $statusCode"))
            assertTrue(error, error.contains("Não use este conteúdo"))
            assertTrue(error, error.contains("repita a URL"))
            assertEquals(error, status.pageFinished())
            assertEquals(error, status.preserveHttpError("OK"))
        }
    }

    @Test
    fun subresourceHttpErrorsDoNotFailTheMainPage() {
        val status = BrowserNavigationStatus()

        assertNull(status.recordHttpError(isMainFrame = false, statusCode = 404))
        assertEquals("OK", status.pageFinished())
        assertEquals("OK", status.preserveHttpError("OK"))
    }

    @Test
    fun successfulStatusDoesNotCreateNavigationError() {
        val status = BrowserNavigationStatus()

        assertNull(status.recordHttpError(isMainFrame = true, statusCode = 200))
        assertFalse(status.pageFinished().startsWith("ERROR:"))
        assertEquals("OK", status.pageFinished())
    }

    @Test
    fun firstVisibleFrameIsPartialUntilDomReadinessIsConfirmed() {
        val status = BrowserNavigationStatus()

        val committed = status.pageCommitted("http://localhost:4174/")
        val incomplete = status.incompleteMessage("http://localhost:4174/", "http://localhost:4174/", "interactive", 120)

        assertTrue(committed, committed.startsWith("PARTIAL:"))
        assertTrue(committed, committed.contains("primeiro quadro"))
        assertTrue(incomplete, incomplete.startsWith("PARTIAL:"))
        assertTrue(incomplete, incomplete.contains("document.readyState=interactive"))
        assertTrue(incomplete, incomplete.contains("120 caracteres"))
    }

    @Test
    fun mainFrameHttpErrorOverridesAnEarlierPartialCommit() {
        val status = BrowserNavigationStatus()
        val partial = status.pageCommitted("https://example.test/")

        val error = status.recordHttpError(isMainFrame = true, statusCode = 503)

        assertTrue(partial, partial.startsWith("PARTIAL:"))
        assertTrue(error.orEmpty(), status.preserveHttpError(partial).startsWith("ERROR: HTTP 503"))
    }

    @Test
    fun localConnectionRefusalExplainsThatLocalhostBelongsToTheWebViewDevice() {
        val status = BrowserNavigationStatus()

        val error = status.recordNetworkError(true, "http://localhost:4174/", "net::ERR_CONNECTION_REFUSED")

        assertTrue(error.orEmpty(), error.orEmpty().startsWith("ERROR:"))
        assertTrue(error.orEmpty(), error.orEmpty().contains("4174"))
        assertTrue(error.orEmpty(), error.orEmpty().contains("próprio dispositivo/ambiente do WebView"))
        assertTrue(error.orEmpty(), error.orEmpty().contains("Não repita"))
        assertEquals(error, status.preserveHttpError("OK"))
    }

    @Test
    fun subresourceNetworkErrorsDoNotFailTheMainPage() {
        val status = BrowserNavigationStatus()

        assertNull(status.recordNetworkError(false, "http://localhost:4174/assets/app.js", "net::ERR_CONNECTION_REFUSED"))
        assertEquals("OK", status.pageFinished())
    }

    @Test
    fun timeoutDoesNotCallVisibleTextReadyAndIncludesNavigationDiagnostics() {
        val status = BrowserNavigationStatus()

        val partial = status.timeoutMessage("http://localhost:4174/", "http://localhost:4174/", "interactive", 250)
        val empty = status.timeoutMessage("http://localhost:4174/", "about:blank", "loading", 0)

        assertTrue(partial, partial.startsWith("PARTIAL:"))
        assertTrue(partial, partial.contains("não prova que o app esteja pronto"))
        assertTrue(empty, empty.startsWith("TIMEOUT:"))
        assertTrue(empty, empty.contains("logs do servidor"))
        assertTrue(empty, empty.contains("document.readyState=loading"))
    }

    @Test
    fun lateMainFrameHttpErrorOverridesEarlierPageFinishedResult() {
        val status = BrowserNavigationStatus()
        assertEquals("OK", status.pageFinished())

        val error = status.recordHttpError(isMainFrame = true, statusCode = 404)

        assertEquals(error, status.preserveHttpError("OK"))
    }
}
