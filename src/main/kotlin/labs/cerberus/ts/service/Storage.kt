package labs.cerberus.ts.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** Failed or truncated JSON is fatal, never silently replaced by an empty database. */
class JsonStore(private val path: Path) {
    private val mapper = ObjectMapper()
    @Synchronized fun read(): JsonNode = if (Files.exists(path)) {
        (mapper.readTree(Files.readAllBytes(path)) ?: error("Empty JSON store: ${path.fileName}")).also {
            require(it.isObject) { "JSON store must be an object: ${path.fileName}" }
        }
    } else mapper.createObjectNode()

    @Synchronized fun write(value: Any) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".cerberus-", ".tmp")
        try {
            val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
