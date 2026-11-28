package dev.fluwy.app.agent

import dev.fluwy.app.data.MemoryItem
import org.junit.Assert.*
import org.junit.Test

class MemoryGraphTest {
    private val tom = MemoryItem(id = "a1b2c3d4e5", text = "Tom é o designer do time e cuida do Figma", title = "Tom · design", kind = "pessoa")
    private val app = MemoryItem(id = "b2", text = "O projeto Fluwy é um app Android em Kotlin", tags = listOf("fluwy"))
    private val ui = MemoryItem(id = "c3", text = "Telas do [[Tom · design]] seguem o tema do Fluwy Android", tags = listOf("fluwy"))
    private val all = listOf(tom, app, ui)

    @Test fun wikilinksResolveByTitle() {
        assertEquals(listOf("Tom · design"), MemoryGraph.wikiTargets(ui.text))
        val e = MemoryGraph.edges(all, auto = false)
        assertTrue(e.any { it.explicit && setOf(it.a, it.b) == setOf("a1b2c3d4e5", "c3") })
    }
    @Test fun autoLinksBySharedTag() {
        val e = MemoryGraph.edges(all)
        assertTrue(e.any { !it.explicit && setOf(it.a, it.b) == setOf("b2", "c3") })
    }
    @Test fun explicitLinksAndPrefix() {
        val m = MemoryItem(id = "z9", text = "x", links = listOf("b2"))
        assertTrue(MemoryGraph.edges(all + m, auto = false).any { setOf(it.a, it.b) == setOf("z9", "b2") })
        assertEquals("a1b2c3d4e5", MemoryGraph.resolve("a1b2c3", all))
        assertEquals(listOf("a1b2c3d4e5"), MemoryGraph.linkIds(listOf("[[Tom · design]]", "inexistente-xyz"), all))
    }
    @Test fun danglingLinksIgnored() {
        val m = MemoryItem(id = "z9", text = "x", links = listOf("apagada"))
        assertTrue(MemoryGraph.edges(listOf(m)).isEmpty())
    }
    @Test fun kinds() {
        assertEquals("pessoa", MemoryGraph.kindOf(tom))
        assertEquals("projeto", MemoryGraph.kindOf(app))
        assertEquals("preferencia", MemoryGraph.kindOf(MemoryItem(text = "Prefere respostas curtas")))
        assertEquals("outro", MemoryGraph.kindOf(MemoryItem(text = "xyz abc")))
    }
    @Test fun titleAndPlain() {
        assertEquals("Tom · design", MemoryGraph.titleOf(tom))
        assertEquals("O projeto Fluwy é um…", MemoryGraph.titleOf(app))
        assertFalse(MemoryGraph.plain(ui.text).contains("[["))
    }
    @Test fun expandAddsNeighbors() {
        val out = MemoryGraph.expand(listOf(ui), all, 2)
        assertTrue(out.first() == ui && out.any { it.id == "a1b2c3d4e5" })
    }
    @Test fun layoutBoundedAndStable() {
        val many = (0 until 120).map { MemoryItem(id = "n$it", text = "nota $it tema${it % 5} grupo", tags = listOf("t${it % 7}")) }
        val e = MemoryGraph.edges(many)
        val g = many.associate { it.id to MemoryGraph.kindOf(it) }
        val l1 = MemoryGraph.layout(many.map { it.id }, g, e)
        val l2 = MemoryGraph.layout(many.map { it.id }, g, e)
        assertEquals(120, l1.size)
        assertTrue(l1.values.all { it.x in -1.001f..1.001f && it.y in -1.001f..1.001f && !it.x.isNaN() })
        assertEquals(l1["n5"]!!.x, l2["n5"]!!.x, 0f)
        val big = (0 until 600).map { "m$it" }
        assertEquals(600, MemoryGraph.layout(big, emptyMap(), emptyList(), iterations = 30).values.count { !it.x.isNaN() })
    }
}
