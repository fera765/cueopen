package dev.cueopen.app.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveReadGuardTest {
    @Test fun sameArchiveOnlyOncePerTask() {
        val g = ArchiveReadGuard()
        assertNull(g.check("s", "t1", "o1_40075.txt", 40_075))
        assertTrue(g.check("s", "t1", "o1_40075.txt", 40_075)!!.contains("já leu"))
        assertNull(g.check("s", "t2", "o1_40075.txt", 40_075))  // nova tarefa do usuário zera
    }

    @Test fun distinctLimitAndTinyFiles() {
        val g = ArchiveReadGuard(maxDistinct = 3)
        assertNull(g.check("s", "t", "a.txt", 5000))
        assertNull(g.check("s", "t", "b.txt", 5000))
        assertNotNull(g.check("s", "t", "o857_301.txt", 301))     // minúsculo depois de outras leituras
        assertNull(g.check("s", "t", "c.txt", 5000))
        assertTrue(g.check("s", "t", "d.txt", 5000)!!.contains("limite"))
    }
}
