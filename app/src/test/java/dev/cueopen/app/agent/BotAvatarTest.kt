package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class BotAvatarTest {
    @Test
    fun cadaBotTemAvatarDiferente() {
        val pares = (0 until 96).map { BotCatalog.avatarAt(it) }
        assertEquals(96, pares.toSet().size)
        assertEquals(12, (0 until 56).map { BotCatalog.avatarAt(it).first }.toSet().size)
    }
}
