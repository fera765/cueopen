package dev.fluwy.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSenderTest {
    @Test fun `only real discord webhooks are accepted`() {
        assertTrue(ChannelSender.validDiscordWebhook("https://discord.com/api/webhooks/123456/abcDEF_-x"))
        assertTrue(ChannelSender.validDiscordWebhook("https://canary.discordapp.com/api/webhooks/1/x"))
        assertFalse(ChannelSender.validDiscordWebhook("http://discord.com/api/webhooks/1/x"))
        assertFalse(ChannelSender.validDiscordWebhook("https://evil.com/api/webhooks/1/x"))
        assertFalse(ChannelSender.validDiscordWebhook("https://discord.com.evil.com/api/webhooks/1/x"))
    }

    @Test fun `configured channels need every required field`() {
        val none = ChannelSender.Config(telegramToken = "t")
        assertEquals(emptyList<ChannelSender.Channel>(), ChannelSender.configured(none))
        val all = ChannelSender.Config("t", "123", "https://discord.com/api/webhooks/1/a", "xoxb", "C1")
        assertEquals(ChannelSender.Channel.entries.toList(), ChannelSender.configured(all))
    }

    @Test fun `mime image detection packages and whatsapp number`() {
        assertEquals("image/png", ChannelSender.mime("a.PNG"))
        assertEquals("application/pdf", ChannelSender.mime("rel.pdf"))
        assertEquals("application/octet-stream", ChannelSender.mime("x.bin"))
        assertTrue(ChannelSender.isImage("foto.jpeg")); assertFalse(ChannelSender.isImage("doc.pdf"))
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), ChannelSender.packagesFor("WhatsApp"))
        assertEquals(emptyList<String>(), ChannelSender.packagesFor("signal"))
        assertEquals("5511999998888@s.whatsapp.net", ChannelSender.whatsappJid("+55 (11) 99999-8888"))
        assertNull(ChannelSender.whatsappJid("123"))
        assertEquals(ChannelSender.Channel.SLACK, ChannelSender.channel(" Slack "))
    }
}
