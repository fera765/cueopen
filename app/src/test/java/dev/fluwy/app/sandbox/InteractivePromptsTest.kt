package dev.fluwy.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractivePromptsTest {
    @Test fun detectaPerguntasComuns() {
        assertEquals("Ok to proceed? (y)", InteractivePrompts.waitingPrompt("Need to install the following packages:\ncreate-vite@9.3.4\nOk to proceed? (y) "))
        assertNotNull(InteractivePrompts.waitingPrompt("Do you want to continue? [Y/n]"))
        assertNotNull(InteractivePrompts.waitingPrompt("◆  Select a framework:"))
        assertNotNull(InteractivePrompts.waitingPrompt("? Project name: › vite-project"))
        assertNotNull(InteractivePrompts.waitingPrompt("[sudo] password for root:"))
        assertNotNull(InteractivePrompts.waitingPrompt("\u001B[36m◇\u001B[39m  Current directory is not empty. Please choose how to proceed:"))
    }

    @Test fun naoConfundeSaidaNormal() {
        assertNull(InteractivePrompts.waitingPrompt("added 120 packages in 14s"))
        assertNull(InteractivePrompts.waitingPrompt("  VITE v5.4.11  ready in 900 ms\n  ➜  Local:   http://127.0.0.1:5173/"))
        assertNull(InteractivePrompts.waitingPrompt(""))
    }

    @Test fun dicaDependeDaFerramenta() {
        val npm = InteractivePrompts.hint("cd app && npm create vite@latest . -- --template react", "Ok to proceed? (y)")
        assertTrue(npm.contains("npx --yes"))
        assertTrue(npm.contains("Não repita"))
        assertTrue(InteractivePrompts.hint("apt-get install curl", null).contains("-y"))
        assertTrue(InteractivePrompts.hint("algum-cli init", null).contains("--yes"))
        assertTrue(InteractivePrompts.looksCancelled("│\n└  Operation cancelled"))
    }
}
