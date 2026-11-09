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

    @Test
    fun pingPongBetweenTwoIdenticalCallsTrips() {
        val d = ToolLoopDetector()
        val kinds = (0 until 3).flatMap {
            listOf(d.call("browser_back", "{}", 1, threshold = 99), d.call("browser_read", "{}", 2, threshold = 99))
        }
        assertEquals(ToolLoopDetector.Kind.PING_PONG, kinds.last()?.kind)
    }

    @Test
    fun globalBreakerTripsAfterManyCallsWithoutAnythingNew() {
        val d = ToolLoopDetector()
        assertNull(d.call("file_read", "{\"p\":\"0\"}", 5, long = true, threshold = 99))
        var last: ToolLoopDetector.Trip? = null
        for (i in 1 until ToolLoopDetector.NO_PROGRESS_CALLS) last = d.call("file_read", "{\"p\":\"$i\"}", 5, long = true, threshold = 99)
        assertEquals(ToolLoopDetector.Kind.NO_PROGRESS, last?.kind)
    }

    @Test
    fun postCompactionGuardCatchesTheSameLoopAfterCompacting() {
        val d = ToolLoopDetector()
        assertNull(d.call("shell", "{\"c\":\"ls\"}", 3, threshold = 99))
        d.armPostCompaction()
        assertNull(d.call("shell", "{\"c\":\"ls\"}", 3, threshold = 99))
        assertEquals(ToolLoopDetector.Kind.POST_COMPACTION, d.call("shell", "{\"c\":\"ls\"}", 3, threshold = 99)?.kind)

        val fresh = ToolLoopDetector()
        assertNull(fresh.call("shell", "{\"c\":\"ls\"}", 3, threshold = 99))
        fresh.armPostCompaction()
        repeat(3) { i -> assertNull(fresh.call("shell", "{\"c\":\"cat $i\"}", 10 + i, threshold = 99)) }
    }

    @Test
    fun unknownToolRetriesTrip() {
        val d = ToolLoopDetector()
        assertNull(d.unknownTool("magic"))
        assertNull(d.unknownTool("magic"))
        assertEquals(ToolLoopDetector.Kind.UNKNOWN_TOOL, d.unknownTool("magic")?.kind)
    }
}
