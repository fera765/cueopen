package dev.cueopen.app.uiconnector.custom

import android.content.Context
import dev.cueopen.app.diag.ErrorLog
import java.io.File

/** Guarda as definições JSON dos conectores do usuário em filesDir/uic_custom/<id>.json. */
class CustomConnectorStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "uic_custom").apply { mkdirs() }

    private fun file(id: String) = File(dir, "$id.json")

    fun rawJson(id: String): String? = runCatching { file(id).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Definições válidas gravadas (inválidas/corrompidas são ignoradas e registradas no log de erros). */
    fun loadAll(): List<CustomDef> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            val parsed = runCatching { CustomDefParser.parse(f.readText()) }.getOrNull()
            val def = parsed?.def
            if (def == null || def.id != f.nameWithoutExtension) {
                runCatching { ErrorLog.log("uiconnector", "conector personalizado ignorado (${f.name}): ${parsed?.errors?.firstOrNull() ?: "ilegível"}") }
                null
            } else def
        }

    /** Valida e grava. Recusa ids de conectores que vêm com o app. */
    fun save(jsonText: String, reservedIds: Set<String>): Result<CustomDef> {
        val parsed = CustomDefParser.parse(jsonText)
        val def = parsed.def ?: return Result.failure(IllegalArgumentException(parsed.errors.joinToString("\n")))
        if (def.id in reservedIds) return Result.failure(IllegalArgumentException("O id '${def.id}' é de um conector do app; escolha outro."))
        return runCatching {
            val tmp = File(dir, "${def.id}.json.tmp")
            tmp.writeText(jsonText)
            if (!tmp.renameTo(file(def.id))) { file(def.id).writeText(jsonText); tmp.delete() }
            def
        }
    }

    fun delete(id: String) {
        runCatching { file(id).delete() }
    }
}
