package dev.fluwy.app.uiconnector.custom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test fun `parser lê definição YAML em bloco com modelos e tipos`() {
        val parsed = CustomDefParser.parse(vslYaml())
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        assertTrue(parsed.errors.joinToString("\n"), parsed.errors.isEmpty())
        assertEquals("vsltest", parsed.def!!.id)
        assertEquals("integer", parsed.def.tools.single().params.first().type)
    }

    @Test fun `parser exige parâmetros VSL enviados na requisição e contrato completo`() {
        val parsed = CustomDefParser.parse(vslYaml())
        assertNotNull(parsed.def)
        assertTrue(parsed.errors.joinToString("\n"), parsed.errors.isEmpty())

        val broken = CustomDefParser.parse(vslYaml().replace("instructions: \"{{guardrails}}\"", "instructions: persuasão genérica"))
        assertNull(broken.def)
        assertTrue(broken.errors.joinToString("\n"), broken.errors.any { it.contains("guardrailsParam não é enviado") })
    }

    @Test fun `parser aponta erro de sintaxe YAML e rejeita chave duplicada`() {
        val malformed = CustomDefParser.parse("id: teste\nname sem dois-pontos\n")
        assertNull(malformed.def)
        assertTrue(malformed.errors.single(), malformed.errors.single().contains("YAML inválido"))
        assertTrue(malformed.errors.single(), malformed.errors.single().contains("indentação"))

        val duplicate = CustomDefParser.parse("id: teste\nid: outro\nname: Teste\ndescription: d\n")
        assertNull(duplicate.def)
        assertTrue(duplicate.errors.joinToString("\n"), duplicate.errors.any { it.contains("duplicate") || it.contains("already") || it.contains("chave") })

        val alias = CustomDefParser.parse("""
            id: alias
            name: Alias
            description: Teste de alias
            baseUrl: https://api.example.com
            tools: &items []
            panels: *items
        """.trimIndent())
        assertNull(alias.def)
        assertTrue(alias.errors.joinToString("\n"), alias.errors.any { it.contains("YAML inválido") })

        val customTag = CustomDefParser.parse("id: !!com.example.Custom valor\n")
        assertNull(customTag.def)
        assertTrue(customTag.errors.joinToString("\n"), customTag.errors.any { it.contains("YAML inválido") })
    }

    @Test fun `parser aceita JSON legado e impõe limites e raiz em mapa`() {
        val legacy = CustomDefParser.parse(CustomTemplates.RSS.json)
        assertNotNull(legacy.errors.joinToString("\n"), legacy.def)
        assertTrue(CustomDefParser.parse("[]").errors.single().contains("mapa YAML"))
        assertTrue(CustomDefParser.parse("x".repeat(70_000)).errors.single().contains("64 KB"))
    }

    @Test fun `atestado canoniza YAML e JSON legado e invalida alterações ou política antiga`() {
        val yaml = """a: 1
b:
  y: 2
  x: 3
"""
        val reorderedYaml = """b: {x: 3, y: 2}
a: 1
"""
        val legacyJson = """{"b":{"x":3,"y":2},"a":1}"""
        val proof = ConnectorAttestationPolicy.issue(yaml, ConnectorAttestationPolicy.REQUIRED_CHECKS)
        assertTrue(ConnectorAttestationPolicy.isInstallable(reorderedYaml, proof))
        assertTrue(ConnectorAttestationPolicy.isInstallable(legacyJson, proof))
        assertFalse(ConnectorAttestationPolicy.isInstallable("a: 2\nb: {y: 2, x: 3}", proof))
        assertFalse(ConnectorAttestationPolicy.isInstallable(yaml, proof.copy(policyVersion = 0)))
        assertFalse(ConnectorAttestationPolicy.isInstallable(yaml, proof.copy(checks = proof.checks - "mock_requests")))
        assertFalse(ConnectorAttestationPolicy.isInstallable(yaml, null))
    }

    @Test fun `serializador converte documento JSON legado em YAML equivalente`() {
        val oldDocument = ConnectorYamlCodec.parse(CustomTemplates.TOKEN_LOGIN.json)
        val yaml = ConnectorYamlCodec.stringify(oldDocument)
        assertTrue(yaml.contains("id: minhaapi"))
        assertEquals(oldDocument, ConnectorYamlCodec.parse(yaml))
        assertNotNull(CustomDefParser.parse(yaml).def)
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

    @Test fun `dry-run aceita marcador de segredo sem erro de regexp`() = runBlocking {
        val parsed = CustomDefParser.parse(CustomTemplates.RSS.json)
        assertNotNull(parsed.def)
        val result = ConnectorDryRunValidator.validate(
            CustomTemplates.RSS.json,
            parsed.def!!,
            settingsInput = mapOf("credential" to "{{secret:token}}"),
        )
        assertTrue(result.report, result.passed)
    }

    @Test fun `pipeline offline valida geração VSL de 10 e 20 minutos em YAML`() = runBlocking {
        val yaml = vslYaml()
        val parsed = CustomDefParser.parse(yaml)
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        for (minutes in listOf(10, 20)) {
            val args = mapOf("duration" to minutes.toString(), "language" to "Português", "guardrails" to "Sem promessas garantidas.", "forbidden" to "garantido")
            val result = ConnectorDryRunValidator.validate(yaml, parsed.def!!, mapOf("gerar_vsl" to args))
            assertTrue("${minutes}min: ${result.report}", result.passed)
            assertTrue(result.report.contains("saída VSL simulada"))
        }
    }

    @Test fun `pipeline YAML escreve edita e relê arquivo no filesystem virtual`() = runBlocking {
        val yaml = """
            id: filetest
            name: Teste de arquivo
            description: Escreve e relê artefato no workspace virtual
            version: "1.0"
            baseUrl: https://files.example.com
            capabilities: [files]
            tools:
              - name: gravar
                description: Grava o artefato solicitado
                risky: true
                params:
                  - name: path
                    description: Caminho
                  - name: content
                    description: Conteúdo
                file:
                  op: write
                  path: "{{path}}"
                  content: "{{content}}"
              - name: editar
                description: Substitui um trecho do artefato
                risky: true
                params:
                  - name: path
                    description: Caminho
                  - name: old
                    description: Trecho antigo
                  - name: new
                    description: Trecho novo
                file:
                  op: replace
                  path: "{{path}}"
                  oldText: "{{old}}"
                  newText: "{{new}}"
                  allowMultiple: false
              - name: ler
                description: Lê o artefato solicitado
                params:
                  - name: path
                    description: Caminho
                file:
                  op: read
                  path: "{{path}}"
        """.trimIndent()
        val parsed = CustomDefParser.parse(yaml)
        assertNotNull(parsed.errors.joinToString("\n"), parsed.def)
        val args = mapOf(
            "gravar" to mapOf("path" to "outputs/vsl.md", "content" to "Roteiro de teste gerado."),
            "editar" to mapOf("path" to "outputs/vsl.md", "old" to "Roteiro de teste", "new" to "Roteiro atualizado"),
            "ler" to mapOf("path" to "outputs/vsl.md"),
        )
        val result = ConnectorDryRunValidator.validate(yaml, parsed.def!!, args)
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

    private fun vslYaml(): String = """
        id: vsltest
        name: Gerador VSL
        description: Gera roteiro sob contrato
        version: "1.0"
        baseUrl: https://api.example.com
        tools:
          - name: gerar_vsl
            description: Gera roteiro de VSL
            risky: true
            params:
              - name: duration
                description: Duração em minutos
                type: integer
              - name: language
                description: Idioma
                type: string
              - name: guardrails
                description: Instruções
                type: string
              - name: forbidden
                description: Termos proibidos
                type: string
                required: false
            request:
              method: POST
              path: /generate
              body:
                minutes: "{{duration|int}}"
                language: "{{language}}"
                instructions: "{{guardrails}}"
            response: "{{$.script}}"
            outputPolicy:
              type: vsl
              durationParam: duration
              languageParam: language
              guardrailsParam: guardrails
              forbiddenTermsParam: forbidden
              wordsPerMinute: 130
              tolerance: 0.20
              requiredSections:
                - Abertura
                - Problema
                - Solução
                - Oferta
                - Chamada para ação
    """.trimIndent()
}
