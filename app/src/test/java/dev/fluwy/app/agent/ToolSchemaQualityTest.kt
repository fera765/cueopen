package dev.fluwy.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Padrão de qualidade das ferramentas do agente (guia da Anthropic para ferramentas):
 * nomes em snake_case, descrição que explica quando usar, e todo parâmetro descrito.
 */
class ToolSchemaQualityTest {
    private val src = File("src/main/java/dev/fluwy/app/agent/AgentToolSchemas.kt").readText()
    private val toolRe = Regex("""tool\(\s*"([^"]+)",\s*"((?:[^"\\]|\\.)*)"""")

    @Test fun namesAreSnakeCase() {
        val names = toolRe.findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("nenhuma ferramenta encontrada", names.size > 20)
        val bad = names.filterNot { it.matches(Regex("[a-z][a-z0-9_]*")) }
        assertTrue("nomes fora de snake_case: $bad", bad.isEmpty())
    }

    @Test fun descriptionsExplainWhenToUse() {
        val short = toolRe.findAll(src).filter { it.groupValues[2].length < 40 }.map { it.groupValues[1] }.toList()
        assertTrue("descrições curtas demais (explique o que faz e quando usar): $short", short.isEmpty())
    }

    @Test fun everyParameterHasADescription() {
        val empty = Regex("""put\("([a-z_0-9]+)",\s*(?:str|int|bool|num)\(""\)""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("parâmetros sem descrição: $empty", empty.isEmpty())
    }
}
