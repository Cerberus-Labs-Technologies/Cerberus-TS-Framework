package labs.cerberus.ts

import io.github.jaredmdobson.concentus.OpusDecoder
import labs.cerberus.ts.music.*
import labs.cerberus.ts.voice.Ts3jVoiceProvider
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Explicit opt-in: opens two real Voice slots and plays the supplied YouTube URL. */
class MusicLiveTest {
    @TempDir lateinit var directory: Path
    @Test fun `TS6 receives and decodes YouTube Opus audio with pause and resume`() {
        val host = System.getenv("CERBERUS_LIVE_HOST")
        val youtube = System.getenv("CERBERUS_LIVE_YOUTUBE")
        val ffmpeg = System.getenv("CERBERUS_TEST_FFMPEG")
        val ytDlp = System.getenv("CERBERUS_TEST_YTDLP")
        assumeTrue(listOf(host, youtube, ffmpeg, ytDlp).all { !it.isNullOrBlank() }, "Live audio test requires explicit CERBERUS_LIVE_* / CERBERUS_TEST_* opt-in")
        val port = System.getenv("CERBERUS_LIVE_VOICE_PORT")?.toInt() ?: 9987
        val channel = System.getenv("CERBERUS_LIVE_CHANNEL")?.toInt() ?: 1
        val password = System.getenv("CERBERUS_VOICE_PASSWORD")
        val provider = Ts3jVoiceProvider()
        val packets = AtomicInteger(); val energy = AtomicLong(); val invalid = AtomicInteger()
        provider.connect(host!!, port, directory.resolve("observer.ini").toString(), "Cerberus Audio Check", password).use { observer ->
            observer.move(channel)
            MusicService(directory.resolve("music"), MusicConfig(ffmpeg!!, ytDlp!!), provider).use { music ->
                val created = music.create("live", host, port, channel, "Cerberus Music Test", password)
                val bot = music.bot("live")
                val decoder = OpusDecoder(48000, 2)
                observer.onOpus { clientId, packet ->
                    if (clientId == created.clientId && packet.isNotEmpty()) {
                        try {
                            val pcm = ShortArray(11520)
                            val samples = decoder.decode(packet, 0, packet.size, pcm, 0, 5760, false)
                            if (samples != 960) invalid.incrementAndGet()
                            energy.addAndGet(pcm.take(samples * 2).sumOf { it.toLong() * it })
                            packets.incrementAndGet()
                        } catch (_: Exception) { invalid.incrementAndGet() }
                    }
                }.use {
                    fun await(condition: () -> Boolean) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
                        while (!condition() && System.nanoTime() < deadline) {
                            assertNull(bot.status().error)
                            Thread.sleep(25)
                        }
                        assertTrue(condition(), "Live audio timed out")
                    }
                    bot.volume(20); bot.play(youtube!!)
                    await { packets.get() >= 25 }
                    bot.pause(); Thread.sleep(150)
                    val before = packets.get(); Thread.sleep(250)
                    assertEquals(before, packets.get())
                    bot.resume(); await { packets.get() >= before + 25 }
                    bot.stop()
                    assertTrue(energy.get() > 0); assertEquals(0, invalid.get())
                    println("TS6 live YouTube audio: ${packets.get()} decoded stereo 48kHz/20ms packets; pause/resume passed")
                }
            }
        }
    }
}
