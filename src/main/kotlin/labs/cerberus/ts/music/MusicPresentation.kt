package labs.cerberus.ts.music

import labs.cerberus.ts.voice.VoiceConnection
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

enum class MusicPhase { STOPPED, LOADING, PLAYING, PAUSED, ERROR }
internal data class PresentationSnapshot(val track: TrackMetadata?, val phase: MusicPhase, val volume: Int)

internal object MusicArtwork {
    fun description(snapshot: PresentationSnapshot): String {
        val state = when (snapshot.phase) {
            MusicPhase.STOPPED -> "Bereit"
            MusicPhase.LOADING -> "Lädt"
            MusicPhase.PLAYING -> if (snapshot.track?.advertisement == true) "Werbung" else "Spielt"
            MusicPhase.PAUSED -> "Pausiert"
            MusicPhase.ERROR -> "Wiedergabefehler"
        }
        fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}\\[\\]]"), " ").trim()
        val text = buildList {
            add("$state: ${snapshot.track?.title?.let(::clean) ?: "Cerberus Music"}")
            snapshot.track?.artist?.let { add(clean(it)) }
            snapshot.track?.station?.let { add("Radio: ${clean(it)}") }
            add("${snapshot.volume} % | Cerberus Labs")
        }.joinToString("\n")
        return text.codePoints().limit(200).toArray().let { String(it, 0, it.size) }
    }

    fun render(snapshot: PresentationSnapshot, fetchArtwork: Boolean = true): ByteArray {
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val cover = if (fetchArtwork) snapshot.track?.artworkUrl?.let { runCatching { fetch(it) }.getOrNull() } else null
            graphics.color = Color(17, 24, 39); graphics.fillRect(0, 0, 300, 300)
            if (cover != null) {
                val side = minOf(cover.width, cover.height)
                graphics.drawImage(cover, 0, 0, 300, 300, (cover.width - side) / 2, (cover.height - side) / 2,
                    (cover.width + side) / 2, (cover.height + side) / 2, null)
                graphics.color = Color(0, 0, 0, 190); graphics.fillRect(0, 130, 300, 170)
            } else {
                graphics.color = Color(16, 185, 129); graphics.fillRoundRect(22, 24, 256, 7, 7, 7)
                graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 15); graphics.color = Color(167, 243, 208)
                graphics.drawString("CERBERUS MUSIC", 22, 61)
            }
            val state = when (snapshot.phase) {
                MusicPhase.STOPPED -> "BEREIT"; MusicPhase.LOADING -> "LÄDT"; MusicPhase.PLAYING -> if (snapshot.track?.advertisement == true) "WERBUNG" else "LIVE"
                MusicPhase.PAUSED -> "PAUSIERT"; MusicPhase.ERROR -> "FEHLER"
            }
            if (cover != null) { graphics.color = Color(0, 0, 0, 190); graphics.fillRoundRect(16, 18, 115, 25, 8, 8) }
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 12); graphics.color = Color(167, 243, 208)
            graphics.drawString(state, 22, if (cover != null) 35 else 91)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 13); graphics.color = Color(167, 243, 208)
            graphics.drawString(snapshot.track?.station?.take(26) ?: state, 22, 157)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 23); graphics.color = Color.WHITE
            val title = snapshot.track?.title ?: "Cerberus Music"
            val words = title.replace(Regex("\\s+"), " ").trim().split(' ')
            val lines = mutableListOf<String>(); var line = ""
            for (word in words) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (graphics.fontMetrics.stringWidth(next) > 256 && line.isNotEmpty()) { lines += line; line = word } else line = next
            }
            if (line.isNotEmpty()) lines += line
            lines.take(3).forEachIndexed { index, text ->
                var fit = text
                while (graphics.fontMetrics.stringWidth(fit) > 256 && fit.length > 1) fit = fit.dropLast(1)
                graphics.drawString(fit, 22, 189 + index * 27)
            }
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13); graphics.color = Color(209, 213, 219)
            graphics.drawString((snapshot.track?.artist ?: "Cerberus Labs").take(37), 22, 282)
        } finally { graphics.dispose() }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "jpg", output))
            output.toByteArray().also { check(it.size <= 64000) }
        }
    }

    private fun fetch(url: String): BufferedImage {
        val uri = URI(url)
        require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        try {
            val bytes = connection.inputStream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
            require(bytes.size <= 2 * 1024 * 1024)
            ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unsupported cover image")
                try {
                    reader.input = input
                    require(reader.getWidth(0) in 1..4096 && reader.getHeight(0) in 1..4096)
                    return reader.read(0)
                } finally { reader.dispose() }
            }
        } finally { connection.disconnect() }
    }
}

/** Coalesces updates off the audio worker. A slow cover or denied avatar must never stop playback. */
internal class MusicPresentation(private val voice: VoiceConnection, private val intervalMillis: Long = 2000) : AutoCloseable {
    private val latest = AtomicReference<PresentationSnapshot?>()
    private val executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("cerberus-music-presentation").factory())
    private var lastDescription: String? = null
    private var lastAvatar: Pair<TrackMetadata?, MusicPhase>? = null
    @Volatile var error: String? = null; private set
    init { require(intervalMillis > 0); executor.scheduleWithFixedDelay(::publish, 0, intervalMillis, TimeUnit.MILLISECONDS) }
    fun update(value: PresentationSnapshot) { latest.set(value) }
    private fun publish() {
        val snapshot = latest.get() ?: return
        if (!voice.isConnected) return
        try {
            val description = MusicArtwork.description(snapshot)
            if (description != lastDescription) { voice.setDescription(description); lastDescription = description }
            val key = snapshot.track to snapshot.phase
            if (key != lastAvatar) {
                val image = MusicArtwork.render(snapshot)
                if (latest.get() != snapshot) return
                voice.setAvatar(image); lastAvatar = key
            }
            error = null
        } catch (e: Exception) {
            val detail = if (e is com.github.manevolent.ts3j.command.CommandException) "TeamSpeak-Fehler ${e.errorId}" else e.javaClass.simpleName
            if (error != detail) System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Music description/avatar update failed: $detail")
            error = detail
            // Avoid hammering a denied or unreachable file transfer endpoint.
            try { TimeUnit.SECONDS.sleep(15) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
    override fun close() { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
}
