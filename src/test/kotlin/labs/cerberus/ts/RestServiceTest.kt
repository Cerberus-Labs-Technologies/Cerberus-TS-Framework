package labs.cerberus.ts

import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.*
import kotlin.test.*

class RestServiceTest {
    @TempDir lateinit var directory: Path
    @Test fun `REST requires scoped bearer tokens and unregisters plugin routes`() {
        val tokens = TokenService(directory.resolve("tokens.json"))
        val token = tokens.generate(setOf("GET /hello", "POST /hello"))
        RestService(tokens).use { rest ->
            val handle = rest.route("GET", "/hello") { RestResponse(body = mapOf("hello" to "world")) }
            rest.start(InetSocketAddress("127.0.0.1", 0))
            HttpClient.newHttpClient().use { client ->
                val uri = URI("http://127.0.0.1:${rest.port}/hello")
                fun get(key: String?) = client.send(HttpRequest.newBuilder(uri).apply {
                    key?.let { header("Authorization", "Bearer $it") }
                }.build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(401, get(null).statusCode())
                assertEquals(401, get("invalid").statusCode())
                assertEquals(200, get(token).statusCode())
                assertEquals("{\"hello\":\"world\"}", get(token).body())
                handle.close()
                assertEquals(404, get(token).statusCode())
                tokens.revoke(token)
                assertEquals(401, get(token).statusCode())
            }
        }
    }
}
