package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolLoopDetectorTest {
    private fun ToolLoopDetector.call(name: String, args: String, result: Int, long: Boolean = false, threshold: Int = 3, content: Int? = null) =
        record(name, args, result, long, threshold, content)

    @Test
    fun differentArgsAndDifferentResultsNeverTrip() {
        val d = ToolLoopDetector()
        repeat(40) { i -> assertNull(d.call("browser_navigate", "{\"url\":\"https://x/$i\"}", 1000 + i)) }
    }

    @Test
    fun sameArgsWithChangingResultsIsPollingNotLoop() {
        val d = ToolLoopDetector()
        repeat(30) { i -> assertNull(d.call("market_price", "{\"t\":\"SOL\"}", i)) }
    }

    @Test
    fun identicalCallAndResultTripsAtThreshold() {
        val d = ToolLoopDetector()
        assertNull(d.call("file_read", "{\"p\":\"a\"}", 7))
        assertNull(d.call("file_read", "{\"p\":\"a\"}", 7))
        val trip = d.call("file_read", "{\"p\":\"a\"}", 7)
        assertNotNull(trip); assertEquals(ToolLoopDetector.Kind.SAME_CALL, trip!!.kind)
    }

    @Test
    fun repetitionSeparatedByRealProgressDoesNotTrip() {
        val d = ToolLoopDetector()
        repeat(10) { i ->
            assertNull(d.call("browser_read", "{}", 1))          // mesma leitura...
            assertNull(d.call("browser_click", "{\"id\":$i}", 500 + i)) // ...mas cada clique traz resultado novo
        }
    }

    @Test
    fun alternatingWithoutNewInformationStillTrips() {
        val d = ToolLoopDetector()
        var tripped = false
        repeat(6) {
            if (d.call("browser_navigate", "{\"u\":\"a\"}", 1) != null) tripped = true
            if (d.call("browser_read", "{}", 2) != null) tripped = true
        }
        assertEquals(true, tripped)
    }

    @Test
    fun sameLongContentByDifferentPathsTripsOnlyForContentTools() {
        val d = ToolLoopDetector()
        assertNull(d.call("file_read", "{\"p\":\"a\"}", 9, long = true, content = 3))
        assertNull(d.call("file_read", "{\"p\":\"b\"}", 9, long = true, content = 3))
        assertEquals(ToolLoopDetector.Kind.SAME_CONTENT, d.call("file_read", "{\"p\":\"c\"}", 9, long = true, content = 3)?.kind)

        val other = ToolLoopDetector()
        repeat(6) { i -> assertNull(other.call("browser_navigate", "{\"u\":\"$i\"}", 9, long = true, content = null)) }
    }
}
