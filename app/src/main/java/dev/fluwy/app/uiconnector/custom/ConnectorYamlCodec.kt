package dev.fluwy.app.uiconnector.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.math.BigDecimal
import java.util.Date

/** Parser/serializer de documentos YAML de UI Connectors. O modelo interno continua tipado por JsonElement. */
object ConnectorYamlCodec {
    const val MAX_CHARS = 64 * 1024

    private fun loaderOptions() = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 0
        codePointLimit = MAX_CHARS
        nestingDepthLimit = 64
    }

    private fun yaml() = Yaml(SafeConstructor(loaderOptions()))

    /** Lê um documento YAML seguro; aceita JSON legado, que também é sintaxe YAML válida. */
    fun parse(text: String): JsonElement {
        require(text.length <= MAX_CHARS) { "Definição maior que 64 KB." }
        val value = yaml().load<Any?>(text)
        return toJson(value, "$")
    }

    /** Serializa uma árvore JSON como YAML de bloco para migração de arquivos legados. */
    fun stringify(value: JsonElement): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
            indent = 2
            indicatorIndent = 0
            width = 100
            isPrettyFlow = true
            splitLines = false
            isExplicitStart = false
            isExplicitEnd = false
        }
        return Yaml(options).dump(toNative(value)).trimEnd() + "\n"
    }

    private fun toJson(value: Any?, path: String): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            val literal = value.toString()
            require(literal != "NaN" && literal != "Infinity" && literal != "-Infinity") { "$path: número YAML não finito não é permitido." }
            JsonPrimitive(value)
        }
        is List<*> -> JsonArray(value.mapIndexed { i, child -> toJson(child, "$path[$i]") })
        is Map<*, *> -> {
            val fields = LinkedHashMap<String, JsonElement>()
            value.forEach { (key, child) ->
                require(key is String) { "$path: chaves de mapa devem ser texto; encontrado ${key?.javaClass?.simpleName ?: "nulo"}." }
                fields[key] = toJson(child, "$path.$key")
            }
            JsonObject(fields)
        }
        is Date -> throw IllegalArgumentException("$path: datas YAML devem ser colocadas entre aspas para permanecer texto.")
        else -> throw IllegalArgumentException("$path: tipo YAML não suportado (${value.javaClass.simpleName}); use apenas mapas, listas, texto, números, booleanos e null.")
    }

    private fun toNative(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { value.forEach { (k, v) -> put(k, toNative(v)) } }
        is JsonArray -> value.map(::toNative)
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.longOrNull != null -> value.longOrNull
            value.doubleOrNull != null -> BigDecimal(value.content)
            else -> value.content
        }
    }
}
