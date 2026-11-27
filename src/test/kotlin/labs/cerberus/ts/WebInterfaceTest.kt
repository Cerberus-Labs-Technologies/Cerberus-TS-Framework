package labs.cerberus.ts

import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.web.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import kotlin.test.*

class WebInterfaceTest {
    @TempDir lateinit var directory: Path
    private fun bundle() = mapOf("index.html" to WebAsset("<!doctype html><html lang=de><title>Cerberus TS</title><body>Login</body></html>".toByteArray(), "text/html; charset=utf-8"),
        "assets/app.js" to WebAsset("export const app=true".toByteArray(), "text/javascript; charset=utf-8"))
    private fun page() = WebPage("control", "Musik", module = "index.js", stylesheet = "style.css")
    private val assets = mapOf("index.js" to "web/index.js", "style.css" to "web/style.css")
    private fun resource(path: String) = ByteArrayInputStream(if (path.endsWith(".css")) ".player{color:blue}".toByteArray() else "export function mount(root,api){return ()=>{}}".toByteArray())

    @Test fun `failed shell construction releases its routes without removing existing routes`() {
        RestService(TokenService(directory.resolve("tokens.json"))).use { rest ->
            val existing = rest.publicRoute("GET", "/assets/app.js") { RestResponse(body = "existing", contentType = "text/plain") }
            assertFails { WebInterfaceService(rest, bundle()) }
            // The failed constructor had registered / before colliding with the existing asset.
            // A second shell can now claim /; the pre-existing route is still owned by its caller.
            WebInterfaceService(rest, bundle().filterKeys { it == "index.html" }).use {
                rest.start(InetSocketAddress("127.0.0.1", 0))
                HttpClient.newHttpClient().use { client ->
                    fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path")).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, get("/").statusCode())
                    assertEquals("existing", get("/assets/app.js").body())
                }
            }
            existing.close()
            // Invalid paths must be rejected before the constructor registers any public route.
            assertFails { WebInterfaceService(rest, bundle() + ("../invalid.js" to WebAsset(byteArrayOf(), "text/javascript"))) }
            WebInterfaceService(rest, bundle()).close()
        }
    }

    @Test fun `shared shell and plugin assets are public while page manifests and plugin api remain scoped`() {
        val tokens = TokenService(directory.resolve("tokens.json")); val token = tokens.generate(setOf("GET /api/web/*"))
        RestService(tokens).use { rest ->
            WebInterfaceService(rest, bundle()).use { web ->
                web.register("music", "Cerberus Music", page(), assets, ::resource)
                rest.route("GET", "/api/plugins/music/status") { RestResponse(body = "private") }
                rest.start(InetSocketAddress("127.0.0.1", 0))
                HttpClient.newHttpClient().use { client ->
                    fun get(path: String, auth: Boolean = false) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$path")).apply {
                        if (auth) header("Authorization", "Bearer $token")
                    }.build(), HttpResponse.BodyHandlers.ofString())
                    val shell = get("/"); assertEquals(200, shell.statusCode()); assertTrue(shell.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"))
                    assertTrue(shell.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self'"))
                    assertEquals(401, get("/api/web/pages").statusCode())
                    val manifest = get("/api/web/pages", true); assertEquals(200, manifest.statusCode()); assertTrue(manifest.body().contains("/plugin-assets/music/control/"))
                    assertEquals(200, get(web.pages().single().module).statusCode())
                    assertEquals(401, get("/api/plugins/music/status", true).statusCode())
                }
            }
        }
    }
    @Test fun `plugin page scopes remove assets and an old handle cannot remove a replacement registration`() {
        RestService(TokenService(directory.resolve("tokens.json"))).use { rest ->
            WebInterfaceService(rest, bundle()).use { web ->
                val first = web.register("music", "Music", page(), assets, ::resource)
                web.register("other", "Other", page().copy(order = 1), assets, ::resource)
                assertEquals(listOf("other", "music"), web.pages().map { it.plugin })
                assertFails { web.register("music", "Music", page(), assets, ::resource) }
                first.close(); val replacement = web.register("music", "Music", page(), assets, ::resource)
                first.close(); assertEquals(2, web.pages().size)
                replacement.close(); assertEquals(listOf("other"), web.pages().map { it.plugin })
            }
        }
    }
    @Test fun `missing resources external entries traversal and partial route registration cannot leave a page behind`() {
        val tokens = TokenService(directory.resolve("tokens.json")); val token = tokens.generate(setOf("GET /plugin-assets/*"))
        RestService(tokens).use { rest ->
            WebInterfaceService(rest, bundle()).use { web ->
                assertFails { web.register("music", "Music", page().copy(module = "https://example.test/a.js"), assets, ::resource) }
                assertFails { web.register("music", "Music", page(), assets + ("../unsafe.js" to "web/index.js"), ::resource) }
                assertFails { web.register("music", "Music", page(), assets + ("secret" to "../config.properties"), ::resource) }
                assertFails { web.register("music", "Music", page(), assets) { null } }
                val first = web.register("music", "Music", page(), assets, ::resource)
                val entry = web.pages().single(); first.close()
                rest.publicRoute("GET", entry.stylesheet!!) { RestResponse(body = "existing") }
                assertFails { web.register("music", "Music", page(), assets, ::resource) }
                assertTrue(web.pages().isEmpty())
                rest.start(InetSocketAddress("127.0.0.1", 0))
                HttpClient.newHttpClient().use { client ->
                    val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}${entry.module}"))
                        .header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(404, response.statusCode())
                }
            }
        }
    }
    @Test fun `core administration api reports disconnects and omits query credentials`() {
        val config = BotConfig(ConnectionConfig(password = "TOP_SECRET_VALUE"), directory.resolve("data"), directory.resolve("plugins"), webEnabled = false)
        val bot = CerberusBot(config) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = false
                override fun execute(command: QueryCommand) = QueryResponse(when(command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "1", "virtualserver_id" to "1"))
                    "serverinfo" -> listOf(mapOf("virtualserver_name" to "Community", "virtualserver_maxclients" to "32"))
                    "clientlist", "channellist" -> emptyList()
                    else -> error("Unexpected ${command.name}")
                }, QueryError(0, "ok"))
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
        bot.use {
            RestService(bot.tokens).use { rest ->
                WebControlApi(bot, rest) { emptyList() }.use {
                    val token = bot.tokens.generate(setOf("GET /api/web/*")); rest.start(InetSocketAddress("127.0.0.1", 0))
                    HttpClient.newHttpClient().use { client ->
                        fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}/api/web/$path"))
                            .header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
                        assertTrue(get("status").body().contains("\"connected\":false")); assertEquals(503, get("clients").statusCode())
                        bot.server.connect(); val response = get("status")
                        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("Community")); assertFalse(response.body().contains("TOP_SECRET_VALUE"))
                        assertEquals("[]", get("clients").body()); assertEquals("[]", get("plugins").body()); assertEquals("[]", get("music").body())
                    }
                }
            }
        }
    }
}
