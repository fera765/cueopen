package dev.fluwy.app.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputTest {
    @Test
    fun silentFailureStatesThatCapturedStreamsWereEmptyAndKeepsExitCode() {
        val formatted = ShellOutput.formatExecutionResult(raw = "", exitCode = 1, timedOut = false)

        assertTrue(formatted, formatted.contains("stdout/stderr capturados vazios"))
        assertTrue(formatted, formatted.endsWith("[exit 1]"))
    }

    @Test
    fun silentSuccessAndTimeoutAreReportedSeparately() {
        val success = ShellOutput.formatExecutionResult(raw = "", exitCode = 0, timedOut = false)
        val timeout = ShellOutput.formatExecutionResult(raw = "", exitCode = -1, timedOut = true)

        assertTrue(success, success.contains("comando concluído sem saída"))
        assertTrue(success, success.endsWith("[exit 0]"))
        assertTrue(timeout, timeout.contains("stdout/stderr vazios antes do timeout"))
        assertTrue(timeout, timeout.contains("[timeout]"))
        assertTrue(timeout, timeout.endsWith("[exit -1]"))
    }

    @Test
    fun capturedOutputIsPreservedAlongsideNonzeroExitCode() {
        assertEquals("diagnóstico\n[exit 2]", ShellOutput.formatExecutionResult("diagnóstico", 2, false))
    }

    @Test
    fun missingCaptureFileDoesNotMasqueradeAsEmptyCommandOutput() {
        val directory = Files.createTempDirectory("shell-output-test").toFile()
        try {
            val missing = File(directory, "missing.log")
            assertTrue(ShellOutput.readCapturedFile(missing).contains("não foi criado"))

            val empty = File(directory, "empty.log").apply { writeText("") }
            assertEquals("", ShellOutput.readCapturedFile(empty))
        } finally {
            directory.deleteRecursively()
        }
    }

    @org.junit.Test fun longOutputKeepsHeadAndTailWithPointer() {
        val text = "INICIO\n" + "x".repeat(50_000) + "\nERRO FINAL"
        val out = ShellOutput.headTail(text, ShellOutput.INLINE_LIMIT, "/root/workspace/.fluwy/out/cmd_1.log")
        org.junit.Assert.assertTrue(out.startsWith("INICIO"))
        org.junit.Assert.assertTrue(out.endsWith("ERRO FINAL"))
        org.junit.Assert.assertTrue(out.contains("cmd_1.log"))
        org.junit.Assert.assertTrue(out.length < ShellOutput.INLINE_LIMIT + 300)
    }
}
