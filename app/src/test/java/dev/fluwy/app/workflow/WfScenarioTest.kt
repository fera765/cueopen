package dev.fluwy.app.workflow

import dev.fluwy.app.ui.GfmPrep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dezenas de cenários de fluxo: do mais simples ao mais complexo, estrutura + conteúdo enviado. */
class WfScenarioTest {
    private fun n(id: String, type: String, vararg cfg: Pair<String, String>) = WfNode(id = id, type = type, config = mapOf(*cfg))
    private fun e(a: String, b: String, port: String = "main") = WfEdge(from = a, to = b, port = port)
    private fun wf(nodes: List<WfNode>, vararg edges: WfEdge) = Workflow(name = "T", nodes = nodes, edges = edges.toList())
    private fun ok(w: Workflow) = WfLogic.problems(w).filterNot { it.contains("não está ligado a nada") }.also { assertTrue(it.joinToString(), it.isEmpty()) }
    private fun bad(w: Workflow, part: String) = WfLogic.problems(w).also { p -> assertTrue("esperava \"$part\" em $p", p.any { it.contains(part, true) }) }
    private fun mail(rec: String, subject: String = "Resumo", body: String = "{{a}}") =
        n("g", "connector", "source" to "hub", "conector" to "gmail", "acao" to "send_email", "campos" to "{\"receiver\": [\"$rec\"], \"subject\": \"$subject\", \"body_text\": \"$body\"}")
    private val trig = n("t", "trigger_manual")
    private val ai = n("a", "ai", "prompt" to "Resuma {{prev}}")

    // ── estrutura ──
    @Test fun s01_vazio() { bad(wf(emptyList()), "vazio") }
    @Test fun s02_semGatilho() { bad(wf(listOf(ai)), "gatilho") }
    @Test fun s03_manualNotify() { ok(wf(listOf(trig, n("m", "notify", "text" to "Oi")), e("t", "m"))) }
    @Test fun s04_notifySemTexto() { bad(wf(listOf(trig, n("m", "notify")), e("t", "m")), "texto da notificação") }
    @Test fun s05_agendaIntervalo() { ok(wf(listOf(n("t", "trigger_schedule", "mode" to "interval", "minutes" to "5"), n("m", "notify", "text" to "x")), e("t", "m"))) }
    @Test fun s06_agendaIntervaloZero() { bad(wf(listOf(n("t", "trigger_schedule", "mode" to "interval", "minutes" to "0"), n("m", "notify", "text" to "x")), e("t", "m")), "mínimo") }
    @Test fun s07_agendaHoraInvalida() { bad(wf(listOf(n("t", "trigger_schedule", "mode" to "daily", "time" to "25:99"), n("m", "notify", "text" to "x")), e("t", "m")), "horário") }
    @Test fun s08_ligacaoQuebrada() { bad(wf(listOf(trig), e("t", "zz")), "não existe") }
    @Test fun s09_entradaNoGatilho() { bad(wf(listOf(trig, ai), e("a", "t")), "gatilho não pode") }
    @Test fun s10_tokenInexistente() { bad(wf(listOf(trig, n("m", "notify", "text" to "{{nada.x}}")), e("t", "m")), "não aponta") }
    @Test fun s11_tokenProprio() { bad(wf(listOf(trig, n("m", "notify", "text" to "{{m}}")), e("t", "m")), "próprio nó") }
    @Test fun s12_aiSemPrompt() { bad(wf(listOf(trig, n("a", "ai")), e("t", "a")), "Instrução") }
    @Test fun s13_cadeiaAiNotify() { ok(wf(listOf(trig, ai, n("m", "notify", "text" to "{{a}}")), e("t", "a"), e("a", "m"))) }
    @Test fun s14_jsonInvalidoConector() {
        bad(wf(listOf(trig, n("g", "connector", "conector" to "gmail", "acao" to "x", "campos" to "{oops")), e("t", "g")), "JSON")
    }

    // ── e-mail ──
    @Test fun s15_emailReal() { ok(wf(listOf(trig, ai, mail("ana@empresa.com.br")), e("t", "a"), e("a", "g"))) }
    @Test fun s16_emailExemplo() { bad(wf(listOf(trig, ai, mail("email@exemplo.com")), e("t", "a"), e("a", "g")), "ask_question") }
    @Test fun s17_emailXY() { bad(wf(listOf(trig, ai, mail("x@y.com")), e("t", "a"), e("a", "g")), "não é um e-mail real") }
    @Test fun s18_emailVazio() {
        val g = n("g", "connector", "conector" to "gmail", "acao" to "send_email", "campos" to "{\"subject\": \"s\", \"body_text\": \"b\"}")
        bad(wf(listOf(trig, g), e("t", "g")), "falta o e-mail de destino")
    }
    @Test fun s19_emailTemplateDestino() { ok(wf(listOf(trig, ai, mail("{{a | email}}")), e("t", "a"), e("a", "g"))) }
    @Test fun s20_emailSemAssunto() { bad(wf(listOf(trig, ai, mail("ana@empresa.com", subject = "")), e("t", "a"), e("a", "g")), "assunto") }
    @Test fun s21_emailSemCorpo() { bad(wf(listOf(trig, ai, mail("ana@empresa.com", body = "")), e("t", "a"), e("a", "g")), "corpo") }
    @Test fun s22_emailVarios() {
        assertEquals(listOf("a@b.com", "c@d.org"), WfQuality.recipients("{\"receiver\": [\"a@b.com\", \"c@d.org\"]}"))
        assertEquals(listOf("a@b.com", "c@d.org"), WfQuality.recipients("{\"to\": \"a@b.com; c@d.org\"}"))
    }
    @Test fun s23_emailRuntime() {
        fun j(s: String) = Json.parseToJsonElement(s) as JsonObject
        assertNull(WfQuality.emailProblem(j("{\"receiver\":[\"ana@empresa.com\"],\"subject\":\"Oi\",\"body_text\":\"Olá Ana\"}")))
        assertNotNull(WfQuality.emailProblem(j("{\"receiver\":[\"\"],\"subject\":\"Oi\",\"body_text\":\"x\"}")))
        assertNotNull(WfQuality.emailProblem(j("{\"receiver\":[\"ana@empresa.com\"],\"subject\":\"Oi\",\"body_text\":\"\"}")))
        assertNotNull(WfQuality.emailProblem(j("{\"receiver\":[\"ana@empresa.com\"],\"subject\":\"Oi\",\"body_text\":\"{\\\"a\\\":1}\"}")))
        assertNotNull(WfQuality.emailProblem(j("{\"receiver\":[\"ana@empresa.com\"],\"subject\":\"{{x.y}}\",\"body_text\":\"ok\"}")))
        assertNotNull(WfQuality.emailProblem(j("{\"receiver\":[\"teste@exemplo.com\"],\"subject\":\"Oi\",\"body_text\":\"ok\"}")))
    }

    // ── conteúdo de modal/página/chat ──
    @Test fun s24_conteudoOk() { assertNull(WfQuality.contentProblem("X", "**Oi**\n\n| a | b |\n|---|---|\n| 1 | 2 |")) }
    @Test fun s25_conteudoVazio() { assertNotNull(WfQuality.contentProblem("X", "  ")) }
    @Test fun s26_conteudoToken() { assertNotNull(WfQuality.contentProblem("X", "Olá {{g.nome}}")) }
    @Test fun s27_conteudoJson() { assertNotNull(WfQuality.contentProblem("X", "{\"a\": 1}")) }
    @Test fun s28_conteudoNull() { assertNotNull(WfQuality.contentProblem("X", "null")) }
    @Test fun s29_conteudoErro() { assertNotNull(WfQuality.contentProblem("X", "ERRO: timeout")) }
    @Test fun s30_jsonPermitido() { assertNull(WfQuality.contentProblem("X", "{\"a\": 1}", allowJson = true)) }

    // ── webhook ──
    private val hook = n("t", "trigger_webhook", "repo" to "fulano/hooks", "issue" to "3", "secret" to "s3gredo-forte-123")
    @Test fun s31_webhookOk() { ok(wf(listOf(hook, n("m", "notify", "text" to "{{t.payload.pedido}}")), e("t", "m"))) }
    @Test fun s32_webhookSemRepo() { bad(wf(listOf(n("t", "trigger_webhook", "issue" to "3", "secret" to "s3gredo-forte-123"), n("m", "notify", "text" to "x")), e("t", "m")), "Repositório") }
    @Test fun s33_webhookSegredoCurto() { bad(wf(listOf(n("t", "trigger_webhook", "repo" to "a/b", "issue" to "1", "secret" to "curto"), n("m", "notify", "text" to "x")), e("t", "m")), "12 caracteres") }
    @Test fun s34_webhookParse() {
        val json = """[{"id":10,"body":"s3gredo-forte-123\n{\"pedido\":1}"},{"id":11,"body":"errado\n{}"},{"id":12,"body":"s3gredo-forte-123\n{\"pedido\":2}"},{"id":9,"body":"s3gredo-forte-123\nvelho"}]"""
        val h = WfQuality.parseHooks(json, "s3gredo-forte-123", afterId = 9)
        assertEquals(listOf(10L, 12L), h.map { it.id })
        assertEquals("{\"pedido\":1}", h[0].payload)
        assertEquals(12L, WfQuality.newestId(json))
    }
    @Test fun s35_webhookSemSegredoNaoDispara() { assertTrue(WfQuality.parseHooks("""[{"id":5,"body":"abc"}]""", "s3gredo-forte-123", 0).isEmpty()) }
    @Test fun s36_webhookSegredoNoMeioNaoVale() { assertTrue(WfQuality.parseHooks("""[{"id":5,"body":"oi s3gredo-forte-123"}]""", "s3gredo-forte-123", 0).isEmpty()) }
    @Test fun s37_webhookJsonRuim() { assertTrue(WfQuality.parseHooks("not json", "s3gredo-forte-123", 0).isEmpty()) }

    // ── complexos ──
    @Test fun s38_emailImportanteComRevisao() {
        val t = n("t", "trigger_schedule", "mode" to "interval", "minutes" to "15")
        val g1 = n("g1", "connector", "conector" to "gmail", "acao" to "gmail_search_email", "campos" to "{\"max_results\":1}")
        val nw = n("nw", "if_new", "key" to "{{g1.data.results.messages.0.id}}")
        val d = n("d", "ai_decide", "criteria" to "Precisa de resposta?", "input" to "{{g1}}")
        val a1 = n("a1", "ai", "prompt" to "Responda {{g1.data.results.messages.0 | text}}")
        val r1 = n("r1", "review", "item" to "{{g1}}", "draft" to "{{a1}}")
        val s = n("s", "connector", "conector" to "gmail", "acao" to "send_email", "campos" to "{\"receiver\":[\"{{g1.data.results.messages.0.from | email}}\"],\"subject\":\"Re: {{g1.data.results.messages.0.subject}}\",\"body_text\":\"{{r1.draft}}\"}")
        val m = n("m", "notify", "text" to "Resposta enviada", "open" to "chat")
        ok(wf(listOf(t, g1, nw, d, a1, r1, s, m), e("t", "g1"), e("g1", "nw"), e("nw", "d", "true"), e("d", "a1", "true"), e("a1", "r1"), e("r1", "s", "true"), e("s", "m")))
    }
    @Test fun s39_webhookIaPagina() {
        ok(wf(listOf(hook, n("a", "ai", "prompt" to "Tabela de {{t.payload}}"), n("m", "notify", "text" to "Novo pedido", "detail" to "{{a}}", "open" to "page", "layout" to "markdown")), e("t", "a"), e("a", "m")))
    }
    @Test fun s40_ramificacaoIf() {
        val h = n("h", "http", "url" to "https://exemplo.org/api", "method" to "GET")
        val c = n("c", "if", "left" to "{{h.status}}", "op" to "==", "right" to "200")
        ok(wf(listOf(trig, h, c, n("ok", "notify", "text" to "No ar"), n("ko", "notify", "text" to "Fora do ar")), e("t", "h"), e("h", "c"), e("c", "ok", "true"), e("c", "ko", "false")))
    }
    @Test fun s41_parseAgendaLivre() {
        assertEquals("interval", WfLogic.parseSchedule("a cada 2 minutos")?.get("mode"))
        assertEquals("daily", WfLogic.parseSchedule("1x por dia às 8h")?.get("mode"))
        assertEquals("weekly", WfLogic.parseSchedule("toda segunda 08:30")?.get("mode"))
    }

    // ── Markdown estilo GitHub ──
    @Test fun s42_gfmTarefas() { assertEquals("- ☐ fazer\n- ☑ feito", GfmPrep.normalize("- [ ] fazer\n- [x] feito")) }
    @Test fun s43_gfmLinkSolto() { assertEquals("veja <https://a.com/x>.", GfmPrep.normalize("veja https://a.com/x.")) }
    @Test fun s44_gfmLinkMarkdownIntacto() { assertEquals("[a](https://a.com)", GfmPrep.normalize("[a](https://a.com)")) }
    @Test fun s45_gfmCodigoIntacto() { val s = "```\n- [ ] x https://a.com\n```"; assertEquals(s, GfmPrep.normalize(s)) }
    @Test fun s46_gfmTabelaIntacta() { val s = "| a | b |\n|---|---|\n| 1 | 2 |"; assertEquals(s, GfmPrep.normalize(s)) }

    // ── teste "mock real": leituras reais, envios simulados ──
    private fun send(type: String, vararg c: Pair<String, String>) = WfQuality.isExternalSend(type, mapOf(*c))
    @Test fun s47_conectorEnvioSimulado() {
        assertTrue(send("connector", "acao" to "send_email")); assertTrue(send("connector", "acao" to "reply_to_email"))
        assertTrue(send("connector", "acao" to "create_row")); assertTrue(send("connector", "acao" to "slack_post_message"))
        assertTrue(send("connector", "acao" to ""))  // na dúvida, simula
    }
    @Test fun s48_conectorLeituraReal() {
        assertTrue(!send("connector", "acao" to "gmail_search_email")); assertTrue(!send("connector", "acao" to "get_rows"))
        assertTrue(!send("connector", "acao" to "list_events")); assertTrue(!send("connector", "acao" to "read_file"))
    }
    @Test fun s49_httpMetodo() {
        assertTrue(!send("http", "method" to "GET")); assertTrue(!send("http")); assertTrue(send("http", "method" to "POST"))
        assertTrue(send("http", "method" to "delete")); assertTrue(send("http", "method" to "PUT"))
    }
    @Test fun s50_shell() {
        assertTrue(send("shell", "command" to "curl -X POST https://h.com -d x")); assertTrue(send("shell", "command" to "git push origin main"))
        assertTrue(send("shell", "command" to "echo oi | sendmail a@b.com"))
        assertTrue(!send("shell", "command" to "curl -s https://api.site.com/dados | jq .")); assertTrue(!send("shell", "command" to "ls -la"))
    }
    @Test fun s51_mcp() { assertTrue(send("mcp", "tool" to "notion_create_page")); assertTrue(!send("mcp", "tool" to "notion_search")) }
    @Test fun s52_outrosNaoSaem() { assertTrue(!send("ai")); assertTrue(!send("notify")); assertTrue(!send("if")) }

    // ── e-mail em HTML ──
    private fun jo(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun str(o: JsonObject, k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content
    @Test fun s53_markdownViraHtml() {
        val h = WfEmail.toHtml("# Título\n\nOlá **Ana**, veja [o site](https://a.com).\n\n- um\n- dois\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n> nota\n\n```\nx<1\n```")
        listOf("<h1", "<strong>Ana</strong>", "<a href=\"https://a.com\"", "<ul", "<li", "<table", "<th", "<td", "<blockquote", "<pre", "x&lt;1").forEach { assertTrue("$it em $h", h.contains(it)) }
    }
    @Test fun s54_escapaHtmlDoTexto() { assertTrue(WfEmail.toHtml("a <script>x</script>").contains("&lt;script&gt;")) }
    @Test fun s55_prepareBodyHtml() {
        val o = WfEmail.prepare(jo("{\"receiver\":[\"a@b.com\"],\"subject\":\"S\",\"body_text\":\"**oi**\"}"), setOf("receiver", "subject", "body_text", "body_html"))
        assertTrue(str(o, "body_html").startsWith("<!DOCTYPE html>")); assertTrue(str(o, "body_html").contains("<strong>oi</strong>")); assertEquals("oi", str(o, "body_text"))
    }
    @Test fun s56_prepareBodyType() {
        val o = WfEmail.prepare(jo("{\"receiver\":[\"a@b.com\"],\"subject\":\"S\",\"body_text\":\"oi\"}"), setOf("receiver", "subject", "body_type", "body"))
        assertEquals("html", str(o, "body_type")); assertTrue(str(o, "body").contains("<p")); assertTrue(!o.containsKey("body_text"))
    }
    @Test fun s57_htmlProntoMantido() {
        val o = WfEmail.prepare(jo("{\"subject\":\"S\",\"body_text\":\"<p>Oi <b>Ana</b></p>\"}"), setOf("body_html", "body_text"))
        assertTrue(str(o, "body_html").contains("<p>Oi <b>Ana</b></p>")); assertEquals("Oi Ana", str(o, "body_text"))
    }
    @Test fun s58_tarefasNoEmail() { assertTrue(WfEmail.toHtml("- [x] feito").contains("☑ feito")) }
}
