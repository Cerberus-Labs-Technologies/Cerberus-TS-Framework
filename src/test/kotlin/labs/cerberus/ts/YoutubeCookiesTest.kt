package labs.cerberus.ts

import labs.cerberus.ts.music.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class YoutubeCookiesTest {
    @TempDir lateinit var directory: Path
    private val fixture = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t2147483647\tTEST\tfictional-session\n"
    @Test fun `cookies are copied privately isolated from rewrites and removed on close`() {
        val media = Files.createDirectory(directory.resolve("files"))
        val source = Files.writeString(directory.resolve("cookies.txt"), fixture)
        val first = YoutubeCookies.open(source, media)
        val second = YoutubeCookies.open(source, media)
        val copies = listOf(first, second).map { Path.of(it.arguments[1]) }
        try {
            assertNotEquals(copies[0], copies[1])
            copies.forEach {
                assertEquals(fixture, Files.readString(it))
                if (Files.getFileStore(it).supportsFileAttributeView("posix")) {
                    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(it)))
                }
            }
            Files.writeString(copies[0], "rewritten")
            assertEquals(fixture, Files.readString(source)); assertEquals(fixture, Files.readString(copies[1]))
        } finally { first.close(); second.close(); first.close() }
        assertTrue(copies.none(Files::exists)); assertEquals(emptyList(), YoutubeCookies.open(null, media).arguments)
    }
    @Test fun `invalid missing oversized and media reachable cookies fail safely`() {
        val media = Files.createDirectory(directory.resolve("files"))
        val invalid = Files.writeString(directory.resolve("invalid.txt"), "secret-invalid")
        val huge = Files.writeString(directory.resolve("huge.txt"), fixture + "x".repeat(2 * 1024 * 1024))
        val reachable = Files.writeString(media.resolve("hidden.mp4"), fixture)
        val link = Files.createSymbolicLink(directory.resolve("link.txt"), reachable)
        for (file in listOf(invalid, huge, reachable, link, directory.resolve("missing"), directory)) {
            val error = assertFailsWith<IllegalArgumentException> { YoutubeCookies.open(file, media) }
            assertFalse(error.message!!.contains(directory.toString())); assertFalse(error.message!!.contains("secret-invalid"))
        }
    }
    @Test fun `configuration accepts an optional cookie path from environment or properties`() {
        val properties = Files.writeString(directory.resolve("config.properties"), "guest=true\nmusic.cookies.file=config/youtube.cookies.txt\n")
        assertEquals(Path.of("config/youtube.cookies.txt"), BotConfig.load(properties, emptyMap()).music.cookiesFile)
        assertEquals(Path.of("/private/example.txt"), BotConfig.load(properties, mapOf("CERBERUS_MUSIC_COOKIES_FILE" to "/private/example.txt")).music.cookiesFile)
        assertNull(BotConfig.load(properties, mapOf("CERBERUS_MUSIC_COOKIES_FILE" to "")).music.cookiesFile)
    }
    @Test fun `audio extraction passes the private copy and cleans it after the subprocess exits`() {
        val media = Files.createDirectory(directory.resolve("files"))
        val source = Files.writeString(directory.resolve("cookies.txt"), fixture)
        val marker = directory.resolve("marker")
        val tool = Files.writeString(directory.resolve("yt-dlp-fixture"), """
            #!/bin/sh
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = --cookies ]; then
                printf '%s' "${'$'}2" > '$marker'
                printf 'rewritten' > "${'$'}2"
              fi
              shift
            done
            printf '%s\n' '{"url":"https://media.example.invalid/audio","http_headers":{},"title":"Fixture"}'
        """.trimIndent())
        assertTrue(tool.toFile().setExecutable(true))
        PlaybackJob("fixture").use { job ->
            val result = MediaResolver(media, MusicConfig(ytDlp=tool.toString(), cookiesFile=source)).resolve("https://youtu.be/fixture", job)
            assertEquals("Fixture", result.metadata.title)
        }
        val copy = Path.of(Files.readString(marker)); assertNotEquals(source, copy); assertFalse(Files.exists(copy))
        assertEquals(fixture, Files.readString(source))
    }
}
