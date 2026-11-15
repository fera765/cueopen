package dev.cueopen.app.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceFlowTest {
    @Test fun `start response is parsed with sane interval`() {
        val s = DeviceFlow.parseStart(JSONObject("""{"device_code":"d","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":0,"expires_in":900}"""))!!
        assertEquals("ABCD-1234", s.userCode); assertEquals(1, s.intervalSec)
        assertNull(DeviceFlow.parseStart(JSONObject("""{"error":"invalid_client"}""")))
    }

    @Test fun `poll handles pending slow down tokens and errors`() {
        assertEquals(null to false, DeviceFlow.parsePoll(JSONObject("""{"error":"authorization_pending"}"""), 0))
        assertEquals(null to true, DeviceFlow.parsePoll(JSONObject("""{"error":"slow_down"}"""), 0))
        val (t, _) = DeviceFlow.parsePoll(JSONObject("""{"access_token":"tok","refresh_token":"r","expires_in":3600}"""), 1_000)
        assertEquals("tok", t!!.accessToken); assertEquals(3_601_000L, t.expiresAtMs)
        try { DeviceFlow.parsePoll(JSONObject("""{"error":"access_denied"}"""), 0); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("recusado")) }
    }
}
