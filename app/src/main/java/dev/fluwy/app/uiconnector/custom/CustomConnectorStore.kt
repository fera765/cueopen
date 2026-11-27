package dev.fluwy.app.uiconnector.custom

import android.content.Context
import dev.fluwy.app.diag.ErrorLog
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Guarda definições YAML dos conectores do usuário em filesDir/uic_custom/<id>.yaml. */
class CustomConnectorStore private constructor(private val dir: File, @Suppress("UNUSED_PARAMETER") marker: Unit) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "uic_custom"), Unit)

    /** Construtor interno para testes locais de migração/recuperação sem depender de Android Context. */
    internal constructor(directory: File) : this(directory, Unit)

    init { dir.mkdirs() }

    private fun yamlFile(id: String) = File(dir, "$id.yaml")
    private fun ymlFile(id: String) = File(dir, "$id.yml")
    private fun legacyJsonFile(id: String) = File(dir, "$id.json")
    private fun candidates(id: String) = listOf(yamlFile(id), ymlFile(id), legacyJsonFile(id))

    /** Retorna a definição válida de maior precedência; se nenhuma for válida, deixa o texto disponível para correção manual. */
    fun rawYaml(id: String): String? = runCatching {
        val existing = candidates(id).filter { it.isFile }
        for (source in existing) {
            val text = source.readText()
            val def = runCatching { CustomDefParser.parse(text).def }.getOrNull()?.takeIf { it.id == id } ?: continue
            if (source.extension.equals("json", true)) {
                migrateLegacy(source, def)
                val migrated = yamlFile(id)
                if (migrated.isFile && runCatching { CustomDefParser.parse(migrated.readText()).def?.id == id }.getOrDefault(false))
                    return@runCatching migrated.readText()
            }
            return@runCatching text
        }
        existing.firstOrNull()?.readText()
    }.getOrNull()

    /** Definições válidas gravadas; arquivos YAML inválidos não ocultam um JSON legado ainda recuperável. */
    fun loadAll(): List<CustomDef> {
        val candidates = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yaml", "yml", "json") }
            ?: return emptyList()
        return candidates.groupBy { it.nameWithoutExtension }.toSortedMap().mapNotNull { (id, files) ->
            val ordered = files.sortedBy { precedence(it.extension) }
            var selected: Pair<File, CustomDef>? = null
            var firstError: String? = null
            for (source in ordered) {
                val parsed = runCatching { CustomDefParser.parse(source.readText()) }.getOrNull()
                val def = parsed?.def
                if (def != null && def.id == id) {
                    selected = source to def
                    break
                }
                if (firstError == null) firstError = parsed?.errors?.firstOrNull() ?: "o id interno não corresponde ao nome do arquivo"
            }
            val (source, def) = selected ?: run {
                val first = ordered.firstOrNull() ?: return@mapNotNull null
                runCatching { ErrorLog.log("uiconnector", "conector personalizado ignorado (${first.name}): ${firstError ?: "ilegível"}") }
                return@mapNotNull null
            }
            if (source.extension.equals("json", true)) migrateLegacy(source, def)
            def
        }
    }

    /** Valida e grava YAML. JSON legado aceito na importação é normalizado para YAML antes de persistir. */
    fun save(yamlText: String, reservedIds: Set<String>): Result<CustomDef> {
        val parsed = CustomDefParser.parse(yamlText)
        val def = parsed.def ?: return Result.failure(IllegalArgumentException(parsed.errors.joinToString("\n")))
        if (def.id in reservedIds) return Result.failure(IllegalArgumentException("O id '${def.id}' é de um conector do app; escolha outro."))
        return runCatching {
            val stripped = CustomDefParser.stripFences(yamlText).trim()
            val storedText = if (stripped.startsWith("{") || stripped.startsWith("[")) {
                ConnectorYamlCodec.stringify(ConnectorYamlCodec.parse(stripped))
            } else stripped + "\n"
            writeYaml(def.id, storedText)
            legacyJsonFile(def.id).delete()
            ymlFile(def.id).delete()
            def
        }
    }

    private fun migrateLegacy(source: File, def: CustomDef) {
        val target = yamlFile(def.id)
        if (target.isFile) {
            val existing = runCatching { CustomDefParser.parse(target.readText()).def }.getOrNull()
            if (existing?.id == def.id) return // uma definição YAML válida tem precedência sobre a cópia legada
        }
        runCatching {
            val document = ConnectorYamlCodec.parse(source.readText())
            val yaml = ConnectorYamlCodec.stringify(document)
            if (CustomDefParser.parse(yaml).def != def) return@runCatching
            writeYaml(def.id, yaml)
            val verified = target.takeIf { it.isFile }?.readText()?.let { CustomDefParser.parse(it).def }
            if (verified == def) source.delete()
        }
    }

    /** Escreve temporário no mesmo diretório e troca por rename atômico; falha sem truncar o arquivo anterior. */
    private fun writeYaml(id: String, text: String) {
        val target = yamlFile(id)
        val tmp = File.createTempFile("$id-", ".yaml.tmp", dir)
        try {
            tmp.writeText(text)
            val parsed = CustomDefParser.parse(tmp.readText()).def
            if (parsed?.id != id) throw IOException("A definição YAML temporária não passou na validação do id.")
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("O armazenamento não oferece substituição atômica; a definição anterior foi preservada.", e)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun precedence(extension: String): Int = when (extension.lowercase()) {
        "yaml" -> 0
        "yml" -> 1
        else -> 2
    }

    fun delete(id: String) {
        runCatching { yamlFile(id).delete() }
        runCatching { ymlFile(id).delete() }
        runCatching { legacyJsonFile(id).delete() }
    }
}
