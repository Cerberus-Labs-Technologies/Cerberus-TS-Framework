package labs.cerberus.ts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.music.*
import labs.cerberus.ts.plugin.PluginManager
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import labs.cerberus.ts.voice.*
import labs.cerberus.ts.web.WebInterfaceService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.*
import kotlin.test.*

class MusicWebTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
    @Test fun `actual music jar controls real playback over scoped HTTP and cleans up on unload`() {
        val ffmpeg = System.getenv("CERBERUS_TEST_FFMPEG")
        assumeTrue(!ffmpeg.isNullOrBlank(), "Set CERBERUS_TEST_FFMPEG for the HTTP playback integration")
        val channel = AtomicInteger(); val packets = AtomicInteger(); val connected = ConcurrentHashMap.newKeySet<String>()
        val connecting = CountDownLatch(1); val releaseConnect = CountDownLatch(1)
        val provider = object : VoiceProvider {
            override val id = "fixture"
            override fun connect(host: String, port: Int, identity: String, nickname: String, password: String?): VoiceConnection {
                assertEquals("localhost", host); assertEquals(24871, port)
                if (nickname == "Blocked") { connecting.countDown(); check(releaseConnect.await(5, TimeUnit.SECONDS)) }
                connected += identity
                return object : VoiceConnection {
                    override val isConnected get() = identity in connected
                    override val clientId = 42
                    override var paused = false
                    override val bufferedPackets = 0
                    override fun move(channelId: Int, password: String?) { channel.set(channelId) }
                    override fun sendOpus(packet: ByteArray) { packets.incrementAndGet(); Thread.sleep(20) }
                    override fun clearAudio() {}
                    override fun onOpus(listener: (Int, ByteArray) -> Unit) = AutoCloseable {}
                    override fun privateMessage(clientId: Int, message: String) {}
                    override fun close() { connected -= identity }
                }
            }
        }
        val events = EventBus()
        val server = TeamSpeakServer(ConnectionConfig(password = "fixture"), events) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = false
                override fun execute(command: QueryCommand) = QueryResponse(when (command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "1", "virtualserver_id" to "1"))
                    "serverinfo" -> listOf(mapOf("virtualserver_port" to "24871"))
                    "channellist" -> listOf(mapOf("cid" to "7", "pid" to "0", "channel_name" to "Musik <&>"))
                    else -> emptyList()
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
                    MusicService(directory.resolve("music"), MusicConfig(ffmpeg = ffmpeg!!), provider).use { music ->
                        val files = Files.createDirectories(directory.resolve("music/files"))
                        val pcm = ByteArray(48000 * 4 * 30)
                        AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48000f, 16, 2, true, false), pcm.size.toLong() / 4).use {
                            AudioSystem.write(it, AudioFileFormat.Type.WAVE, files.resolve("test.wav").toFile())
                        }
                        val services = Services()
                        services.register(EventBus::class.java, events); services.register(TeamSpeakServer::class.java, server)
                        services.register(CommandService::class.java, CommandService(server, PermissionService(directory.resolve("permissions.json"))))
                        services.register(RestService::class.java, rest); services.register(WebInterfaceService::class.java, web)
                        services.register(MusicService::class.java, music)
                        val plugins = Files.createDirectory(directory.resolve("plugins"))
                        Files.copy(Path.of(System.getProperty("cerberus.music.jar")), plugins.resolve("music.jar"))
                        PluginManager(services, directory.resolve("data")).use { manager ->
                            manager.load(plugins); manager.enable()
                            rest.start(InetSocketAddress("127.0.0.1", 0))
                            val read = tokens.generate(setOf("GET /api/plugins/music/*"))
                            val admin = tokens.generate(setOf("GET /api/plugins/music/*", "POST /api/plugins/music/*", "DELETE /api/plugins/music/*", "GET /plugin-assets/*"))
                            HttpClient.newHttpClient().use { client ->
                                fun request(method: String, path: String, body: String? = null, token: String? = admin): HttpResponse<String> {
                                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}/api/plugins/music$path"))
                                        .timeout(java.time.Duration.ofSeconds(10))
                                    token?.let { builder.header("Authorization", "Bearer $it") }
                                    return client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                                }
                                fun action(body: String) = request("POST", "/bots/radio/actions", body)
                                assertEquals(200, request("GET", "/video").statusCode())
                                for ((method, path, body) in listOf(
                                    Triple("POST", "/video", """{"id":"denied","channelId":7}"""),
                                    Triple("POST", "/video/denied/actions", """{"action":"play","source":"clip.mp4"}"""),
                                    Triple("DELETE", "/video/denied", null)
                                )) {
                                    assertEquals(401, request(method, path, body, read).statusCode())
                                    assertEquals(401, request(method, path, body, null).statusCode())
                                }
                                val page = web.pages().single()
                                assertEquals("music", page.plugin); assertEquals("Musik-Bots", page.title)
                                val asset = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}${page.module}")).build(), HttpResponse.BodyHandlers.ofString())
                                assertEquals(200, asset.statusCode()); assertTrue(asset.body().contains("export async function mount"))
                                assertEquals(401, request("GET", "/bots", token = null).statusCode())
                                assertEquals(200, request("GET", "/bots", token = read).statusCode())
                                assertEquals("Musik <&>", mapper.readTree(request("GET", "/channels").body())[0]["name"].asText())
                                val create = """{"id":"radio","channelId":7,"nickname":"Radio"}"""
                                assertEquals(401, request("POST", "/bots", create, read).statusCode())
                                assertEquals(400, request("POST", "/bots", """{"id":"BAD","channelId":7}""").statusCode())
                                assertEquals(400, request("POST", "/bots", """{"id":"radio","channelId":null}""").statusCode())
                                assertEquals(200, request("POST", "/bots", create).statusCode()); assertEquals(7, channel.get())
                                assertEquals(409, request("POST", "/bots", create).statusCode())
                                assertEquals(401, request("POST", "/bots/radio/actions", """{"action":"stop"}""", read).statusCode())
                                assertEquals(401, request("DELETE", "/bots/radio", token = read).statusCode())
                                for (body in listOf("""{"action":"volume","volume":101}""", """{"action":"play","source":""}""", """{"action":"move","channelId":0}""", """{"action":"execute"}""", """{"action":"volume"}""")) assertEquals(400, action(body).statusCode(), body)
                                assertEquals(404, request("POST", "/bots/missing/actions", """{"action":"stop"}""").statusCode())
                                assertEquals(200, action("""{"action":"play","source":"test.wav"}""").statusCode())
                                eventually { packets.get() > 0 }
                                assertEquals("test.wav", mapper.readTree(request("GET", "/bots").body())[0]["current"].asText())
                                assertEquals(200, action("""{"action":"pause"}""").statusCode()); assertTrue(music.bot("radio").status().paused)
                                assertEquals(200, action("""{"action":"enqueue","source":"test.wav"}""").statusCode()); assertEquals(listOf("test.wav"), music.bot("radio").status().queued)
                                assertEquals(200, action("""{"action":"volume","volume":25}""").statusCode()); assertEquals(25, music.bot("radio").status().volume)
                                assertEquals(200, action("""{"action":"move","channelId":9}""").statusCode()); assertEquals(9, channel.get())
                                assertEquals(200, action("""{"action":"resume"}""").statusCode()); assertFalse(music.bot("radio").status().paused)
                                assertEquals(200, action("""{"action":"skip"}""").statusCode()); eventually { music.bot("radio").status().queued.isEmpty() }
                                assertEquals(200, action("""{"action":"stop"}""").statusCode()); eventually { music.bot("radio").status().current == null }
                                assertEquals(204, request("DELETE", "/bots/radio").statusCode()); assertTrue(connected.isEmpty())
                                val executor = Executors.newSingleThreadExecutor()
                                try {
                                    val pending = executor.submit<HttpResponse<String>> { request("POST", "/bots", """{"id":"blocked","channelId":7,"nickname":"Blocked"}""") }
                                    assertTrue(connecting.await(5, TimeUnit.SECONDS))
                                    assertEquals(409, request("POST", "/bots", create).statusCode())
                                    releaseConnect.countDown(); assertEquals(200, pending.get(5, TimeUnit.SECONDS).statusCode())
                                } finally { releaseConnect.countDown(); executor.shutdownNow() }
                                manager.close(); assertTrue(music.list().isEmpty()); assertTrue(connected.isEmpty()); assertTrue(web.pages().isEmpty())
                                assertEquals(404, request("GET", "/bots").statusCode())
                                assertEquals(404, client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${rest.port}${page.module}")).header("Authorization", "Bearer $admin").build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                            }
                        }
                    }
                }
            }
        }
    }
}
