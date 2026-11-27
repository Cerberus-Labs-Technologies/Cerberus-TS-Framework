package labs.cerberus.ts.music

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MusicConfig(val ffmpeg: String = "ffmpeg", val ytDlp: String = "yt-dlp",
    val javascriptRuntime: String = "node", val maxBots: Int = 4, val maxQueue: Int = 100) {
    init { require(ffmpeg.isNotBlank() && ytDlp.isNotBlank() && javascriptRuntime.isNotBlank()); require(maxBots in 1..32 && maxQueue in 1..1000) }
}

internal class PlaybackJob(val source: String) : AutoCloseable {
    private val cancelled = AtomicBoolean()
    private val resources = mutableListOf<AutoCloseable>()
    val isCancelled get() = cancelled.get()
    @Synchronized fun <T : AutoCloseable> own(resource: T): T {
        if (cancelled.get()) { resource.close(); error("Playback cancelled") }
        resources += resource
        return resource
    }
    override fun close() {
        val owned = synchronized(this) {
            if (!cancelled.compareAndSet(false, true)) return
            resources.asReversed().toList().also { resources.clear() }
        }
        owned.forEach { runCatching { it.close() } }
    }
}

internal class ManagedProcess(command: List<String>) : AutoCloseable {
    val process: Process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    override fun close() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        process.waitFor(2, TimeUnit.SECONDS)
    }
}

internal data class MediaInput(val location: String, val headers: Map<String, String> = emptyMap(), val remote: Boolean = true,
    val metadata: TrackMetadata = TrackMetadata(location.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }),
    val inspectRadio: Boolean = false)

internal class MediaResolver(private val root: Path, private val config: MusicConfig) {
    init { Files.createDirectories(root) }
    fun resolve(source: String, job: PlaybackJob): MediaInput {
        require(source.isNotBlank() && source.length <= 8192 && source.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Ungültige Musikquelle" }
        val uri = runCatching { URI(source) }.getOrNull()
        if (uri?.scheme?.lowercase() in setOf("http", "https")) {
            require(uri!!.host != null && uri.userInfo == null) { "Ungültige HTTP-Adresse" }
            val host = uri.host.lowercase()
            return if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")) youtube(source, job)
            else MediaInput(source, metadata = TrackMetadata(uri.host), inspectRadio = true)
        }
        require(uri?.scheme == null) { "Nur Audiodateien, HTTP-/HTTPS-Streams und YouTube-Links sind erlaubt" }
        val file = root.resolve(source).toRealPath()
        require(file.startsWith(root.toRealPath()) && Files.isRegularFile(file)) { "Datei muss im Musikverzeichnis liegen" }
        require(file.fileName.toString().substringAfterLast('.').lowercase() in setOf("mp3", "wav", "flac", "ogg", "opus", "m4a", "aac", "webm", "mp4")) { "Nicht unterstützte Audiodatei" }
        return MediaInput(file.toString(), remote = false)
    }

    private fun youtube(source: String, job: PlaybackJob): MediaInput {
        val child = job.own(ManagedProcess(listOf(config.ytDlp, "--ignore-config", "--no-playlist", "--no-warnings", "--check-formats",
            "--socket-timeout", "15", "--js-runtimes", config.javascriptRuntime,
            "--print", "%(.{url,http_headers,title,artist,uploader,thumbnail})j", "--skip-download", "-f", "bestaudio/best", "--", source)))
        val output = CompletableFuture<ByteArray>()
        Thread.ofVirtual().start {
            try { output.complete(child.process.inputStream.readNBytes(2 * 1024 * 1024 + 1)) }
            catch (e: Exception) { output.completeExceptionally(e) }
        }
        try {
            val bytes = output.get(60, TimeUnit.SECONDS)
            require(bytes.size <= 2 * 1024 * 1024) { "YouTube-Antwort zu groß" }
            check(child.process.waitFor(5, TimeUnit.SECONDS) && child.process.exitValue() == 0) { "YouTube-Link konnte nicht aufgelöst werden; yt-dlp und JavaScript-Runtime prüfen" }
            val json = ObjectMapper().readTree(bytes)
            val url = json.path("url").asText()
            val uri = URI(url)
            check(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "YouTube hat keinen direkten Audio-Stream geliefert" }
            val headers = json.path("http_headers").properties().associate { it.key to it.value.asText() }
            require(headers.size <= 30 && headers.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]+")) &&
                value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' } })
            return MediaInput(url, headers, metadata = TrackMetadata(json.path("title").asText("YouTube"),
                json.path("artist").asText().ifBlank { json.path("uploader").asText() }.takeIf(String::isNotBlank),
                artworkUrl = json.path("thumbnail").asText().takeIf(String::isNotBlank)))
        } finally { child.close() }
    }
}

internal interface PcmSource : AutoCloseable {
    fun readFrame(): ByteArray?
    val metadata: TrackMetadata? get() = null
    fun onMetadata(listener: (TrackMetadata) -> Unit): AutoCloseable { metadata?.let(listener); return AutoCloseable {} }
}

internal class FfmpegSource(input: MediaInput, config: MusicConfig) : PcmSource {
    private val currentMetadata = java.util.concurrent.atomic.AtomicReference(input.metadata)
    private val metadataListeners = java.util.concurrent.CopyOnWriteArrayList<(TrackMetadata) -> Unit>()
    private val radio = if (input.inspectRadio) runCatching { RadioFeed.open(input) { next ->
        currentMetadata.set(next); metadataListeners.forEach { it(next) }
    } }.getOrNull() else null
    private val child = try { ManagedProcess(buildList {
        addAll(listOf(config.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error"))
        if (radio == null && input.remote) addAll(listOf("-rw_timeout", "15000000", "-protocol_whitelist", "http,https,tcp,tls,crypto"))
        if (radio == null && input.headers.isNotEmpty()) addAll(listOf("-headers", input.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
        if (radio != null) addAll(listOf("-probesize", "32768", "-analyzeduration", "1000000"))
        addAll(listOf("-i", if (radio != null) "pipe:0" else input.location, "-vn", "-map", "0:a:0", "-ac", "2", "-ar", "48000", "-f", "s16le", "pipe:1"))
    }) } catch (e: Exception) { radio?.close(); throw e }
    private val feeder = radio?.let { feed -> Thread.ofPlatform().daemon().name("cerberus-radio-feed").start {
        try { child.process.outputStream.use { output -> feed.input.copyTo(output) } }
        catch (_: java.io.IOException) { runCatching { child.process.outputStream.close() } }
    } }
    override val metadata: TrackMetadata get() = currentMetadata.get()
    override fun onMetadata(listener: (TrackMetadata) -> Unit): AutoCloseable {
        metadataListeners += listener; listener(currentMetadata.get())
        return AutoCloseable { metadataListeners -= listener }
    }
    override fun readFrame(): ByteArray? {
        val bytes = child.process.inputStream.readNBytes(3840)
        if (bytes.isEmpty()) {
            check(child.process.waitFor(5, TimeUnit.SECONDS) && child.process.exitValue() == 0) { "FFmpeg konnte die Audioquelle nicht abspielen" }
            return null
        }
        check(bytes.size % 4 == 0) { "Unvollständiger PCM-Stereo-Sample" }
        return bytes.copyOf(3840) // Zero-pad the last 20 ms frame.
    }
    override fun close() {
        metadataListeners.clear()
        try { child.close() } finally { radio?.close(); feeder?.interrupt() }
    }
}
