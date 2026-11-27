package dev.fluwy.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileSearchTest {
    @Test fun `finds lines with paths and skips internal dirs`() {
        val root = Files.createTempDirectory("ws").toFile()
        File(root, "notes.md").writeText("linha 1\nO preço do plano é R$ 10\nfim")
        File(root, ".fluwy/out").mkdirs(); File(root, ".fluwy/out/o1.txt").writeText("preço no output salvo")
        File(root, ".fluwy/state").mkdirs(); File(root, ".fluwy/state/x.txt").writeText("preço interno")
        val r = FileSearch.search(root, root, "PREÇO", regex = false)
        assertTrue(r, r.contains("notes.md:2:")); assertTrue(r, r.contains(".fluwy/out/o1.txt:1:")); assertTrue(r, !r.contains("state"))
        assertTrue(FileSearch.search(root, root, "naoexiste", false).startsWith("Nenhuma"))
        assertTrue(FileSearch.search(root, root, "(", true).startsWith("ERRO"))
    }
}
