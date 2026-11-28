package dev.fluwy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubSkillsTest {
    @Test fun `parses the supported repository address shapes`() {
        assertEquals(GithubSkills.Ref("anthropics", "skills", null, ""), GithubSkills.parse("anthropics/skills"))
        assertEquals(GithubSkills.Ref("anthropics", "skills", null, ""), GithubSkills.parse(" https://github.com/anthropics/skills/ "))
        assertEquals(GithubSkills.Ref("a", "b", null, ""), GithubSkills.parse("github.com/a/b.git"))
        assertEquals(GithubSkills.Ref("a", "b", "main", "skills/pdf"), GithubSkills.parse("https://github.com/a/b/tree/main/skills/pdf"))
        assertEquals(GithubSkills.Ref("a", "b", "dev", "x/SKILL.md"), GithubSkills.parse("https://www.github.com/a/b/blob/dev/x/SKILL.md?plain=1"))
    }

    @Test fun `rejects other hosts malformed names and traversal`() {
        assertNull(GithubSkills.parse("https://gitlab.com/a/b"))
        assertNull(GithubSkills.parse("so-um-nome"))
        assertNull(GithubSkills.parse("gitlab.com/a"))
        assertNull(GithubSkills.parse("a/b/issues/3"))
        assertNull(GithubSkills.parse("a b/c"))
        assertNull(GithubSkills.parse("https://github.com/a/b/tree/main/../../etc"))
        assertNull(GithubSkills.parse("ftp://github.com/a/b"))
    }

    @Test fun `finds SKILL md files under the requested folder only`() {
        val all = listOf("README.md", "skills/pdf/SKILL.md", "skills/pdf/extra.md", "skills/xlsx/skill.md", "other/SKILL.md", "SKILL.md", "skills-old/SKILL.md")
        assertEquals(listOf("SKILL.md", "other/SKILL.md", "skills-old/SKILL.md", "skills/pdf/SKILL.md", "skills/xlsx/skill.md"), GithubSkills.skillPaths(all, ""))
        assertEquals(listOf("skills/pdf/SKILL.md", "skills/xlsx/skill.md"), GithubSkills.skillPaths(all, "skills/"))
        assertEquals(listOf("skills/pdf/SKILL.md"), GithubSkills.skillPaths(all, "skills/pdf/SKILL.md"))
    }

    @Test fun `title falls back to the folder and frontmatter is read`() {
        assertEquals("pdf", GithubSkills.fallbackTitle("skills/pdf/SKILL.md", "repo"))
        assertEquals("repo", GithubSkills.fallbackTitle("SKILL.md", "repo"))
        val md = "---\nname: \"PDF Tools\"\ndescription: Lê PDFs\n---\n# corpo"
        assertEquals("PDF Tools", GithubSkills.frontmatter(md, "name"))
        assertEquals("Lê PDFs", GithubSkills.frontmatter(md, "description"))
        assertEquals("", GithubSkills.frontmatter("# sem frontmatter", "name"))
    }
}
