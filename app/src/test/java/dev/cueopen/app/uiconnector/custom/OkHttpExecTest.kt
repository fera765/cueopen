package dev.cueopen.app.uiconnector.custom

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Executor HTTP real contra um servidor local (modo permissivo só nos testes). */
class OkHttpExecTest {
    @Test fun `executa GET e POST e nao segue redirecionamento`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val out = ("""{"m":"${ex.requestMethod}","auth":"${ex.requestHeaders.getFirst("Authorization")}","b":${if (body.isEmpty()) "null" else body}}""").toByteArray()
            ex.responseHeaders.add("Set-Cookie", "sid=abc123; Path=/")
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.createContext("/redir") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:1/x")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val http = OkHttpExec(permissive = true)
            val g = http.exec(HttpReq("GET", "$base/echo", mapOf("Authorization" to "Bearer t"), null))
            assertEquals(200, g.code)
            assertTrue(g.body.contains("\"m\":\"GET\"") && g.body.contains("Bearer t"))
            assertEquals("sid=abc123; Path=/", g.headers["set-cookie"]?.firstOrNull())
            val p = http.exec(HttpReq("POST", "$base/echo", emptyMap(), """{"x":1}"""))
            assertTrue(p.body.contains("\"m\":\"POST\"") && p.body.contains("\"x\":1"))
            assertEquals(302, http.exec(HttpReq("GET", "$base/redir", emptyMap(), null)).code)
        } finally {
            server.stop(0)
        }
    }
}
