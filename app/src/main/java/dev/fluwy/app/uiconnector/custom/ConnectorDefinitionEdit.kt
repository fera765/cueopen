package dev.fluwy.app.uiconnector.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Redação e round-trip fail-closed para edição por IA de definições customizadas. */
object ConnectorDefinitionEdit {
    private data class ProtectedValue(val path: List<String>, val token: String, val value: JsonElement)
    private val sensitiveKey = Regex("(?i)(secret|token|password|passwd|credential|api.?key|client.?secret|access.?token|refresh.?token|senha|chave|segredo|auth|cookie)")
    private val lockToken = Regex("__FLUWY_LOCK_[0-9a-f]{24}__")
    private val explicitSecret = Regex("(?i)(?:\\bsk-(?:proj-)?[A-Za-z0-9_-]{16,}|\\bgh[pousr]_[A-Za-z0-9]{20,}|\\bxox[baprs]-[A-Za-z0-9-]{20,}|\\bAKIA[A-Z0-9]{16}\\b|\\beyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,})")
    private val highEntropy = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{40,}(?![A-Za-z0-9])")
    private val secretReference = Regex("(?i)\\{\\{\\s*secret:")
    private val lockedRoots = setOf("login", "headers", "capabilities", "setup")

    /** Valor que aparenta chave/token/segredo ou referência ao cofre; não pode ser gerado pela IA. */
    fun containsSecretLikeValue(value: String): Boolean =
        explicitSecret.containsMatchIn(value) || highEntropy.containsMatchIn(value) || secretReference.containsMatchIn(value)

    /** Devolve YAML legível para a IA, substituindo campos protegidos por marcadores opacos estáveis. */
    fun sanitize(rawYaml: String, sessionId: String): String {
        val root = parse(rawYaml, "A definição atual não pode ser sanitizada com segurança.")
        val protected = LinkedHashMap<String, ProtectedValue>()
        return ConnectorYamlCodec.stringify(redact(root, emptyList(), null, sessionId, protected))
    }

    /** Reconstitui campos protegidos somente se permanecerem exatamente nos mesmos caminhos. */
    fun restore(originalYaml: String, proposedYaml: String, sessionId: String): String {
        val original = parse(originalYaml, "A definição atual não pode ser sanitizada com segurança.")
        val protected = LinkedHashMap<String, ProtectedValue>()
        redact(original, emptyList(), null, sessionId, protected)
        val candidate = parse(proposedYaml, "A definição proposta não é YAML/JSON válido.")
        val knownPaths = protected.values.map { pathKey(it.path) }.toSet()
        validateCandidate(candidate, emptyList(), null, knownPaths, protected.keys)

        var restored = candidate
        protected.values.forEach { item ->
            val atPath = getAt(restored, item.path)
            if (atPath != JsonPrimitive(item.token) || count(restored, item.token) != 1)
                throw IllegalArgumentException("A proposta removeu ou moveu um campo protegido. Preserve os marcadores de segurança sem alterá-los.")
            restored = replaceAt(restored, item.path, item.value)
        }
        if (restored !is JsonObject) throw IllegalArgumentException("A raiz da definição precisa continuar sendo um mapa.")
        val yaml = ConnectorYamlCodec.stringify(restored)
        if (yaml.length > CustomDefParser.MAX_CHARS) throw IllegalArgumentException("A definição atualizada excede 64 KB.")
        return yaml
    }

    private fun parse(text: String, message: String): JsonElement = try {
        ConnectorYamlCodec.parse(CustomDefParser.stripFences(text))
    } catch (_: Throwable) {
        throw IllegalArgumentException(message)
    }

    private fun redact(
        element: JsonElement,
        path: List<String>,
        key: String?,
        sessionId: String,
        protected: MutableMap<String, ProtectedValue>,
    ): JsonElement {
        val rootKey = if (path.size == 1) key?.lowercase() else null
        val protect = (rootKey != null && rootKey in lockedRoots) || key?.lowercase() == "headers" ||
            (key != null && sensitiveKey.containsMatchIn(key)) || isSensitiveSetting(path, key, element) ||
            ((element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::containsSecretLikeValue) == true)
        if (protect) {
            val token = token(sessionId, path)
            protected[token] = ProtectedValue(path, token, element)
            return JsonPrimitive(token)
        }
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (childKey, child) ->
                redact(child, path + childKey, childKey, sessionId, protected)
            })
            is JsonArray -> JsonArray(element.mapIndexed { index, child ->
                redact(child, path + index.toString(), key, sessionId, protected)
            })
            else -> element
        }
    }

    private fun isSensitiveSetting(path: List<String>, key: String?, element: JsonElement): Boolean {
        if (key != null && key.lowercase() != "settings") return false
        if (path.lastOrNull()?.toIntOrNull() == null || path.getOrNull(path.size - 2) != "settings") return false
        val obj = element as? JsonObject ?: return false
        val descriptor = listOf("id", "label", "description").mapNotNull { (obj[it] as? JsonPrimitive)?.content }.joinToString(" ")
        return sensitiveKey.containsMatchIn(descriptor)
    }

    private fun validateCandidate(
        element: JsonElement,
        path: List<String>,
        key: String?,
        knownPaths: Set<String>,
        tokens: Set<String>,
    ) {
        val here = pathKey(path)
        val rootKey = if (path.size == 1) key?.lowercase() else null
        val locked = (rootKey != null && rootKey in lockedRoots) || key?.lowercase() == "headers" ||
            (key != null && sensitiveKey.containsMatchIn(key)) || isSensitiveSetting(path, key, element)
        if (locked && here !in knownPaths)
            throw IllegalArgumentException("A proposta adicionou um campo de credencial, permissão ou código bloqueado.")
        when (element) {
            is JsonObject -> element.forEach { (childKey, child) ->
                validateCandidate(child, path + childKey, childKey, knownPaths, tokens)
            }
            is JsonArray -> element.forEachIndexed { index, child ->
                validateCandidate(child, path + index.toString(), key, knownPaths, tokens)
            }
            is JsonPrimitive -> if (element.isString) {
                val text = element.content
                val markers = lockToken.findAll(text).map { it.value }.toList()
                if (markers.any { it !in tokens })
                    throw IllegalArgumentException("A proposta contém um marcador de proteção desconhecido.")
                if (markers.isEmpty() && containsSecretLikeValue(text))
                    throw IllegalArgumentException("A proposta parece conter uma chave, token ou outro segredo gerado. Remova-o e deixe credenciais para o formulário seguro.")
            }
        }
    }

    private fun token(sessionId: String, path: List<String>): String {
        val material = sessionId + "|" + pathKey(path)
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "__FLUWY_LOCK_${hash.take(24)}__"
    }

    private fun pathKey(path: List<String>): String = path.joinToString("/") { it.replace("~", "~0").replace("/", "~1") }

    private fun getAt(root: JsonElement, path: List<String>): JsonElement? {
        var current: JsonElement = root
        for (part in path) current = when (current) {
            is JsonObject -> current[part] ?: return null
            is JsonArray -> part.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
            else -> return null
        }
        return current
    }

    private fun replaceAt(root: JsonElement, path: List<String>, replacement: JsonElement): JsonElement {
        if (path.isEmpty()) return replacement
        val head = path.first()
        val tail = path.drop(1)
        return when (root) {
            is JsonObject -> {
                val child = root[head] ?: throw IllegalArgumentException("A estrutura da proposta mudou perto de um campo protegido.")
                JsonObject(root.toMutableMap().apply { put(head, replaceAt(child, tail, replacement)) })
            }
            is JsonArray -> {
                val index = head.toIntOrNull() ?: throw IllegalArgumentException("A estrutura da proposta mudou perto de um campo protegido.")
                if (index !in root.indices) throw IllegalArgumentException("A estrutura da proposta mudou perto de um campo protegido.")
                JsonArray(root.toMutableList().apply { set(index, replaceAt(this[index], tail, replacement)) })
            }
            else -> throw IllegalArgumentException("A estrutura da proposta mudou perto de um campo protegido.")
        }
    }

    private fun count(root: JsonElement, token: String): Int = when (root) {
        is JsonObject -> root.values.sumOf { count(it, token) }
        is JsonArray -> root.sumOf { count(it, token) }
        is JsonPrimitive -> if (root.isString && root.content == token) 1 else 0
    }
}
