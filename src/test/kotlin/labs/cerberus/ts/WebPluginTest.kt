package labs.cerberus.ts

import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.plugin.PluginManager
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import labs.cerberus.ts.web.WebInterfaceService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WebPluginTest {
    @TempDir lateinit var directory: Path
    @Test fun `actual welcome jar contributes a web page persists changes and removes its api on unload`() {
        val events = EventBus()
        val server = TeamSpeakServer(ConnectionConfig(password = "fixture"), events) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = false
                override fun execute(command: QueryCommand) = QueryResponse(when(command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "1", "virtualserver_id" to "1"))
                    "clientlist" -> emptyList()
                    else -> error("Unexpected command")
                }, QueryError(0, "ok"))
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
        server.use {
            server.connect()
            val tokens = TokenService(directory.resolve("tokens.json"))
            RestService(tokens).use { rest ->
                WebInterfaceService(rest, emptyMap()).use { web ->
                    val services = Services(); val permissions = PermissionService(directory.resolve("permissions.json"))
                    services.register(EventBus::class.java, events); services.register(TeamSpeakServer::class.java, server)
                    services.register(CommandService::class.java, CommandService(server, permissions))
                    services.register(RestService::class.java, rest); services.register(WebInterfaceService::class.java, web)
                    val plugins = Files.createDirectory(directory.resolve("plugins"))
                    Files.copy(Path.of(System.getProperty("cerberus.example.jar")), plugins.resolve("welcome.jar"))
                    val manager = PluginManager(services, directory.resolve("data"))
                    manager.load(plugins); manager.enable()
                    rest.start(InetSocketAddress("127.0.0.1", 0))
                    val read = tokens.generate(setOf("GET /api/plugins/welcome/*", "GET /api/web/*"))
                    val admin = tokens.generate(setOf("GET /api/plugins/welcome/*", "PUT /api/plugins/welcome/*"))
                    HttpClient.newHttpClient().use { client ->
                        fun request(method: String, token: String, body: String? = null) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}/api/plugins/welcome/settings"))
                            .header("Authorization", "Bearer $token").method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                        assertEquals("welcome", web.pages().single().plugin)
                        val module = web.pages().single().module
                        val asset = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}$module")).build(), HttpResponse.BodyHandlers.ofString())
                        assertEquals(200, asset.statusCode()); assertTrue(asset.body().contains("export async function mount"))
                        assertEquals(200, request("GET", read).statusCode())
                        assertEquals(401, request("PUT", read, """{"greeting":"Guten Abend"}""").statusCode())
                        assertEquals(400, request("PUT", admin, """{"greeting":""}""").statusCode())
                        assertEquals("{\"greeting\":\"Guten Abend\"}", request("PUT", admin, """{"greeting":"Guten Abend"}""").body())
                        assertEquals("Guten Abend", labs.cerberus.ts.configuration.YamlConfiguration.loadConfiguration(directory.resolve("data/welcome/config.yml")).getString("greeting"))
                        manager.close()
                        assertTrue(web.pages().isEmpty()); assertEquals(404, request("GET", read).statusCode())
                    }
                }
            }
        }
    }
}
