package dev.cueopen.app.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {
    @After fun clean() = KeyPool.reset()

    @Test fun `single key never rotates`() {
        assertEquals("k1", KeyPool.current(" k1 "))
        assertFalse(KeyPool.rotate("k1"))
    }

    @Test fun `rotates through every account once then gives up until a success`() {
        val raw = "k1\nk2\nk3"
        assertEquals("k1", KeyPool.current(raw))
        assertTrue(KeyPool.rotate(raw)); assertEquals("k2", KeyPool.current(raw))
        assertTrue(KeyPool.rotate(raw)); assertEquals("k3", KeyPool.current(raw))
        assertFalse(KeyPool.rotate(raw)); assertEquals("k3", KeyPool.current(raw))
        KeyPool.onSuccess(raw)
        assertTrue(KeyPool.rotate(raw)); assertEquals("k1", KeyPool.current(raw))
        assertEquals("3 contas", KeyPool.describe(raw))
    }
}
