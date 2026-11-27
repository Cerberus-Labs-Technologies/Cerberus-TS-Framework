package labs.cerberus.ts

import labs.cerberus.ts.music.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.imageio.ImageIO
import labs.cerberus.ts.voice.VoiceConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MusicMetadataTest {
    private fun block(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val size = (bytes.size + 15) / 16
        return byteArrayOf(size.toByte()) + bytes.copyOf(size * 16)
    }
    @Test fun `ICY metadata is stripped across arbitrary read boundaries without changing audio`() {
        val audio = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val title = "StreamTitle='Björk - Winter’s song';StreamUrl='';"
        val encoded = audio.copyOfRange(0, 4) + block(title) + audio.copyOfRange(4, 8) + byteArrayOf(0) + audio.copyOfRange(8, 12)
        for (chunk in 1..7) {
            val metadata = mutableListOf<String>(); val output = ByteArrayOutputStream()
            IcyAudioStream(ByteArrayInputStream(encoded), 4, metadata::add).use { stream ->
                val buffer = ByteArray(chunk)
                while (true) { val n = stream.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            }
            assertContentEquals(audio, output.toByteArray()); assertEquals(listOf(title), metadata)
        }
    }
    @Test fun `truncated ICY block is rejected and zero length reads obey InputStream contract`() {
        val stream = IcyAudioStream(ByteArrayInputStream(byteArrayOf(42, 2, 0)), 1) {}
        assertEquals(0, stream.read(ByteArray(0)))
        assertEquals(42, stream.read())
        assertFailsWith<EOFException> { stream.read() }
    }
    @Test fun `radio titles ads unicode and cover metadata update without stale artist or image`() {
        val initial = TrackMetadata("old song", "old artist", "I♥X-MAS", "https://example.org/old.jpg")
        val ad = IcyMetadata.parse("StreamTitle='';adw_ad='true';", initial)
        assertTrue(ad.advertisement); assertEquals("Werbung", ad.title); assertNull(ad.artworkUrl); assertNull(ad.artist)
        val next = IcyMetadata.parse("StreamTitle='ANNA KENDRICK & SNOOP DOGG - WINTER WONDERLAND';StreamUrl='';", ad)
        assertEquals("WINTER WONDERLAND", next.title); assertEquals("ANNA KENDRICK & SNOOP DOGG", next.artist)
        assertEquals("I♥X-MAS", next.station); assertFalse(next.advertisement)
        val apostrophe = IcyMetadata.parse("StreamTitle='Dean Martin - Baby It's Cold Outside';", next)
        assertEquals("Baby It's Cold Outside", apostrophe.title)
        val image = IcyMetadata.parse("StreamTitle='Artist - Song';StreamUrl='https://example.org/cover.jpg?size=300';", next)
        assertEquals("https://example.org/cover.jpg?size=300", image.artworkUrl)
        assertEquals(next, IcyMetadata.parse("StreamUrl='';", next))
        assertEquals("I♥X-MAS", IcyMetadata.header("I♥X-MAS".toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)))
        assertEquals("Björk", IcyMetadata.text("Björk".toByteArray(Charsets.ISO_8859_1)))
    }
    @Test fun `description and generated avatar identify the current track and playback state`() {
        val snapshot = PresentationSnapshot(TrackMetadata("Last Christmas", "Ashley Tisdale", "I♥X-MAS"), MusicPhase.PLAYING, 25)
        val description = MusicArtwork.description(snapshot)
        assertTrue(description.contains("Last Christmas") && description.contains("Ashley Tisdale") && description.contains("I♥X-MAS"))
        assertTrue(MusicArtwork.description(snapshot.copy(phase = MusicPhase.PAUSED)).startsWith("Pausiert"))
        val first = MusicArtwork.render(snapshot, fetchArtwork = false)
        assertTrue(first.size in 1..64000)
        val image = ImageIO.read(ByteArrayInputStream(first)); assertEquals(300, image.width); assertEquals(300, image.height)
        val second = MusicArtwork.render(snapshot.copy(track = snapshot.track!!.copy(title = "Winter Wonderland")), fetchArtwork = false)
        assertFalse(first.contentEquals(second))
        assertFalse(first.contentEquals(MusicArtwork.render(snapshot.copy(phase = MusicPhase.PAUSED), fetchArtwork = false)))
        val long = snapshot.copy(track = TrackMetadata("🎄".repeat(300)))
        val limited = MusicArtwork.description(long)
        assertEquals(200, limited.codePointCount(0, limited.length)); assertFalse(limited.last().isHighSurrogate())
    }
    @Test fun `presentation follows the latest title but volume changes do not reupload the avatar`() {
        val descriptions = CopyOnWriteArrayList<String>(); val avatars = AtomicInteger()
        val voice = object : VoiceConnection {
            override val isConnected = true
            override val clientId = 1
            override var paused = false
            override val bufferedPackets = 0
            override fun move(channelId: Int, password: String?) {}
            override fun sendOpus(packet: ByteArray) {}
            override fun clearAudio() {}
            override fun onOpus(listener: (Int, ByteArray) -> Unit) = AutoCloseable {}
            override fun privateMessage(clientId: Int, message: String) {}
            override fun setDescription(description: String) { descriptions += description }
            override fun setAvatar(image: ByteArray) { assertNotNull(ImageIO.read(ByteArrayInputStream(image))); avatars.incrementAndGet() }
            override fun close() {}
        }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(condition())
        }
        MusicPresentation(voice, 25).use { presentation ->
            val first = PresentationSnapshot(TrackMetadata("First song"), MusicPhase.PLAYING, 25)
            presentation.update(first)
            await { avatars.get() == 1 }
            val second = first.copy(track = TrackMetadata("Second song"))
            presentation.update(second)
            await { avatars.get() == 2 && descriptions.last().contains("Second song") }
            presentation.update(second.copy(volume = 60))
            await { descriptions.last().contains("60 %") }
            assertEquals(2, avatars.get()); assertNull(presentation.error)
        }
    }
}
