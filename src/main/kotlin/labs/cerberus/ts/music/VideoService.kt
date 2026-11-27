package labs.cerberus.ts.music

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Optional media worker, isolated from the Query connection and existing audio bots. */
data class VideoStatus(val id: String, val connected: Boolean = false, val clientId: Int = 0, val channelId: Int = 0,
    val current: String? = null, val title: String? = null, val phase: String = "STOPPED", val paused: Boolean = false,
    val volume: Int = 25, val queued: List<String> = emptyList(), val viewers: Int = 0, val streamId: String? = null,
    val error: String? = null, val viewerError: String? = null)

class VideoService(private val directory: Path, private val config: MusicConfig) : AutoCloseable {
    private val bots = linkedMapOf<String, VideoBot>()
    private var closed = false
    private fun helper(): Path {
        System.getenv("CERBERUS_VIDEO_HELPER")?.let { return Path.of(it).toAbsolutePath() }
        val library = Path.of(VideoService::class.java.protectionDomain.codeSource.location.toURI())
        val installed = library.parent?.parent?.resolve("video/worker.mjs")
        return if (installed != null && Files.isRegularFile(installed)) installed else Path.of("video/worker.mjs").toAbsolutePath()
    }
    fun available() = Files.isRegularFile(helper()) && generateSequence(helper().parent) { it.parent }.take(3)
        .any { Files.isRegularFile(it.resolve("node_modules/@honeybbq/teamspeak/package.json")) }
    @Synchronized fun create(id: String, host: String, port: Int, channelId: Int, nickname: String,
        serverPassword: String? = null, channelPassword: String? = null): VideoStatus {
        check(!closed) { "Video-Service ist beendet." }
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,19}"))) { "Video-Bot-ID: Kleinbuchstaben, Ziffern, _ oder -, maximal 20 Zeichen." }
        require(channelId > 0 && port in 1..65535 && nickname.length in 3..30 && nickname.none { it.isISOControl() })
        check(id !in bots) { "Video-Bot existiert bereits." }
        check(bots.size < config.maxBots) { "Maximale Video-Bot-Anzahl erreicht." }
        check(available()) { "Video-Worker fehlt. Distribution mit Video-Abhängigkeiten installieren." }
        val root = directory.toAbsolutePath().resolve("video/$id")
        Files.createDirectories(root)
        val env = System.getenv()
        val minimum = env["CERBERUS_VIDEO_PORT_MIN"]?.toInt() ?: 24900
        val maximum = env["CERBERUS_VIDEO_PORT_MAX"]?.toInt() ?: 24919
        require(minimum in 1024..65534 && maximum in minimum + 1..65535) { "Ungültiger Video-UDP-Portbereich." }
        val publicAddress = env["CERBERUS_VIDEO_PUBLIC_ADDRESS"]?.takeIf { it.isNotBlank() }
        require(publicAddress == null || publicAddress.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && publicAddress.split('.').all { it.toInt() in 0..255 }) { "Video-Public-Address muss eine IPv4-Adresse sein." }
        val bot = VideoBot(id, config.javascriptRuntime, helper(), mapOf(
            "host" to host, "port" to port, "channelId" to channelId, "nickname" to nickname,
            "serverPassword" to serverPassword, "channelPassword" to channelPassword,
            "directory" to root.toString(), "identity" to root.resolve("identity.key").toString(),
            "files" to directory.toAbsolutePath().resolve("files").toString(), "ffmpeg" to config.ffmpeg,
            "ffprobe" to env.getOrDefault("CERBERUS_VIDEO_FFPROBE", Path.of(config.ffmpeg).parent?.resolve("ffprobe")?.toString() ?: "ffprobe"),
            "ytDlp" to config.ytDlp, "node" to config.javascriptRuntime, "maxQueue" to minOf(config.maxQueue, 100),
            "maxViewers" to 4, "portMin" to minimum, "portMax" to maximum,
            "publicAddress" to publicAddress, "stun" to env["CERBERUS_VIDEO_STUN"]
        ))
        bots[id] = bot
        return bot.status()
    }
    @Synchronized fun list() = bots.values.map { it.status() }
    @Synchronized fun bot(id: String) = bots[id] ?: throw IllegalArgumentException("Unbekannter Video-Bot.")
    fun remove(id: String) { val bot = synchronized(this) { bots.remove(id) } ?: throw IllegalArgumentException("Unbekannter Video-Bot."); bot.close() }
    override fun close() {
        val owned = synchronized(this) { if (closed) return; closed = true; bots.values.toList().also { bots.clear() } }
        owned.forEach { runCatching { it.close() } }
    }
}

internal class VideoWorkerException(message: String) : IllegalStateException(message)

class VideoBot internal constructor(val id: String, node: String, helper: Path, config: Map<String, Any?>) : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val counter = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<VideoStatus>>()
    private val outputLock = Any()
    private val process = ProcessBuilder(node, helper.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val output = process.outputStream.bufferedWriter()
    @Volatile private var current = VideoStatus(id)
    @Volatile private var closed = false
    private val reader = Thread.ofPlatform().daemon().name("cerberus-video-$id").start {
        try {
            process.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    check(line.length <= 1048576) { "Invalid video worker response" }
                    val json = mapper.readTree(line)
                    val status = json.get("status")?.let { mapper.treeToValue((it.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()).put("id", id), VideoStatus::class.java) }
                    if (status != null) current = status
                    val request = json.get("requestId")?.asLong()?.let { pending.remove(it) }
                    if (json.has("error")) {
                        val error = json.path("error").asText().take(200)
                        request?.completeExceptionally(VideoWorkerException(error))
                        if (json.path("event").asText() == "fatal") current = current.copy(phase = "ERROR", error = error)
                    } else if (status != null) request?.complete(status)
                }
            }
        } catch (_: Exception) { /* Report a fixed message; tool output may contain source credentials. */ }
        finally {
            current = current.copy(connected = false, phase = if (closed) "STOPPED" else "ERROR",
                error = if (closed) null else "Video-Worker getrennt. Bot neu verbinden.")
            pending.values.forEach { it.completeExceptionally(VideoWorkerException("Video-Worker getrennt.")) }; pending.clear()
        }
    }
    init {
        try { request(mapOf("action" to "init", "config" to config), 50) }
        catch (e: Exception) { close(); throw e }
    }
    fun status() = current
    fun action(action: String, source: String? = null, volume: Int? = null, channelId: Int? = null, channelPassword: String? = null): VideoStatus {
        require(action in setOf("play", "enqueue", "pause", "resume", "skip", "stop", "volume", "move")) { "Unbekannte Video-Aktion." }
        if (action in setOf("play", "enqueue")) require(!source.isNullOrBlank() && source.length <= 8192 && source.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Ungültige Videoquelle." }
        if (action == "volume") require(volume != null && volume in 0..100) { "Lautstärke: 0 bis 100." }
        if (action == "move") require(channelId != null && channelId > 0) { "Ungültiger Channel." }
        return request(mapOf("action" to action, "source" to source, "volume" to volume, "channelId" to channelId, "channelPassword" to channelPassword))
    }
    private fun request(body: Map<String, Any?>, timeout: Long = 15): VideoStatus {
        check(!closed && process.isAlive) { "Video-Worker ist getrennt." }
        val number = counter.incrementAndGet()
        val result = CompletableFuture<VideoStatus>(); pending[number] = result
        try {
            synchronized(outputLock) { output.write(mapper.writeValueAsString(body + ("requestId" to number))); output.newLine(); output.flush() }
            return result.get(timeout, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? RuntimeException ?: VideoWorkerException("Video-Aktion fehlgeschlagen.")) }
        catch (_: java.util.concurrent.TimeoutException) { throw VideoWorkerException("Video-Aktion dauert zu lange. Status vor erneutem Senden prüfen.") }
        finally { pending.remove(number) }
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { synchronized(outputLock) { output.write("{\"action\":\"close\"}\n"); output.flush(); output.close() } }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS)
        }
        if (Thread.currentThread() !== reader) reader.join(1000)
    }
}
