package dev.cueopen.app.assistant

import android.text.InputType

/** Limites e filtros puros para contexto entregue explicitamente pelo Android Assist API. */
internal object AssistTextPolicy {
    const val MAX_NODES = 120
    const val MAX_CHARS = 8_000
    private const val MAX_FIELD_CHARS = 500

    data class NodeText(
        val text: String? = null,
        val hint: String? = null,
        val contentDescription: String? = null,
        val inputType: Int = InputType.TYPE_NULL,
    )

    fun isPasswordInput(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun collect(nodes: Iterable<NodeText>): String {
        val lines = linkedSetOf<String>()
        var length = 0
        for (node in nodes.take(MAX_NODES)) {
            if (isPasswordInput(node.inputType)) continue
            for (value in listOfNotNull(node.text, node.hint, node.contentDescription)) {
                val clean = value
                    .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), "")
                    .replace(Regex("[\\n\\t\\r]+"), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                    .take(MAX_FIELD_CHARS)
                if (clean.isBlank() || !lines.add(clean)) continue
                val addedLength = clean.length + if (length == 0) 0 else 1
                if (length + addedLength > MAX_CHARS) {
                    val remaining = (MAX_CHARS - length - if (length == 0) 0 else 1).coerceAtLeast(0)
                    if (remaining > 0) lines.add(clean.take(remaining))
                    return lines.joinToString("\n").take(MAX_CHARS)
                }
                length += addedLength
            }
        }
        return lines.joinToString("\n").take(MAX_CHARS)
    }
}
