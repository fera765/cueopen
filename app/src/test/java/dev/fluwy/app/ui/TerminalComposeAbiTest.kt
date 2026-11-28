package dev.fluwy.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalComposeAbiTest {
    @Test
    fun terminalEffectIsKeyedByStableStringRatherThanInnerSessionObject() {
        val candidates = listOf(
            File("src/main/java/dev/fluwy/app/ui/ComputerScreen.kt"),
            File("app/src/main/java/dev/fluwy/app/ui/ComputerScreen.kt"),
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
        assertTrue("ComputerScreen.kt must be available to the ABI regression test", sourceFile != null)
        val source = sourceFile!!.readText()

        assertTrue(
            "DisposableEffect must use the String key so generated Compose bytecode cannot link Session.\$stable",
            Regex("DisposableEffect\\(key\\)").containsMatchIn(source),
        )
    }
}
