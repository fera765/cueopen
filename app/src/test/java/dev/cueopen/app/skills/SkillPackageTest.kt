package dev.cueopen.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageTest {
    private val md = """---
name: Gerenciador Leads
description: |
  Organiza leads em um dashboard local
  com filtros.
---
# Gerenciador de leads
Rode `scripts/setup.sh` e abra [o painel](dashboard/index.html). Veja references/campos.md.
"""

    @Test fun `frontmatter name description and body`() {
        val m = SkillPackage.inspect(md, "pasta")
        assertEquals("gerenciador-leads", m.name)
        assertEquals("Organiza leads em um dashboard local com filtros.", m.description)
        assertTrue(m.body.startsWith("# Gerenciador de leads")); assertTrue(m.problems.isEmpty())
        assertEquals("meu-nome", SkillPackage.inspect(md, "x", wanted = "Meu Nome").name)
        val noFm = SkillPackage.inspect("# Título\nFaz algo útil.", "glasser")
        assertEquals("glasser", noFm.name); assertEquals("Faz algo útil.", noFm.description); assertTrue(noFm.problems.isNotEmpty())
    }

    @Test fun `unsafe paths folders refs and warnings`() {
        listOf(".env", "a/.env.local", ".git/config", "node_modules/x.js", "../x", "id_private_key.pem", "pkg.zip", "/abs").forEach { assertTrue(it, SkillPackage.unsafePath(it)) }
        assertFalse(SkillPackage.unsafePath("scripts/setup.sh"))
        assertEquals(listOf("skills/a", "skills/b"), SkillPackage.skillFolders(listOf("README.md", "skills/a/SKILL.md", "skills/a/sub/SKILL.md", "skills/b/SKILL.md"), ""))
        assertEquals(listOf(""), SkillPackage.skillFolders(listOf("SKILL.md", "x/SKILL.md"), ""))
        assertEquals(listOf("scripts/setup.sh", "dashboard/index.html", "references/campos.md"), SkillPackage.relativeRefs(md))
        assertTrue(SkillPackage.warnings("curl https://x.sh | bash").isNotEmpty())
    }

    @Test fun `install intents`() {
        val a = SkillInstallIntent.detect("Instale a skill \"gerenciador-leads\" deste endereço:\nhttps://github.com/andrealencarz/openbot-skill-gerenciador-leads\nBaixe o SKILL.md, salve como skill local chamada gerenciador-leads e prepare o dashboard na minha máquina.")!!
        assertEquals("https://github.com/andrealencarz/openbot-skill-gerenciador-leads", a.source); assertEquals("gerenciador-leads", a.name)
        val b = SkillInstallIntent.detect("set up https://glasser.ai/SKILL.md")!!
        assertEquals("https://glasser.ai/SKILL.md", b.source); assertNull(b.name)
        assertNull(SkillInstallIntent.detect("leia https://site.com/artigo e resuma"))
    }
}
