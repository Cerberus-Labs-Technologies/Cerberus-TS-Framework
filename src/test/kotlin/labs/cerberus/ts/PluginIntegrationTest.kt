package labs.cerberus.ts

import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import kotlin.test.*

class PluginIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `example plugin jar discovers loads executes and unregisters resources`() {
        val services = Services()
        val events = EventBus()
        val sentMessages = mutableListOf<QueryCommand>()
        val server = TeamSpeakServer(ConnectionConfig(password = "test"), events) {
            object : QueryConnection {
                override val supportsNotifications = true
                override var isOpen = true
                override fun execute(command: QueryCommand): QueryResponse {
                    val rows = when (command.name) {
                        "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                        "whoami" -> listOf(mapOf("client_id" to "99", "virtualserver_id" to "1"))
                        "clientlist" -> listOf(
                            mapOf("clid" to "11", "client_type" to "0", "client_nickname" to "Kotlin Welt"),
                            mapOf("clid" to "12", "client_type" to "0", "client_nickname" to "Änne | Test"),
                            mapOf("clid" to "98", "client_type" to "1", "client_nickname" to "Other Query"),
                            mapOf("clid" to "99", "client_type" to "1", "client_nickname" to "Cerberus TS"))
                        "sendtextmessage" -> { sentMessages += command; emptyList() }
                        else -> emptyList()
                    }
                    return QueryResponse(rows, QueryError(0, "ok"))
                }
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
        val commands = CommandService(server, PermissionService(directory.resolve("permissions.json")))
        services.register(EventBus::class.java, events)
        services.register(CommandService::class.java, commands)
        services.register(TeamSpeakServer::class.java, server)
        val rest = RestService(TokenService(directory.resolve("tokens.json")))
        services.register(RestService::class.java, rest)
        val plugins = directory.resolve("plugins")
        Files.createDirectories(plugins)
        Files.copy(Path.of(System.getProperty("cerberus.example.jar")), plugins.resolve("welcome.jar"))
        server.use { PluginManager(services, directory.resolve("data")).use { manager ->
            server.connect()
            manager.load(plugins)
            manager.enable(); manager.enable()
            assertEquals("welcome", manager.descriptors().single().id)
            assertEquals(listOf(
                mapOf("targetmode" to "1", "target" to "11", "msg" to "hallo Kotlin Welt"),
                mapOf("targetmode" to "1", "target" to "12", "msg" to "hallo Änne | Test")),
                sentMessages.map { it.parameters })
            val replies = mutableListOf<String>()
            commands.console("hello \"Kotlin Welt\"", replies::add)
            assertEquals(listOf("Hallo Kotlin Welt!"), replies)
        } }
        val replies = mutableListOf<String>()
        commands.console("hello", replies::add)
        assertTrue(replies.single().startsWith("Unknown command"))
        rest.route("GET", "/welcome") { RestResponse() }.close()
        rest.close()
    }

    @Test fun `failed plugin load rolls back event registrations`() {
        val services = Services()
        val events = EventBus()
        services.register(EventBus::class.java, events)
        val plugins = directory.resolve("plugins")
        Files.createDirectories(plugins)
        JarOutputStream(Files.newOutputStream(plugins.resolve("broken.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/cerberus.properties"))
            jar.write("id=broken\nversion=2.0.0\napi=2\nmain=labs.cerberus.ts.FailingPlugin\n".toByteArray())
            jar.closeEntry()
        }
        FailingPlugin.calls = 0
        PluginManager(services, directory.resolve("data")).use { manager -> assertFails { manager.load(plugins) } }
        events.publish("event")
        assertEquals(0, FailingPlugin.calls)
    }
}

class FailingPlugin : CerberusPlugin {
    override fun onLoad(context: PluginContext) {
        context.on<String> { calls++ }
        error("Intentional load failure")
    }
    companion object { var calls = 0 }
}
