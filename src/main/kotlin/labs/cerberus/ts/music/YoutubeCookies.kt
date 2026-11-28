package labs.cerberus.ts.music

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/** yt-dlp rewrites its jar. A private copy isolates concurrent lookups and preserves the uploaded original. */
internal class YoutubeCookies private constructor(private val directory: Path?) : AutoCloseable {
    val arguments = directory?.let { listOf("--cookies", it.resolve("cookies.txt").toString()) } ?: emptyList()
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true) && directory != null) {
            Files.deleteIfExists(directory.resolve("cookies.txt")); Files.deleteIfExists(directory)
        }
    }
    companion object {
        fun open(source: Path?, mediaRoot: Path): YoutubeCookies {
            if (source == null) return YoutubeCookies(null)
            var directory: Path? = null
            try {
                val file = source.toRealPath()
                require(!file.startsWith(mediaRoot.toRealPath()) && Files.isRegularFile(file) && Files.size(file) <= 2 * 1024 * 1024)
                val bytes = Files.newInputStream(file).use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024 && bytes.toString(Charsets.UTF_8).lineSequence().first() in
                    setOf("# Netscape HTTP Cookie File", "# HTTP Cookie File"))
                val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                directory = if (posix) Files.createTempDirectory("cerberus-youtube-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                    else Files.createTempDirectory("cerberus-youtube-")
                val target = directory.resolve("cookies.txt")
                if (posix) Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                else Files.createFile(target)
                Files.write(target, bytes, StandardOpenOption.WRITE)
                return YoutubeCookies(directory)
            } catch (_: Exception) {
                directory?.let { runCatching { YoutubeCookies(it).close() } }
                throw IllegalArgumentException("YouTube-Cookie-Datei ungültig oder nicht lesbar. Netscape-Format und music.cookies.file prüfen; außerhalb des Medienverzeichnisses ablegen.")
            }
        }
    }
}
