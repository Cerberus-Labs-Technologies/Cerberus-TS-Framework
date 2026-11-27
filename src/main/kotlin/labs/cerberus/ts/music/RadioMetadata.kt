package labs.cerberus.ts.music

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class TrackMetadata(val title: String, val artist: String? = null, val station: String? = null,
    val artworkUrl: String? = null, val advertisement: Boolean = false)

internal object IcyMetadata {
    private val fields = Regex("([A-Za-z][A-Za-z0-9_-]*)='(.*?)';", RegexOption.DOT_MATCHES_ALL)
    fun text(bytes: ByteArray): String = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { bytes.toString(Charsets.ISO_8859_1) }
    fun header(value: String): String = if (value.any { it.code in 128..255 } && value.all { it.code <= 255 })
        text(value.toByteArray(Charsets.ISO_8859_1)) else value

    fun parse(value: String, current: TrackMetadata): TrackMetadata {
        val values = fields.findAll(value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val ad = values["adw_ad"].equals("true", true) || values["adw_ad"] == "1"
        if (ad) return current.copy(title = "Werbung", artist = null, artworkUrl = null, advertisement = true)
        val title = values["streamtitle"]?.takeIf { it.isNotBlank() } ?: return current
        val parts = title.split(" - ", limit = 2)
        var result = current.copy(title = if (parts.size == 2) parts[1].trim() else title.trim(),
            artist = if (parts.size == 2) parts[0].trim() else null, artworkUrl = null, advertisement = false)
        val streamUrl = values["streamurl"].orEmpty()
        if (streamUrl.startsWith('{')) runCatching {
            val json = ObjectMapper().readTree(streamUrl)
            fun field(vararg names: String) = names.firstNotNullOfOrNull { json.path(it).asText().takeIf(String::isNotBlank) }
            result = result.copy(title = field("title", "song") ?: result.title,
                artist = field("artist") ?: result.artist, artworkUrl = field("cover", "artwork", "image", "coverUrl"))
        } else if (streamUrl.matches(Regex("https?://.*\\.(?:jpg|jpeg|png|webp)(?:\\?.*)?", RegexOption.IGNORE_CASE)))
            result = result.copy(artworkUrl = streamUrl)
        values["coverurl"]?.let { result = result.copy(artworkUrl = it) }
        return result
    }
}

/** Removes ICY blocks from the same connection that supplies the audio bytes. */
internal class IcyAudioStream(private val input: InputStream, private val interval: Int,
    private val metadata: (String) -> Unit) : InputStream() {
    private var remaining = interval
    init { require(interval in 1..1024 * 1024) }
    override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.size)
        if (length == 0) return 0
        if (remaining == 0) {
            val size = input.read()
            if (size < 0) return -1
            val bytes = input.readNBytes(size * 16)
            if (bytes.size != size * 16) throw EOFException("Truncated ICY metadata")
            if (bytes.isNotEmpty()) metadata(IcyMetadata.text(bytes).trimEnd('\u0000'))
            remaining = interval
        }
        val count = input.read(buffer, offset, minOf(length, remaining))
        if (count > 0) remaining -= count
        return count
    }
    override fun close() = input.close()
}

internal class RadioFeed private constructor(private val connection: HttpURLConnection, val input: InputStream) : AutoCloseable {
    override fun close() { runCatching { input.close() }; connection.disconnect() }
    companion object {
        fun open(source: MediaInput, update: (TrackMetadata) -> Unit): RadioFeed? {
            val connection = URI(source.location).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Icy-MetaData", "1")
            connection.setRequestProperty("User-Agent", "CerberusTS/2.0")
            source.headers.forEach(connection::setRequestProperty)
            try {
                val input = connection.inputStream
                val interval = connection.getHeaderField("icy-metaint")?.toIntOrNull()
                if (interval == null || interval !in 1..1024 * 1024) { input.close(); connection.disconnect(); return null }
                var current = source.metadata.copy(station = connection.getHeaderField("icy-name")?.let(IcyMetadata::header)?.takeIf(String::isNotBlank))
                current = current.copy(title = current.station ?: current.title)
                update(current)
                return RadioFeed(connection, IcyAudioStream(input, interval) { value ->
                    val next = IcyMetadata.parse(value, current)
                    if (next != current) { current = next; update(next) }
                })
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
    }
}
