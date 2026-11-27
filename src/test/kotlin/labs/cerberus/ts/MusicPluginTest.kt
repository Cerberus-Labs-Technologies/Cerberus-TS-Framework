package labs.cerberus.ts

import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.music.*
import labs.cerberus.ts.plugin.PluginManager
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import labs.cerberus.ts.voice.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MusicPluginTest {
    @TempDir lateinit var directory: Path
    @Test fun `built music plugin creates a bot through its Paper command and releases it on disable`() {
        val closed = AtomicBoolean(); val channel = AtomicInteger()
        val provider = object : VoiceProvider {
            override val id = "fake"
            override fun connect(host: String, port: Int, identity: String, nickname: String, password: String?): VoiceConnection {
                assertEquals("localhost", host); assertEquals(24871, port)
                return object : VoiceConnection {
                    override val isConnected get() = !closed.get()
                    override val clientId = 99
                    override var paused = false
                    override val bufferedPackets = 0
                    override fun move(channelId: Int, password: String?) { channel.set(channelId) }
                    override fun sendOpus(packet: ByteArray) {}
                    override fun clearAudio() {}
                    override fun onOpus(listener: (Int, ByteArray) -> Unit) = AutoCloseable {}
                    override fun privateMessage(clientId: Int, message: String) {}
                    override fun close() { closed.set(true) }
                }
            }
        }
        val events = EventBus()
        val server = TeamSpeakServer(ConnectionConfig(password = "test"), events) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = true
                override fun execute(command: QueryCommand) = QueryResponse(when (command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "1", "virtualserver_id" to "1"))
                    "serverinfo" -> listOf(mapOf("virtualserver_port" to "24871"))
                    else -> emptyList()
                }, QueryError(0, "ok"))
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
        val services = Services()
        services.register(EventBus::class.java, events)
        val commands = CommandService(server, PermissionService(directory.resolve("permissions.json")))
        services.register(CommandService::class.java, commands)
        services.register(TeamSpeakServer::class.java, server)
        val plugins = directory.resolve("plugins"); Files.createDirectories(plugins)
        Files.copy(Path.of(System.getProperty("cerberus.music.jar")), plugins.resolve("music.jar"))
        server.use {
            server.connect()
            MusicService(directory.resolve("music"), MusicConfig(), provider).use { music ->
                services.register(MusicService::class.java, music)
                PluginManager(services, directory.resolve("data")).use { manager ->
                    manager.load(plugins); manager.enable()
                    val replies = ArrayBlockingQueue<String>(1)
                    commands.console("musik create main 7 Test Music") { replies.offer(it) }
                    assertEquals("Musik-Bot main verbunden (Client 99).", replies.poll(5, TimeUnit.SECONDS))
                    assertEquals(7, channel.get())
                    assertEquals("main", music.list().single().id)
                }
                assertTrue(closed.get()); assertTrue(music.list().isEmpty())
                val replies = mutableListOf<String>()
                commands.console("music list", replies::add)
                assertTrue(replies.single().startsWith("Unknown command"))
            }
        }
    }
}
