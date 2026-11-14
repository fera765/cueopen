package dev.cueopen.app.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyntaxCheckTest {
    @Test fun `json and jsonl are validated and other files are ignored`() {
        assertNull(SyntaxCheck.problem("a.json", """{"a": [1, 2]}"""))
        assertNotNull(SyntaxCheck.problem("a.json", """{"a": [1, 2}"""))
        assertNull(SyntaxCheck.problem("a.jsonl", "{\"a\":1}\n\n{\"b\":2}"))
        assertNotNull(SyntaxCheck.problem("a.jsonl", "{\"a\":1}\n{quebrado"))
        assertNull(SyntaxCheck.problem("notas.md", "{ não é json"))
    }

    @Test fun `only edits that break a previously valid file are flagged`() {
        assertNotNull(SyntaxCheck.brokenBy("x.json", "{\"a\":1}", "{\"a\":1,}"))
        assertNull(SyntaxCheck.brokenBy("x.json", "{quebrado", "{continua quebrado"))
        assertNull(SyntaxCheck.brokenBy("x.json", null, "{\"a\":1}"))
    }
}
