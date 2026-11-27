package dev.fluwy.app.browser

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackEndpointProbeTest {
    @Test
    fun recognizesLocalHttpEndpointsAndTheirPorts() {
        assertEquals(4174, LoopbackEndpointProbe.endpoint("http://localhost:4174/")?.port)
        assertEquals(8080, LoopbackEndpointProbe.endpoint("http://127.0.0.1:8080/")?.port)
        assertEquals("localhost:4174", LoopbackEndpointProbe.endpoint("http://localhost:4174/")?.authority)
    }

    @Test
    fun ignoresRemoteHostsAndNonHttpUrls() {
        assertNull(LoopbackEndpointProbe.endpoint("https://example.com/"))
        assertNull(LoopbackEndpointProbe.endpoint("file:///android_asset/start/index.html"))
        assertFalse(LoopbackEndpointProbe.endpoint("https://192.168.1.4/") != null)
    }

    @Test
    fun openLoopbackPortPassesTheTcpProbe() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val endpoint = LoopbackEndpointProbe.endpoint("http://127.0.0.1:${server.localPort}/")

            assertNotNull(endpoint)
            assertTrue(LoopbackEndpointProbe.isReachable(endpoint!!, timeoutMillis = 500))
        }
    }
}
