package dev.cueopen.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SectionSharingTest {
    @After fun reset() { SecretVault.scopeOf = null; listOf("coord", "membro", "outro", "solo", "s1", "section_A", "section_B").forEach { SecretVault.clear(it) } }

    @Test fun `secret typed in the coordinator chat is visible to every agent of the section`() {
        val section = mapOf("coord" to "section_A", "membro" to "section_A", "outro" to "section_B")
        SecretVault.scopeOf = { section[it] }
        SecretVault.put("coord", mapOf("email" to "eu@x.com", "senha" to "segredo123"))
        assertEquals("eu@x.com", SecretVault.get("membro", "email"))
        assertEquals("login eu@x.com segredo123", SecretVault.resolve("membro", "login {{secret:email}} {{secret:senha}}"))
        assertEquals(listOf("email", "senha"), SecretVault.names("membro"))
        assertTrue(SecretVault.redact("membro", "senha=segredo123").contains("‹secret:senha›"))
        // Outra seção e chats comuns continuam isolados.
        assertFalse(SecretVault.has("outro", "email"))
        assertFalse(SecretVault.has("solo", "email"))
    }

    @Test fun `snapshot copies the origin chat secrets into the section`() {
        SecretVault.put("s1", mapOf("api_key" to "abc123"))
        SecretVault.scopeOf = { if (it == "coord" || it == "membro") "section_A" else null }
        SecretVault.put("coord", SecretVault.snapshot("s1"))
        assertEquals("abc123", SecretVault.get("membro", "api_key"))
    }

    @Test fun `shared context carries user info answers secrets and deliveries but not internal messages`() {
        val out = SectionSharedContext.render(
            originalRequest = "Crie a landing page",
            userMessages = listOf("Crie a landing page", "[Relatório automático — X terminou]", "Meu domínio é loja.com"),
            answers = listOf("Qual público?" to "Mulheres 30+"),
            secretNames = listOf("email", "senha"),
            deliveries = listOf(SectionSharedContext.Delivery("Redator", "copy", "salvo em copy.md")),
        )
        assertTrue(out.contains("Pedido original: Crie a landing page"))
        assertTrue(out.contains("Meu domínio é loja.com"))
        assertFalse(out.contains("Relatório automático"))
        assertEquals(1, Regex("Crie a landing page").findAll(out).count())
        assertTrue(out.contains("Mulheres 30+"))
        assertTrue(out.contains("{{secret:email}}") && out.contains("{{secret:senha}}"))
        assertTrue(out.contains("Entrega de Redator"))
        assertTrue(out.length <= SectionSharedContext.MAX_CHARS + 2)
    }

    @Test fun `empty section context renders nothing`() {
        assertEquals("", SectionSharedContext.render("", emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test fun `common spellings of the workspace root resolve instead of failing`() {
        val temp = kotlin.io.path.createTempDirectory().toFile()
        try {
            val ws = File(temp, "workspace").apply { mkdirs() }
            File(ws, "site").mkdirs()
            listOf(".", "./", "~", "/root", "/root/", "workspace", "/root/workspace/").forEach {
                assertEquals(it, ws.canonicalFile, AgentFilePathPolicy.taskWorkspace(ws, it, allowRoot = true))
            }
            listOf("./site", "~/workspace/site", "workspace/site", "/root/workspace//site/").forEach {
                assertEquals(it, File(ws, "site").canonicalFile, AgentFilePathPolicy.taskWorkspace(ws, it, allowRoot = true))
            }
            assertEquals(null, AgentFilePathPolicy.taskWorkspace(ws, "/etc", allowRoot = true))
        } finally { temp.deleteRecursively() }
    }
}
