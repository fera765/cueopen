package dev.cueopen.app.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFileToolFailureGuardTest {
    @Test
    fun identicalMissingFileFailureStopsAfterOneRecoveryAttempt() {
        val guard = AgentFileToolFailureGuard()
        val args = "{\"path\":\"/root/workspace/NOTES.md\"}"
        val error = "ERRO: arquivo não existe neste workspace: /root/workspace/NOTES.md"

        assertNull(guard.record("file_read", args, error))
        assertNotNull(guard.record("file_read", args, error))
    }

    @Test
    fun successfulListingResetsDifferentPathFailureStreak() {
        val guard = AgentFileToolFailureGuard()
        assertNull(guard.record("file_read", "{\"path\":\"a.txt\"}", "ERRO: arquivo não existe"))
        assertNull(guard.record("file_list", "{\"path\":\"\"}", "📄 real.txt (12b)"))
        assertNull(guard.record("file_read", "{\"path\":\"b.txt\"}", "ERRO: arquivo não existe"))
    }

    @Test
    fun differentMissingPathsStopAfterThreeFailuresWithoutProgress() {
        val guard = AgentFileToolFailureGuard()
        assertNull(guard.record("file_read", "{\"path\":\"a.txt\"}", "ERRO: arquivo não existe"))
        assertNull(guard.record("file_read", "{\"path\":\"b.txt\"}", "ERRO: arquivo não existe"))
        assertNotNull(guard.record("file_read", "{\"path\":\"c.txt\"}", "ERRO: arquivo não existe"))
    }

    @Test
    fun onlyFileReadAndListFailuresAffectTheGuard() {
        val guard = AgentFileToolFailureGuard()
        assertNull(guard.record("shell", "{}", "ERRO: command failed"))
        assertNull(guard.record("file_read", "{\"path\":\"ok.txt\"}", "ok.txt — linhas 1–1 de 1\nhello"))
    }
}
