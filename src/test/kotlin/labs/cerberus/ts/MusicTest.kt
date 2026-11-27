package labs.cerberus.ts

import io.github.jaredmdobson.concentus.OpusDecoder
import labs.cerberus.ts.music.*
import labs.cerberus.ts.voice.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream
import kotlin.math.*
import kotlin.test.*

class MusicTest {
    @TempDir lateinit var directory: Path
    private fun pcm(frames: Int = 1): ByteArray = ByteArray(frames * 3840).also { bytes ->
        repeat(bytes.size / 4) { sample ->
            val value = (sin(2 * PI * 440 * sample / 48000) * 20000).toInt()
            repeat(2) { channel ->
                val offset = sample * 4 + channel * 2
                bytes[offset] = value.toByte(); bytes[offset + 1] = (value shr 8).toByte()
            }
        }
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "Condition did not become true")
    }
    private class FakeVoice : VoiceConnection {
        override var isConnected = true
        override val clientId = 42
        override var paused = false
        override val bufferedPackets = 0
        var closed = false
        var channel = 0
        override fun move(channelId: Int, password: String?) { channel = channelId }
        override fun sendOpus(packet: ByteArray) { check(isConnected); assertTrue(packet.isNotEmpty()) }
        override fun clearAudio() {}
        override fun onOpus(listener: (Int, ByteArray) -> Unit) = AutoCloseable {}
        override fun privateMessage(clientId: Int, message: String) {}
        override fun close() { closed = true; isConnected = false }
    }
    private class BlockingSource : PcmSource {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val release = CountDownLatch(1)
        override fun readFrame(): ByteArray? { entered.countDown(); release.await(); return null }
        override fun close() { closed.set(true); release.countDown() }
    }

    @Test fun `PCM encodes to decodable stereo 20ms Opus and volume zero is silent`() {
        fun rms(volume: Int): Double {
            val encoder = PcmOpusEncoder()
            val decoder = OpusDecoder(48000, 2)
            var energy = 0.0
            repeat(5) {
                val packet = encoder.encode(pcm(), volume)
                val decoded = ShortArray(1920)
                assertEquals(960, decoder.decode(packet, 0, packet.size, decoded, 0, 960, false))
                energy += decoded.sumOf { it.toDouble() * it }
            }
            return sqrt(energy / (1920 * 5))
        }
        val full = rms(100)
        assertTrue(full > 5000)
        assertTrue(rms(25) in full * 0.20..full * 0.30)
        assertTrue(rms(0) < 2)
        assertFailsWith<IllegalArgumentException> { PcmOpusEncoder().encode(pcm(), 101) }
    }

    @Test fun `skip closes active decoder advances queue and stop clears queued sources`() {
        val first = BlockingSource(); val second = BlockingSource(); val thirdOpened = AtomicBoolean()
        val voice = FakeVoice()
        MusicBot("test", voice, 2) { source, _ ->
            when (source) { "first" -> first; "second" -> second; else -> { thirdOpened.set(true); BlockingSource() } }
        }.use { bot ->
            bot.play("first")
            assertTrue(first.entered.await(5, TimeUnit.SECONDS))
            bot.enqueue("second"); bot.enqueue("third")
            assertFailsWith<IllegalArgumentException> { bot.enqueue("overflow") }
            bot.pause(); assertTrue(bot.status().paused)
            bot.resume(); assertFalse(bot.status().paused)
            bot.volume(25); assertEquals(25, bot.status().volume)
            bot.skip()
            assertTrue(second.entered.await(5, TimeUnit.SECONDS))
            assertTrue(first.closed.get())
            assertEquals("second", bot.status().current)
            bot.stop()
            eventually { bot.status().current == null }
            assertTrue(second.closed.get()); assertTrue(bot.status().queued.isEmpty()); assertFalse(thirdOpened.get())
        }
        assertTrue(voice.closed)
    }

    @Test fun `replacement cancels old playback and source errors do not kill worker`() {
        val first = BlockingSource(); val replacement = BlockingSource()
        MusicBot("test", FakeVoice(), 10) { source, _ ->
            when (source) { "first" -> first; "replacement" -> replacement; else -> error("Bad source") }
        }.use { bot ->
            bot.play("broken")
            eventually { bot.status().error == "Bad source" && bot.status().current == null }
            bot.play("first"); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
            bot.play("replacement"); assertTrue(replacement.entered.await(5, TimeUnit.SECONDS))
            assertTrue(first.closed.get()); assertNull(bot.status().error)
        }
        assertTrue(replacement.closed.get())
    }

    @Test fun `cancellation closes resources acquired after cancellation`() {
        val job = PlaybackJob("test"); job.close()
        val closed = AtomicBoolean()
        assertFails { job.own(AutoCloseable { closed.set(true) }) }
        assertTrue(closed.get())
    }

    @Test fun `resolver restricts local files to music root including symlinks`() {
        val root = directory.resolve("files"); val resolver = MediaResolver(root, MusicConfig())
        Files.write(root.resolve("song.wav"), pcm())
        assertFalse(resolver.resolve("song.wav", PlaybackJob("song")).remote)
        val outside = directory.resolve("outside.wav"); Files.write(outside, pcm())
        assertFailsWith<IllegalArgumentException> { resolver.resolve("../outside.wav", PlaybackJob("outside")) }
        Files.createSymbolicLink(root.resolve("link.wav"), outside)
        assertFailsWith<IllegalArgumentException> { resolver.resolve("link.wav", PlaybackJob("link")) }
        assertFailsWith<IllegalArgumentException> { resolver.resolve("file:///etc/passwd", PlaybackJob("bad")) }
        assertFailsWith<IllegalArgumentException> { resolver.resolve("https://user:secret@example.com/x", PlaybackJob("bad")) }
        assertEquals("https://radio.example/live", resolver.resolve("https://radio.example/live", PlaybackJob("radio")).location)
    }

    @Test fun `music service isolates bots enforces limits and closes all connections`() {
        val voices = mutableListOf<FakeVoice>(); val identities = mutableListOf<String>()
        val provider = object : VoiceProvider {
            override val id = "fake"
            override fun connect(host: String, port: Int, identity: String, nickname: String, password: String?): VoiceConnection {
                identities += identity
                return FakeVoice().also { voices += it }
            }
        }
        val music = MusicService(directory, MusicConfig(maxBots = 2), provider)
        music.create("one", "localhost", 9987, 1); music.create("two", "localhost", 9987, 2)
        assertEquals(2, identities.distinct().size)
        assertEquals(listOf(1, 2), voices.map { it.channel })
        assertFailsWith<IllegalStateException> { music.create("three", "localhost", 9987, 1) }
        music.bot("one").volume(10)
        assertEquals(50, music.bot("two").status().volume)
        music.remove("one"); assertTrue(voices[0].closed)
        music.close(); music.close(); assertTrue(voices.all { it.closed })
        assertFailsWith<IllegalStateException> { music.create("late", "localhost", 9987, 1) }
    }

    @Test fun `actual FFmpeg decodes WAV with a padded final frame`() {
        val executable = System.getenv("CERBERUS_TEST_FFMPEG")
        assumeTrue(!executable.isNullOrBlank(), "Set CERBERUS_TEST_FFMPEG for real decoder integration")
        val wav = directory.resolve("tone.wav")
        val bytes = pcm() + pcm().copyOf(40)
        AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(48000f, 16, 2, true, false), (bytes.size / 4).toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, wav.toFile())
        }
        FfmpegSource(MediaInput(wav.toString(), remote = false), MusicConfig(ffmpeg = executable!!)).use { source ->
            val first = assertNotNull(source.readFrame())
            val last = assertNotNull(source.readFrame())
            assertContentEquals(bytes.copyOfRange(0, 3840), first)
            assertTrue(last.drop(40).all { it == 0.toByte() })
            assertNull(source.readFrame())
        }
    }
    @Test fun `radio metadata and decoded audio come from a single HTTP connection`() {
        val executable = System.getenv("CERBERUS_TEST_FFMPEG")
        assumeTrue(!executable.isNullOrBlank(), "Set CERBERUS_TEST_FFMPEG for radio pipeline integration")
        val samples = pcm(frames = 3)
        val wav = ByteArrayOutputStream().use { output ->
            AudioInputStream(ByteArrayInputStream(samples), AudioFormat(48000f, 16, 2, true, false), (samples.size / 4).toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, output)
            }
            output.toByteArray()
        }
        val text = "StreamTitle='Test Artist - Test Song';StreamUrl='';".toByteArray()
        val blocks = (text.size + 15) / 16
        val encoded = ByteArrayOutputStream().use { output ->
            wav.asList().chunked(128).forEach { chunk ->
                output.write(chunk.toByteArray())
                if (chunk.size == 128) { output.write(blocks); output.write(text.copyOf(blocks * 16)) }
            }
            output.toByteArray()
        }
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/radio") { exchange ->
            requests.incrementAndGet()
            assertEquals("1", exchange.requestHeaders.getFirst("Icy-MetaData"))
            exchange.responseHeaders.add("icy-metaint", "128")
            exchange.responseHeaders.add("icy-name", "Test Radio")
            exchange.sendResponseHeaders(200, encoded.size.toLong())
            exchange.responseBody.use { it.write(encoded) }
        }
        server.start()
        try {
            val location = "http://127.0.0.1:${server.address.port}/radio"
            FfmpegSource(MediaInput(location, inspectRadio = true), MusicConfig(ffmpeg = executable!!)).use { source ->
                val output = ByteArrayOutputStream()
                while (true) { val frame = source.readFrame() ?: break; output.write(frame) }
                assertContentEquals(samples, output.toByteArray())
                assertEquals(1, requests.get())
                assertEquals(TrackMetadata("Test Song", "Test Artist", "Test Radio"), source.metadata)
            }
        } finally { server.stop(0) }
    }
}
