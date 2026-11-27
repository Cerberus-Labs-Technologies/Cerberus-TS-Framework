package labs.cerberus.ts

import labs.cerberus.ts.rest.*
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ControllerParityTest {
    @TempDir lateinit var directory: Path
    data class Item(val name: String, val count: Int)
    enum class Mode { NORMAL, QUIET }
    @RestController(endpoint = "/test", pluginId = "test-plugin")
    class Controller {
        @RequestMapping(RequestMethod.GET, "/hello")
        fun hello() = ResponseEntity.Builder(Item("Cerberus", 20)).withStatus(201).withHeader("Cache-Control", "no-store").build()
        @RequestMapping(RequestMethod.GET, "/info/:name")
        fun info(@PathParam("name") name: String, @RequestParam("num", type = Int::class) number: Int,
            @RequestParam("flag", required = false, defaultValue = "false") flag: Boolean) = "$name:$number:$flag"
        @RequestMapping(RequestMethod.POST, "/body") fun body(@RequestBody item: Item) = item.copy(count = item.count + 1)
        @RequestMapping(RequestMethod.POST, "/list") fun list(@RequestBody items: List<Item>) = items.sumOf { it.count }
        @RequestMapping(RequestMethod.POST, "/raw") fun raw(@RequestBody text: String) = text
        @RequestMapping(RequestMethod.GET, "/public", isSecured = false) fun public() = mapOf("public" to true)
        @RequestMapping(RequestMethod.GET, "/typed/{id}")
        fun typed(@PathParam("id") id: UUID, @RequestParam("mode") mode: Mode, @RequestParam("optional", required = false) optional: String?) =
            mapOf("id" to id.toString(), "mode" to mode.name, "optional" to optional)
        @RequestMapping(RequestMethod.DELETE, "/empty") fun empty() {}
        @RequestMapping(RequestMethod.GET, "/gone") fun gone(): Nothing = throw RestException(410, "Removed")
        @RequestMapping(RequestMethod.GET, "/broken") fun broken(): Nothing = error("Private internals")
    }

    @Test fun `original controller examples and typed kotlin bodies work over real http with response headers`() = withServer { rest, client, token ->
        val controller = rest.registerController(Controller())
        fun request(path: String, method: String = "GET", body: String? = null, auth: String? = token): HttpResponse<String> =
            client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path")).apply {
                auth?.let { header("Authorization", "Bearer $it") }
                method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            }.build(), HttpResponse.BodyHandlers.ofString())
        val hello = request("/test/hello"); assertEquals(201, hello.statusCode()); assertEquals("no-store", hello.headers().firstValue("Cache-Control").orElseThrow())
        assertEquals("{\"name\":\"Cerberus\",\"count\":20}", hello.body())
        assertEquals("\"Anne Marie:7:false\"", request("/test/info/Anne%20Marie?num=7").body())
        assertEquals("\"Anna:7:true\"", request("/test/info/Anna?num=7&flag=true").body())
        assertEquals("{\"name\":\"Musik\",\"count\":3}", request("/test/body", "POST", """{"name":"Musik","count":2}""").body())
        assertEquals("5", request("/test/list", "POST", """[{"name":"A","count":2},{"name":"B","count":3}]""").body())
        assertEquals("\"Plain ✓\"", request("/test/raw", "POST", "Plain ✓").body())
        assertEquals(200, request("/test/public", auth = null).statusCode()); assertEquals(401, request("/test/hello", auth = null).statusCode())
        assertEquals(204, request("/test/empty", "DELETE").statusCode()); assertEquals("", request("/test/empty", "DELETE").body())
        val id = UUID.randomUUID()
        assertEquals("{\"id\":\"$id\",\"mode\":\"QUIET\",\"optional\":null}", request("/test/typed/$id?mode=QUIET").body())
        assertEquals(410, request("/test/gone").statusCode()); val broken = request("/test/broken")
        assertEquals(500, broken.statusCode()); assertFalse(broken.body().contains("Private internals"))
        controller.close(); assertEquals(404, request("/test/hello").statusCode())
    }
    @Test fun `invalid parameters json encoded separators body size and token scopes are rejected`() = withServer { rest, client, token ->
        rest.registerController(Controller())
        fun request(path: String, body: String? = null, key: String = token) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path"))
            .header("Authorization", "Bearer $key").apply { if (body != null) POST(HttpRequest.BodyPublishers.ofString(body)) }.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(400, request("/test/info/Alice").statusCode())
        assertEquals(400, request("/test/info/Alice?num=hello").statusCode())
        assertEquals(400, request("/test/info/Alice?num=1&num=2").statusCode())
        assertEquals(400, request("/test/info/Alice?num=1&flag=maybe").statusCode())
        assertEquals(400, request("/test/info/Alice%2Ftest?num=1").statusCode())
        listOf("{", "{}", "null", "{\"name\":\"A\",\"count\":null}", "{\"name\":\"A\",\"count\":1} {}").forEach {
            assertEquals(400, request("/test/body", it).statusCode(), it)
        }
        assertEquals(413, request("/test/body", "x".repeat(1024 * 1024 + 1)).statusCode())
        assertEquals(401, request("/test/hello", key = "wrong-token").statusCode())
    }
    @Test fun `route registration is atomic conflicts include renamed placeholders and exact routes win`() = withServer { rest, client, token ->
        val first = rest.route("GET", "/items/:id") { RestResponse(body = "dynamic") }
        assertFails { rest.route("GET", "/items/{other}") { RestResponse() } }
        rest.route("GET", "/items/fixed") { RestResponse(body = "fixed") }
        @RestController("/test") class Invalid {
            @RequestMapping(RequestMethod.GET, "/valid") fun valid() = "ok"
            @RequestMapping(RequestMethod.GET, "/invalid") fun invalid(value: Int) = value
        }
        assertFails { rest.registerController(Invalid()) }
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path")).header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
        assertEquals("\"fixed\"", get("/items/fixed").body()); assertEquals("\"dynamic\"", get("/items/7").body())
        assertEquals(404, get("/test/valid").statusCode()); first.close(); assertEquals(404, get("/items/7").statusCode())
        rest.route("GET", "/a/:id/c") { RestResponse() }
        assertFails { rest.route("GET", "/a/b/:id") { RestResponse() } }
    }
    @Test fun `public binary assets head options and header injection protection work`() = withServer { rest, client, token ->
        rest.publicRoute("GET", "/asset.js") { RestResponse(body = "export const x = 1".toByteArray(), contentType = "text/javascript; charset=utf-8") }
        rest.route("HEAD", "/test/head") { RestResponse(body = mapOf("body" to "hidden")) }
        rest.route("OPTIONS", "/test/options") { RestResponse(204, headers = mapOf("Allow" to "GET, OPTIONS")) }
        fun send(path: String, method: String, auth: Boolean = true) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path")).apply {
            if (auth) header("Authorization", "Bearer $token")
            method(method, HttpRequest.BodyPublishers.noBody())
        }.build(), HttpResponse.BodyHandlers.ofString())
        val asset = send("/asset.js", "GET", false); assertEquals(200, asset.statusCode()); assertEquals("export const x = 1", asset.body())
        assertTrue(asset.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"))
        assertEquals("", send("/test/head", "HEAD").body()); assertEquals(204, send("/test/options", "OPTIONS").statusCode())
        assertFails { RestResponse(headers = mapOf("X-Header" to "a\r\nb")) }
    }
    private fun withServer(action: (RestService, HttpClient, String) -> Unit) {
        val tokens = TokenService(directory.resolve("tokens.json"))
        val token = tokens.generate(setOf("GET /test/*", "POST /test/*", "DELETE /test/*", "HEAD /test/*", "OPTIONS /test/*", "GET /items/*", "GET /a/*"))
        RestService(tokens).use { rest -> rest.start(InetSocketAddress("127.0.0.1", 0)); HttpClient.newHttpClient().use { action(rest, it, token) } }
    }
}
