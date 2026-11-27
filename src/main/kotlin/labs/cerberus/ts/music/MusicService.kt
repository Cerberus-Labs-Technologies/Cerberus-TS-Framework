package labs.cerberus.ts.music

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import labs.cerberus.ts.voice.VoiceConnection
import labs.cerberus.ts.voice.VoiceProvider
import labs.cerberus.ts.voice.Ts3jVoiceProvider
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class MusicStatus(val id: String, val clientId: Int, val connected: Boolean, val current: String?,
    val queued: List<String>, val paused: Boolean, val volume: Int, val error: String?,
    val metadata: TrackMetadata? = null, val phase: MusicPhase = MusicPhase.STOPPED, val presentationError: String? = null)

/** Each bot owns a persistent identity, independent Voice connection, queue and decoder worker. */
class MusicService(private val directory: Path, private val config: MusicConfig = MusicConfig(),
    private val provider: VoiceProvider = Ts3jVoiceProvider()) : AutoCloseable {
    val video = VideoService(directory, config)
    private val bots = linkedMapOf<String, MusicBot>()
    private var closed = false
    @Synchronized fun create(id: String, host: String, port: Int, channelId: Int,
        nickname: String = "Cerberus Music $id".take(30), serverPassword: String? = null, channelPassword: String? = null): MusicStatus {
        check(!closed) { "Music service closed" }
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,19}"))) { "Bot-ID: Kleinbuchstaben, Ziffern, _ oder -, maximal 20 Zeichen" }
        require(channelId > 0)
        check(id !in bots) { "Bot existiert bereits" }
        check(bots.size < config.maxBots) { "Maximale Bot-Anzahl erreicht" }
        val connection = provider.connect(host, port, directory.resolve("identities/$id.ini").toString(), nickname, serverPassword)
        try {
            connection.move(channelId, channelPassword)
            val resolver = MediaResolver(directory.resolve("files"), config)
            val bot = MusicBot(id, connection, config.maxQueue) { source, job -> FfmpegSource(resolver.resolve(source, job), config) }
            bots[id] = bot
            return bot.status()
        } catch (e: Exception) { connection.close(); throw e }
    }
    @Synchronized fun bot(id: String): MusicBot = bots[id] ?: throw IllegalArgumentException("Unbekannter Musik-Bot: $id")
    @Synchronized fun list(): List<MusicStatus> = bots.values.map { it.status() }
    fun remove(id: String) { val bot = synchronized(this) { bots.remove(id) } ?: throw IllegalArgumentException("Unbekannter Musik-Bot: $id"); bot.close() }
    override fun close() {
        val owned = synchronized(this) { if (closed) return; closed = true; bots.values.toList().also { bots.clear() } }
        owned.forEach { runCatching { it.close() } }
        video.close()
    }
}

internal class PcmOpusEncoder {
    private val encoder = OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_AUDIO).apply { bitrate = 128000; complexity = 5 }
    fun encode(frame: ByteArray, volume: Int): ByteArray {
        require(frame.size == 3840 && volume in 0..100)
        val pcm = ShortArray(1920) { index ->
            val sample = ((frame[index * 2].toInt() and 255) or (frame[index * 2 + 1].toInt() shl 8)).toShort().toInt()
            (sample * volume / 100).toShort()
        }
        val output = ByteArray(1275)
        return output.copyOf(encoder.encode(pcm, 0, 960, output, 0, output.size))
    }
}

class MusicBot internal constructor(val id: String, private val voice: VoiceConnection, private val maxQueue: Int,
    private val open: (String, PlaybackJob) -> PcmSource) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val queue = ArrayDeque<String>()
    private var active: PlaybackJob? = null
    private var closed = false
    private val presentation = if (voice.supportsPresentation) MusicPresentation(voice) else null
    private var metadata: TrackMetadata? = null
    private var phase = MusicPhase.STOPPED
    @Volatile private var volume = 50
    @Volatile private var lastError: String? = null
    private val worker = Thread.ofPlatform().daemon().name("cerberus-music-$id").start(::work)
    init { present() }

    private fun effectivePhase() = if (voice.paused && phase == MusicPhase.PLAYING) MusicPhase.PAUSED else phase
    private fun present() = presentation?.update(PresentationSnapshot(metadata, effectivePhase(), volume))

    fun play(source: String) {
        requireSource(source)
        synchronized(lock) {
            check(!closed)
            queue.clear(); active?.close(); voice.clearAudio(); voice.paused = false
            queue.addLast(source); lastError = null; lock.notifyAll()
        }
    }
    fun enqueue(source: String) {
        requireSource(source)
        synchronized(lock) { check(!closed); require(queue.size < maxQueue) { "Warteschlange voll" }; queue.addLast(source); lock.notifyAll() }
    }
    private fun requireSource(source: String) { require(source.isNotBlank() && source.length <= 8192) { "Musikquelle fehlt oder ist zu lang" } }
    fun pause() { synchronized(lock) { check(!closed); voice.paused = true; present() } }
    fun resume() { synchronized(lock) { check(!closed); voice.paused = false; present() } }
    fun volume(percent: Int) { require(percent in 0..100) { "Lautstärke muss zwischen 0 und 100 liegen" }; synchronized(lock) { volume = percent; present() } }
    fun move(channelId: Int, password: String? = null) { require(channelId > 0); voice.move(channelId, password) }
    fun skip() { synchronized(lock) { active?.close(); voice.clearAudio(); voice.paused = false; metadata = null; phase = MusicPhase.STOPPED; present(); lock.notifyAll() } }
    fun stop() { synchronized(lock) { queue.clear(); active?.close(); voice.clearAudio(); voice.paused = false; metadata = null; phase = MusicPhase.STOPPED; present(); lock.notifyAll() } }
    fun status(): MusicStatus = synchronized(lock) {
        MusicStatus(id, voice.clientId, voice.isConnected, active?.source, queue.toList(), voice.paused, volume, lastError,
            metadata, effectivePhase(), presentation?.error)
    }

    private fun work() {
        try {
            while (true) {
                val job = synchronized(lock) {
                    while (!closed && queue.isEmpty()) lock.wait()
                    if (closed) return
                    PlaybackJob(queue.removeFirst()).also {
                        active = it; lastError = null; metadata = TrackMetadata("Audio wird geladen"); phase = MusicPhase.LOADING; present()
                    }
                }
                try {
                    val source = job.own(open(job.source, job))
                    job.own(source.onMetadata { info -> synchronized(lock) {
                        if (active === job && !job.isCancelled) { metadata = source.metadata ?: info; present() }
                    } })
                    val encoder = PcmOpusEncoder()
                    while (!job.isCancelled && voice.isConnected) {
                        val frame = source.readFrame() ?: break
                        if (!job.isCancelled) {
                            synchronized(lock) { if (active === job && phase == MusicPhase.LOADING) { phase = MusicPhase.PLAYING; present() } }
                            voice.sendOpus(encoder.encode(frame, volume))
                        }
                    }
                    while (!job.isCancelled && voice.isConnected && voice.bufferedPackets > 0) TimeUnit.MILLISECONDS.sleep(10)
                    check(job.isCancelled || voice.isConnected) { "Voice-Verbindung getrennt; Bot neu verbinden" }
                } catch (e: Exception) {
                    if (!job.isCancelled) {
                        lastError = when (e) {
                            is java.io.IOException -> "Audio-Werkzeug oder Quelldatei nicht verfügbar"
                            is java.util.concurrent.TimeoutException -> "Zeitüberschreitung beim Laden der Audioquelle"
                            else -> e.message?.takeIf { it.length <= 200 } ?: e.javaClass.simpleName
                        }
                        System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Music bot $id: ${e.javaClass.simpleName}")
                        synchronized(lock) { phase = MusicPhase.ERROR; present() }
                    }
                } finally {
                    job.close(); voice.clearAudio()
                    synchronized(lock) { if (active === job) {
                        active = null
                        if (phase != MusicPhase.ERROR) { phase = MusicPhase.STOPPED; metadata = null }
                        present()
                    } }
                }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; queue.clear(); active?.close(); voice.clearAudio(); voice.paused = false; lock.notifyAll() }
        worker.interrupt()
        try { presentation?.close() } finally {
            try { voice.close() } finally { if (Thread.currentThread() !== worker) worker.join(5000) }
        }
    }
}
