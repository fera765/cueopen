package dev.fluwy.app.device

import java.text.Normalizer

/** A captura ampla via MediaProjection só é apropriada quando o usuário pede a própria captura. */
internal object DeviceScreenshotPolicy {
    private val explicitCapture = Regex(
        "\\b(screenshot|screen\\s*shot|captur(ar|a|e|ando)?\\s+(a\\s+)?tela|captura\\s+de\\s+tela|print\\s+(da\\s+)?tela|take\\s+a\\s+screenshot|capture\\s+(the\\s+)?screen)\\b",
    )

    fun explicitlyRequestsFullScreenCapture(text: String): Boolean {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return explicitCapture.containsMatchIn(normalized)
    }
}
