package dev.fluwy.app.triggers

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteTriggerTest {
    private val before = "Notícia: prefeitura anuncia novo parque central\nAtualizado há 5 minutos por nossa redação\n"

    @Test fun onlyNewTextWakesTheSession() {
        val base = TriggerLogic.diff(TriggerLogic.siteItems(before), emptyList(), "")
        val after = before.replace("há 5 minutos", "há 12 minutos") + "Notícia: escola municipal abre inscrições hoje\n"
        val d = TriggerLogic.diff(TriggerLogic.siteItems(after), base.seen, "")
        assertEquals(1, d.fresh.size)
        assertEquals(true, d.fresh.single().toString().contains("escola municipal"))
    }

    @Test fun shortNoiseIsIgnored() = assertEquals(0, TriggerLogic.siteItems("Menu\nEntrar\nOK").size)
}
