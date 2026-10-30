package dev.cueopen.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.QuestionSpec
import dev.cueopen.app.data.QuestionOption
import dev.cueopen.app.data.SecureField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAndSecretsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // -------------------------------------------------- cofre seguro
    @Test fun secretsResolveOnlyAtExecutionAndAreRedacted() {
        val sid = "t-" + System.nanoTime()
        SecretVault.put(sid, mapOf("api_key" to "sk_live_ABC123xyz", "senha" to "p@ss w0rd!"))
        assertEquals(listOf("api_key", "senha"), SecretVault.names(sid))
        val cmd = SecretVault.resolve(sid, "curl -H 'Authorization: Bearer {{secret:api_key}}' https://x")
        assertTrue(cmd.contains("sk_live_ABC123xyz"))
        // eco cru, URL-encoded e base64 nunca voltam ao modelo
        val out = SecretVault.redact(sid, "token=sk_live_ABC123xyz pass=p%40ss+w0rd%21 b64=" + java.util.Base64.getEncoder().encodeToString("sk_live_ABC123xyz".toByteArray()))
        assertFalse(out, out.contains("sk_live_ABC123xyz"))
        assertFalse(out, out.contains("p%40ss"))
        assertTrue(out, out.contains("‹secret:api_key›") && out.contains("‹secret:senha›"))
        SecretVault.clear(sid)
        assertTrue(SecretVault.names(sid).isEmpty())
    }

    @Test fun missingAndUnknownSecretsStayAsMarkers() {
        val sid = "t-" + System.nanoTime()
        SecretVault.put(sid, mapOf("token" to "abcdef"))
        assertEquals(listOf("email"), SecretVault.missing(sid, "{{secret:token}} {{secret:email}}"))
        assertEquals("x {{secret:email}}", SecretVault.resolve(sid, "x {{secret:email}}"))
        assertEquals("nada", SecretVault.redact("outra-sessao", "nada"))
        // outra tarefa não enxerga os segredos desta
        assertEquals("{{secret:token}}", SecretVault.resolve("outra-sessao", "{{secret:token}}"))
    }

    @Test fun secureMessageNeverContainsValues() {
        val m = AskTools.secureSubmittedMessage(listOf("api_key", "email"))
        assertTrue(m.contains("{{secret:api_key}}") && m.contains("email"))
    }

    // -------------------------------------------------- ask_question
    @Test fun questionsAreValidated() {
        val (qs, err) = AskTools.parseQuestions(obj("""{"questions":[
            {"question":"Qual estilo você prefere?","header":"Estilo","options":[{"label":"Minimalista"},{"label":"Colorido","description":"gradientes"}],"multi_select":true},
            {"question":"Quantos slides?","options":["5","10","15"]}]}"""))
        assertNull(err); assertEquals(2, qs.size)
        assertTrue(qs[0].multiSelect); assertEquals("gradientes", qs[0].options[1].description)
        assertEquals(3, qs[1].options.size); assertTrue(qs[1].allowOther)
        assertNotNull(AskTools.parseQuestions(obj("""{"questions":[{"question":"Tem certeza?","options":["Sim"]}]}""")).second)
        assertNotNull(AskTools.parseQuestions(obj("""{"questions":[]}""")).second)
        assertNotNull(AskTools.parseQuestions(obj("""{"questions":[{"question":"a b c d?","options":["1","2"]},{"question":"a b c d?","options":["1","2"]},{"question":"a b c d?","options":["1","2"]},{"question":"a b c d?","options":["1","2"]},{"question":"a b c d?","options":["1","2"]}]}""")).second)
    }

    @Test fun answersMessageMarksSkipped() {
        val (qs, _) = AskTools.parseQuestions(obj("""{"questions":[{"question":"Qual estilo?","header":"Estilo","options":["A","B"]},{"question":"Qual cor?","options":["Azul","Verde"]}]}"""))
        val msg = AskTools.answersMessage(qs, listOf(listOf("A", "minha ideia"), emptyList()))
        assertTrue(msg, msg.contains("Estilo: A, minha ideia"))
        assertTrue(msg, msg.contains("pulou"))
    }

    @Test fun answeredQuestionCardHidesItsLegacyUserBubble() {
        val q = QuestionSpec("Qual tipo de operação?", options = listOf(QuestionOption("Automática"), QuestionOption("Manual")))
        val card = ChatItem.Question(questions = listOf(q), answers = listOf(listOf("Automática")), state = "answered")
        val bubble = ChatItem.User(text = AskTools.answersMessage(card.questions, card.answers))
        val items = AskTools.visibleTimelineItems(listOf(card, bubble))
        assertEquals(listOf(card), items)
    }

    @Test fun submittedSecureCardHidesItsLegacyUserBubble() {
        val card = ChatItem.SecureForm(title = "Acesso", fields = listOf(SecureField("email", "E-mail")), state = "submitted", submitted = listOf("email"))
        val bubble = ChatItem.User(text = AskTools.secureSubmittedMessage(card.submitted))
        val items = AskTools.visibleTimelineItems(listOf(card, bubble))
        assertEquals(listOf(card), items)
    }

    // -------------------------------------------------- ask_secure
    @Test fun secureFieldsAreValidated() {
        val (title, fields, err) = AskTools.parseSecure(obj("""{"title":"Chave da Eduzz","fields":[{"name":"api_key","label":"API key","type":"api_key"},{"name":"email","label":"E-mail","type":"email","required":false}]}"""))
        assertNull(err); assertEquals("Chave da Eduzz", title); assertEquals(2, fields.size); assertFalse(fields[1].required)
        assertNotNull(AskTools.parseSecure(obj("""{"fields":[{"name":"API KEY","label":"x"}]}""")).third)
        assertNotNull(AskTools.parseSecure(obj("""{"fields":[{"name":"a","label":"x","type":"cpf"}]}""")).third)
        assertNotNull(AskTools.parseSecure(obj("""{"fields":[{"name":"a","label":"x"},{"name":"a","label":"y"}]}""")).third)
    }

    // -------------------------------------------------- saída do terminal
    @Test fun progressBarsAndNoiseAreCompacted() {
        val curl = "  % Total\n" + (1..50).joinToString("\r") { "  $it 1202k  $it 16384" } + "\nOK final"
        val c = ShellOutput.compact(curl)
        assertTrue(c, c.contains("50 1202k") && !c.contains("\r") && c.contains("OK final"))
        val find = (1..200).joinToString("\n") { "find: /vendor/lib/lib$it@1.0.so: Permission denied" } + "\nresultado: achei"
        val f = ShellOutput.compact(find)
        assertTrue(f, f.lines().size < 10 && f.contains("omitidas") && f.contains("resultado: achei"))
    }
}
