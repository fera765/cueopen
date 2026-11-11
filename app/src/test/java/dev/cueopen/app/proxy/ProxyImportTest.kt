package dev.cueopen.app.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyImportTest {
    @Test fun `imports one proxy per line and skips credentials duplicates and junk`() {
        val text = """
            # minha lista
            1.2.3.4:8080
            socks5://5.6.7.8:1080
            https://proxy.example.com:3128/
            1.2.3.4:8080
            user:pass@9.9.9.9:8000
            9.9.9.9:8000:user:pass
            sem-porta
            10.0.0.1:99999
            ftp://1.1.1.1:21
        """.trimIndent()
        val r = ProxyImport.parse(text)
        assertEquals(listOf("1.2.3.4:8080", "5.6.7.8:1080", "proxy.example.com:3128"), r.profiles.map { it.name })
        assertEquals(listOf(ProxyScheme.HTTP, ProxyScheme.SOCKS, ProxyScheme.HTTPS), r.profiles.map { it.scheme })
        assertEquals(1, r.duplicates)
        assertEquals(2, r.withCredentials)
        assertEquals(3, r.invalid)
        assertEquals(3, r.profiles.map { it.id }.toSet().size)
    }

    @Test fun `does not reimport profiles that already exist`() {
        val existing = listOf(ProxyProfile(id = "a", name = "x", scheme = ProxyScheme.HTTP, host = "1.2.3.4", port = 8080))
        val r = ProxyImport.parse("1.2.3.4:8080\n1.2.3.5:8080", existing)
        assertEquals(listOf("1.2.3.5:8080"), r.profiles.map { it.name })
        assertEquals(1, r.duplicates)
    }
}
