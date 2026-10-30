package dev.cueopen.app.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidAssistantManifestContractTest {
    @Test
    fun `VoiceInteractionService declara reconhecimento sessao e suporte ao Assist API`() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val voiceService = services(manifest).firstOrNull {
            it.android("name") == ".assistant.FluwyVoiceInteractionService"
        }
        assertNotNull("VoiceInteractionService precisa estar no manifesto", voiceService)
        requireNotNull(voiceService)

        assertEquals("true", voiceService.android("exported"))
        assertEquals(
            "android.permission.BIND_VOICE_INTERACTION",
            voiceService.android("permission"),
        )
        assertTrue(
            voiceService.getElementsByTagName("action").let { actions ->
                (0 until actions.length).any {
                    (actions.item(it) as Element).android("name") ==
                        "android.service.voice.VoiceInteractionService"
                }
            },
        )

        val voiceMetadata = parse("src/main/res/xml/fluwy_voice_interaction.xml").documentElement
        assertEquals(
            "dev.cueopen.app.assistant.FluwyVoiceInteractionSessionService",
            voiceMetadata.android("sessionService"),
        )
        assertEquals(
            "dev.cueopen.app.assistant.FluwyRecognitionService",
            voiceMetadata.android("recognitionService"),
        )
        assertEquals("true", voiceMetadata.android("supportsAssist"))
    }

    @Test
    fun `servico de reconhecimento esta publicado com a metadata android speech`() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val recognitionService = services(manifest).firstOrNull {
            it.android("name") == ".assistant.FluwyRecognitionService"
        }
        assertNotNull("RecognitionService precisa estar no manifesto", recognitionService)
        requireNotNull(recognitionService)

        assertEquals("true", recognitionService.android("exported"))
        assertTrue(
            recognitionService.getElementsByTagName("action").let { actions ->
                (0 until actions.length).any {
                    (actions.item(it) as Element).android("name") == "android.speech.RecognitionService"
                }
            },
        )
        assertTrue(
            recognitionService.getElementsByTagName("meta-data").let { metadata ->
                (0 until metadata.length).any {
                    val element = metadata.item(it) as Element
                    element.android("name") == "android.speech" &&
                        element.android("resource") == "@xml/fluwy_recognition_service"
                }
            },
        )
        assertEquals(
            "recognition-service",
            parse("src/main/res/xml/fluwy_recognition_service.xml").documentElement.tagName,
        )
    }

    private fun services(manifest: org.w3c.dom.Document): List<Element> {
        val nodes = manifest.getElementsByTagName("service")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun parse(relativePath: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(findSourceFile(relativePath))

    private fun findSourceFile(relativePath: String): File = listOf(
        File(relativePath),
        File("app/$relativePath"),
        File("../app/$relativePath"),
    ).firstOrNull(File::isFile)
        ?: error("Arquivo-fonte não encontrado: $relativePath")

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
