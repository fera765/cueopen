package dev.fluwy.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessScriptTest {
    @Test
    fun scriptIsIdempotentAndExposesState() {
        val js = BrowserHost.READINESS_JS
        assertTrue(js.contains("if (window.__fluwyLoad) return;"))
        assertTrue(js.contains("window.__fluwyLoadState"))
        // polling/streaming longo não pode segurar a leitura para sempre
        assertTrue(js.contains("< 10000"))
        // não pode reutilizar o nome do inspetor de rede já existente
        assertFalse(js.contains("window.__fluwyNet ="))
    }
}
