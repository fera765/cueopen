package dev.fluwy.app.prompts

import java.io.File

/**
 * Ponto único de acesso aos prompts do app. O texto padrão vive em [PromptCatalog]; o usuário pode
 * sobrescrever qualquer prompt em Configurações > Prompts (guardado em `filesDir/prompts/<id>.md`).
 * Sem [init] (ex.: testes JVM) valem sempre os padrões.
 */
object Prompts {
    @Volatile private var dir: File? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(filesDir: File) {
        dir = File(filesDir, "prompts").apply { mkdirs() }
        cache.clear()
    }

    /** Texto atual do prompt (personalizado ou padrão). */
    fun text(id: String): String {
        val def = PromptCatalog.byId(id) ?: error("Prompt desconhecido: $id")
        val d = dir ?: return def.default
        return cache.getOrPut(id) { File(d, "$id.md").takeIf { it.isFile }?.readText() ?: def.default }
    }

    fun render(id: String, vars: Map<String, Any?> = emptyMap(), flags: Map<String, Boolean> = emptyMap()): String =
        PromptTemplate.render(text(id), vars.mapValues { it.value?.toString().orEmpty() }, flags)

    fun isCustomized(id: String): Boolean = dir?.let { File(it, "$id.md").isFile } == true

    fun save(id: String, text: String) {
        val d = dir ?: return
        val def = PromptCatalog.byId(id) ?: return
        if (text == def.default) return reset(id)
        File(d, "$id.md").writeText(text)
        cache[id] = text
    }

    fun reset(id: String) {
        dir?.let { File(it, "$id.md").delete() }
        cache.remove(id)
    }
}
