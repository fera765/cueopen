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
    fun lateMainFrameHttpErrorOverridesEarlierPageFinishedResult() {
        val status = BrowserNavigationStatus()
        assertEquals("OK", status.pageFinished())

        val error = status.recordHttpError(isMainFrame = true, statusCode = 404)

        assertEquals(error, status.preserveHttpError("OK"))
    }
}
