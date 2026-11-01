package dev.cueopen.app.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOutputRetentionPolicyTest {
    @Test
    fun referencedArchivesAreNeverPruned() {
        val directory = Files.createTempDirectory("cue-context-out").toFile()
        try {
            val files = (0 until 45).map { index ->
                File(directory, "o${index.toString(16)}_${1000 + index}.txt").apply {
                    writeText("output $index")
                    setLastModified(1_000L + index)
                }
            }
            val referenced = setOf(files[0].name, files[5].name)

            val deletions = ContextOutputRetentionPolicy.filesToDelete(files, referenced)

            assertFalse(deletions.any { it.name in referenced })
            assertEquals(3, deletions.size)
            assertTrue(deletions.all { it.name in setOf(files[1].name, files[2].name, files[3].name) })
        } finally {
            directory.deleteRecursively()
        }
    }
}
