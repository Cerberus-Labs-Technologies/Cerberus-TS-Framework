package labs.cerberus.ts

import com.sun.net.httpserver.HttpServer
import labs.cerberus.ts.query.*
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.*

class WebQueryTest {
    private fun fixture(action: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try { action(server) } finally { server.stop(0) }
    }
    private fun config(port: Int) = ConnectionConfig(transport = QueryTransport.HTTP, host = "127.0.0.1", port = port,
        apiKey = "secret-key", minimumCommandInterval = Duration.ZERO)

    @Test fun `web query uses API header POST JSON and no query unescaping`() = fixture { server ->
        server.createContext("/") { exchange ->
            assertEquals("/1/sendtextmessage", exchange.requestURI.path)
            assertEquals("secret-key", exchange.requestHeaders.getFirst("x-api-key"))
            assertEquals("POST", exchange.requestMethod)
            assertNull(exchange.requestURI.rawQuery)
            assertEquals("Hello | \\s&=✅", com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.requestBody).path("msg").asText())
            val body = """{"body":[{"msg":"Hello | \\s&=✅"}],"status":{"code":0,"message":"ok"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        WebQueryConnection(config(server.address.port)).use {
            assertEquals("Hello | \\s&=✅", it.execute(command("sendtextmessage", "msg" to "Hello | \\s&=✅")).single()["msg"])
            assertFailsWith<IllegalArgumentException> { it.execute(command("servernotifyregister", "event" to "server")) }
        }
    }

    @Test fun `HTTP error responses retain TeamSpeak error code`() = fixture { server ->
        server.createContext("/") { exchange ->
            val body = """{"status":{"code":5122,"message":"invalid api key"}}""".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        WebQueryConnection(config(server.address.port)).use {
            assertEquals(5122, assertFailsWith<QueryException> { it.execute(command("version")) }.error.id)
        }
    }

    @Test fun `missing status code cannot become a successful response`() = fixture { server ->
        server.createContext("/") { exchange ->
            val body = """{"status":{"message":"broken"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        WebQueryConnection(config(server.address.port)).use {
            assertFailsWith<QueryDisconnectedException> { it.execute(command("version")) }
        }
    }

    @Test fun `server initializes over WebQuery without session commands`() = fixture { http ->
        val requests = mutableListOf<String>()
        http.createContext("/") { exchange ->
            val command = exchange.requestURI.path.substringAfterLast('/')
            requests += command
            val body = (if (command == "version") """{"body":[{"version":"6.0.0-beta13"}],"status":{"code":0}}"""
                else """{"body":[{"client_id":"4"}],"status":{"code":0}}""").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        http.start()
        TeamSpeakServer(config(http.address.port), EventBus()).use { server ->
            server.connect()
            assertTrue(server.isConnected)
            assertFalse(server.supportsNotifications)
            assertEquals(listOf("version", "whoami"), requests)
        }
    }

    @Test fun `WebQuery batch preserves rows options and supports virtual server selection by voice port`() = fixture { http ->
        http.createContext("/") { exchange ->
            assertEquals("/byport/9988/clientinfo", exchange.requestURI.path)
            val body = com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.requestBody)
            assertTrue(body.isArray)
            assertEquals("2", body[0].path("clid").asText())
            assertEquals("3", body[1].path("clid").asText())
            assertTrue(body.all { it.has("-uid") })
            val answer = """{"body":[{"clid":"2"},{"clid":"3"}],"status":{"code":0}}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong()); exchange.responseBody.use { it.write(answer) }
        }
        http.start()
        val config = ConnectionConfig(transport = QueryTransport.HTTP, host = "127.0.0.1", port = http.address.port,
            apiKey = "test", voicePort = 9988, minimumCommandInterval = Duration.ZERO)
        WebQueryConnection(config).use {
            assertEquals(2, it.execute(QueryCommand("clientinfo", mapOf("clid" to "2"), setOf("uid"), listOf(mapOf("clid" to "3")))).rows.size)
            assertFailsWith<IllegalArgumentException> { it.execute(command("ftinitdownload")) }
        }
    }

    @Test fun `instance scoped commands omit virtual server path`() = fixture { http ->
        http.createContext("/") { exchange ->
            assertEquals("/version", exchange.requestURI.path)
            val answer = """{"body":[{"version":"6"}],"status":{"code":0}}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong()); exchange.responseBody.use { it.write(answer) }
        }
        http.start()
        WebQueryConnection(config(http.address.port)).use {
            assertEquals("6", it.execute(QueryCommand("version", scope = QueryScope.INSTANCE)).single()["version"])
        }
    }

    @Test fun `WebQuery timeout also covers a stalled response body`() = fixture { http ->
        http.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            Thread.sleep(300)
            exchange.close()
        }
        http.start()
        val config = ConnectionConfig(transport = QueryTransport.HTTP, host = "127.0.0.1", port = http.address.port,
            apiKey = "test", commandTimeout = Duration.ofMillis(75), minimumCommandInterval = Duration.ZERO)
        WebQueryConnection(config).use {
            assertFailsWith<QueryDisconnectedException> { it.execute(command("version")) }
        }
    }
}
