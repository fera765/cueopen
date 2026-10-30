package dev.cueopen.app.uiconnector.custom

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomConnectorStoreTest {
    @Test fun `JSON legado valido migra para YAML e so depois e removido`() = withTempStore { dir, store ->
        val legacy = File(dir, "oldapi.json").apply { writeText(legacyJson(name = "Legado")) }

        val loaded = store.loadAll()

        assertEquals("Legado", loaded.single().name)
        val yaml = File(dir, "oldapi.yaml")
        assertTrue(yaml.isFile)
        assertTrue(yaml.readText().contains("id: oldapi"))
        assertFalse(legacy.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".yaml.tmp") })
    }

    @Test fun `YAML corrompido nao oculta JSON legado valido e recuperacao e atomica`() = withTempStore { dir, store ->
        File(dir, "oldapi.yaml").writeText("id: [quebrado")
        val legacy = File(dir, "oldapi.json").apply { writeText(legacyJson(name = "Recuperado")) }

        val loaded = store.loadAll()

        assertEquals("Recuperado", loaded.single().name)
        val recovered = CustomDefParser.parse(File(dir, "oldapi.yaml").readText()).def
        assertEquals("Recuperado", recovered?.name)
        assertFalse(legacy.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".yaml.tmp") })
    }

    @Test fun `YAML valido tem precedencia sobre JSON legado concorrente`() = withTempStore { dir, store ->
        File(dir, "oldapi.yaml").writeText(simpleYaml("YAML atual"))
        val legacy = File(dir, "oldapi.json").apply { writeText(legacyJson(name = "Cópia antiga")) }

        val loaded = store.loadAll()

        assertEquals("YAML atual", loaded.single().name)
        assertTrue("a cópia não é removida sem migração verificada", legacy.exists())
        assertEquals("YAML atual", CustomDefParser.parse(store.rawYaml("oldapi").orEmpty()).def?.name)
    }

    @Test fun `save substitui arquivo anterior sem deixar temporario e normaliza JSON para YAML`() = withTempStore { dir, store ->
        File(dir, "oldapi.yaml").writeText("conteúdo interrompido")
        val result = store.save(legacyJson(name = "Salvo"), emptySet())

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        assertEquals("Salvo", result.getOrNull()?.name)
        assertNotNull(CustomDefParser.parse(File(dir, "oldapi.yaml").readText()).def)
        assertTrue(File(dir, "oldapi.yaml").readText().contains("id: oldapi"))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".yaml.tmp") })
    }

    private fun withTempStore(block: (File, CustomConnectorStore) -> Unit) {
        val dir = Files.createTempDirectory("cue-uic-store-").toFile()
        try { block(dir, CustomConnectorStore(dir)) } finally { dir.deleteRecursively() }
    }

    private fun simpleYaml(name: String) = """
        id: oldapi
        name: "$name"
        description: Definição para teste
        version: "1.0"
        baseUrl: https://api.example.com
    """.trimIndent()

    private fun legacyJson(name: String) = """{"id":"oldapi","name":"$name","description":"Definição para teste","version":"1.0","baseUrl":"https://api.example.com"}"""
}
