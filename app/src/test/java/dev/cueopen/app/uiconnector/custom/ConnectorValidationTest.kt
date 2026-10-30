package dev.cueopen.app.uiconnector.custom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorValidationTest {
    private val policy = VslOutputPolicy(
        durationParam = "duration",
        languageParam = "language",
        guardrailsParam = "guardrails",
        forbiddenTermsParam = "forbidden",
        wordsPerMinute = 130,
        tolerance = 0.20,
        requiredSections = listOf("Abertura", "Problema", "Solução", "Oferta", "Chamada para ação"),
    )

    @Test fun `VSL de 10 e 20 minutos passa por estimativa de palavras idioma e estrutura`() {
        for (minutes in listOf(10, 20)) {
            val args = mapOf("duration" to minutes.toString(), "language" to "Português (Brasil)", "guardrails" to "Sem promessas garantidas.", "forbidden" to "garantido; cura milagrosa")
            val copy = VslOutputGuardrails.sample(minutes, "Português (Brasil)", policy, args)
            val result = VslOutputGuardrails.validate(copy, args, policy)
            assertTrue("${minutes}min: ${result.errors.joinToString()}", result.passed)
            assertTrue(result.wordCount in result.minWords..result.maxWords)
        }
    }

    @Test fun `VSL falha se duração seção idioma ou termo proibido não cumprir`() {
        val args = mapOf("duration" to "10", "language" to "Português", "guardrails" to "Não prometa resultados.", "forbidden" to "garantido")
        val good = VslOutputGuardrails.sample(10, "Português", policy, args)
        assertFalse(VslOutputGuardrails.validate("Roteiro curto.", args, policy).passed)
        assertFalse(VslOutputGuardrails.validate(good.replace("## Problema", "## Contexto"), args, policy).passed)
        val reordered = good.replace("## Problema", "## SECAO_TEMP").replace("## Solução", "## Problema").replace("## SECAO_TEMP", "## Solução")
        assertFalse(VslOutputGuardrails.validate(reordered, args, policy).passed)
        val english = VslOutputGuardrails.sample(10, "English", policy, args + ("language" to "English"))
        assertFalse(VslOutputGuardrails.validate(english, args, policy).passed)
        assertFalse(VslOutputGuardrails.validate(good + " Isso é garantido.", args, policy).passed)
    }

    @Test fun `parser exige parâmetros VSL enviados na requisição e contrato completo`() {
        val parsed = CustomDefParser.parse(vslJson())
        assertNotNull(parsed.def)
        assertTrue(parsed.errors.joinToString("\n"), parsed.errors.isEmpty())

        val broken = CustomDefParser.parse(vslJson().replace("\"instructions\":\"{{guardrails}}\"", "\"instructions\":\"seja persuasivo\""))
        assertNull(broken.def)
        assertTrue(broken.errors.any { it.contains("guardrailsParam não é enviado") })
    }

    @Test fun `parser orienta correção de aspas e vírgulas em JSON inválido`() {
        val parsed = CustomDefParser.parse("""{"id":"teste", "name":"sem vírgula" "version":"1.0"}""")
        assertNull(parsed.def)
        assertTrue(parsed.errors.single(), parsed.errors.single().contains("JSON inválido"))
        assertTrue(parsed.errors.single(), parsed.errors.single().contains("Confira vírgulas e aspas"))
    }

    @Test fun `atestado só libera mesmo JSON e versão de política atual`() {
        val json = """{"a":1,"b":{"y":2,"x":3}}"""
        val proof = ConnectorAttestationPolicy.issue(json, ConnectorAttestationPolicy.REQUIRED_CHECKS)
        assertTrue(ConnectorAttestationPolicy.isInstallable("""{"b":{"x":3,"y":2},"a":1}""", proof))
        assertFalse(ConnectorAttestationPolicy.isInstallable("""{"a":2,"b":{"y":2,"x":3}}""", proof))
        assertFalse(ConnectorAttestationPolicy.isInstallable(json, proof.copy(policyVersion = 0)))
        assertFalse(ConnectorAttestationPolicy.isInstallable(json, proof.copy(checks = proof.checks - "mock_requests")))
        assertFalse(ConnectorAttestationPolicy.isInstallable(json, null))
    }

    @Test fun `dry-run do RSS conclui com fixture sem depender de login`() = runBlocking {
        val parsed = CustomDefParser.parse(CustomTemplates.RSS.json)
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        val result = ConnectorDryRunValidator.validate(CustomTemplates.RSS.json, parsed.def!!)
        assertTrue(result.report, result.passed)
        assertNotNull(result.attestation)
        assertTrue(result.report.contains("mocks locais"))
        assertTrue(result.report.contains("nenhuma chamada externa"))
    }

    @Test fun `pipeline offline valida geração VSL de 10 e 20 minutos`() = runBlocking {
        val parsed = CustomDefParser.parse(vslJson())
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        for (minutes in listOf(10, 20)) {
            val args = mapOf("duration" to minutes.toString(), "language" to "Português", "guardrails" to "Sem promessas garantidas.", "forbidden" to "garantido")
            val result = ConnectorDryRunValidator.validate(vslJson(), parsed.def!!, mapOf("gerar_vsl" to args))
            assertTrue("${minutes}min: ${result.report}", result.passed)
            assertTrue(result.report.contains("saída VSL simulada"))
        }
    }

    @Test fun `pipeline offline escreve substitui e relê arquivo no filesystem virtual`() = runBlocking {
        val json = """{
          "id":"filetest","name":"Teste de arquivo","description":"Escreve e relê artefato no workspace virtual",
          "version":"1.0","baseUrl":"https://files.example.com","capabilities":["files"],
          "tools":[
            {"name":"gravar","description":"Grava o artefato solicitado","risky":true,
             "params":[{"name":"path","description":"Caminho"},{"name":"content","description":"Conteúdo"}],
             "file":{"op":"write","path":"{{path}}","content":"{{content}}"}},
            {"name":"editar","description":"Substitui um trecho do artefato","risky":true,
             "params":[{"name":"path","description":"Caminho"},{"name":"old","description":"Trecho antigo"},{"name":"new","description":"Trecho novo"}],
             "file":{"op":"replace","path":"{{path}}","oldText":"{{old}}","newText":"{{new}}"}},
            {"name":"ler","description":"Lê o artefato solicitado",
             "params":[{"name":"path","description":"Caminho"}],"file":{"op":"read","path":"{{path}}"}}
          ]
        }"""
        val parsed = CustomDefParser.parse(json)
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        val args = mapOf(
            "gravar" to mapOf("path" to "outputs/vsl.md", "content" to "Roteiro de teste gerado."),
            "editar" to mapOf("path" to "outputs/vsl.md", "old" to "Roteiro de teste", "new" to "Roteiro atualizado"),
            "ler" to mapOf("path" to "outputs/vsl.md"),
        )
        val result = ConnectorDryRunValidator.validate(json, parsed.def!!, args)
        assertTrue(result.report, result.passed)
        assertTrue(result.report.contains("filesystem virtual"))
        assertTrue(result.report.contains("substituição verificada"))
    }

    @Test fun `Biblioteca de Anúncios não passa por scraping nem por mock offline`() {
        val base = CustomDefParser.parse(CustomTemplates.RSS.json).def!!
        val scraped = base.copy(id = "meta_ads", name = "Facebook Ads Library", host = "www.facebook.com", baseUrl = "https://www.facebook.com")
        val officialApi = scraped.copy(host = "graph.facebook.com", baseUrl = "https://graph.facebook.com")
        assertTrue(ConnectorDryRunValidator.adsLibraryPolicyFailure(scraped)?.contains("scraping") == true)
        assertTrue(ConnectorDryRunValidator.adsLibraryPolicyFailure(officialApi)?.contains("não consegue comprovar token") == true)
    }

    private fun vslJson(): String = """{
      "id":"vsltest","name":"Gerador VSL","description":"Gera roteiro sob contrato", "version":"1.0",
      "baseUrl":"https://api.example.com",
      "tools":[{
        "name":"gerar_vsl","description":"Gera roteiro de VSL","risky":true,
        "params":[
          {"name":"duration","description":"Duração em minutos","type":"integer"},
          {"name":"language","description":"Idioma","type":"string"},
          {"name":"guardrails","description":"Instruções","type":"string"},
          {"name":"forbidden","description":"Termos proibidos","type":"string","required":false}
        ],
        "request":{"method":"POST","path":"/generate","body":{"minutes":"{{duration|int}}","language":"{{language}}","instructions":"{{guardrails}}"}},
        "response":"{{$.script}}",
        "outputPolicy":{"type":"vsl","durationParam":"duration","languageParam":"language","guardrailsParam":"guardrails","forbiddenTermsParam":"forbidden","wordsPerMinute":130,"tolerance":0.20,"requiredSections":["Abertura","Problema","Solução","Oferta","Chamada para ação"]}
      }]
    }"""
}
