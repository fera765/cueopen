package dev.cueopen.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPoliciesTest {
    @Test
    fun detectsForegroundServers() {
        assertTrue(ShellCommandPolicy.isLongRunningServer("cd /root/workspace && python3 -m http.server 8080 --bind 0.0.0.0"))
        assertTrue(ShellCommandPolicy.isLongRunningServer("npx serve -l 8080 ."))
        assertTrue(ShellCommandPolicy.isLongRunningServer("npm run dev"))
        assertFalse(ShellCommandPolicy.isLongRunningServer("python3 -m http.server 8080 &"))
        assertFalse(ShellCommandPolicy.isLongRunningServer("nohup python3 -m http.server 8080 > s.log 2>&1 &"))
        assertFalse(ShellCommandPolicy.isLongRunningServer("curl -s http://127.0.0.1:8080/index.html"))
        assertFalse(ShellCommandPolicy.isLongRunningServer("pkill -f http.server"))
    }

    @Test
    fun detectsWholeSystemSearch() {
        assertTrue(ShellCommandPolicy.isWholeSystemSearch("find / -name \"copy*.md\" 2>/dev/null"))
        assertTrue(ShellCommandPolicy.isWholeSystemSearch("cd x; grep -rn \"pixel\" / 2>/dev/null"))
        assertTrue(ShellCommandPolicy.isWholeSystemSearch("find /vendor -name x"))
        assertFalse(ShellCommandPolicy.isWholeSystemSearch("find /root/workspace -maxdepth 2 -type f"))
        assertFalse(ShellCommandPolicy.isWholeSystemSearch("grep -rn fbq ."))
        assertFalse(ShellCommandPolicy.isWholeSystemSearch("ls -la /root/workspace"))
    }

    @Test
    fun riskSignals() {
        val a = MemberSupervision.Action("file_read", "/root/workspace/index.html", false)
        assertNotNull(MemberSupervision.riskSignal(listOf(a, a, a)))
        val fails = listOf(
            MemberSupervision.Action("shell", "x1", true), MemberSupervision.Action("shell", "x2", true),
            MemberSupervision.Action("shell", "x3", true))
        assertTrue(MemberSupervision.riskSignal(fails)!!.contains("falharam"))
        val ok = listOf(
            MemberSupervision.Action("file_write", "index.html", false), MemberSupervision.Action("shell", "ls", false),
            MemberSupervision.Action("browser_navigate", "http://x", false))
        assertNull(MemberSupervision.riskSignal(ok))
    }
}
