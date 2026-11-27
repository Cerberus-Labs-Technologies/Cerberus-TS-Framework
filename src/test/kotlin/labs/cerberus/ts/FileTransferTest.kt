package labs.cerberus.ts

import labs.cerberus.ts.query.*
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FileTransferTest {
    private class FileQuery(val port: Int, val size: Int, val key: String) : QueryConnection {
        override val supportsNotifications = true
        override var isOpen = true
        val requests = mutableListOf<QueryCommand>()
        override fun execute(command: QueryCommand): QueryResponse {
            requests += command
            val row = when (command.name) {
                "version" -> mapOf("version" to "6.0.0-beta14")
                "whoami" -> mapOf("client_id" to "1", "virtualserver_id" to "2")
                "ftinitdownload", "ftinitupload" -> mapOf("port" to "$port", "ftkey" to key, "ip" to "127.0.0.1", "size" to "$size", "seekpos" to "0", "proto" to "0")
                else -> emptyMap()
            }
            return QueryResponse(if (row.isEmpty()) emptyList() else listOf(row), QueryError(0, "ok"))
        }
        override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable { }
        override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable { }
        override fun close() { isOpen = false }
    }

    @Test fun `download follows advertised IP sends unescaped key and copies exact binary payload`() {
        val key = "1234567890abc/def=1234567890abcde"
        val content = byteArrayOf(0, 1, -1, 13, 10, 124, 92)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val done = CompletableFuture<Void>()
            Thread.ofPlatform().daemon().start {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2000
                        assertEquals(key, String(socket.getInputStream().readNBytes(key.length)))
                        socket.getOutputStream().write(content)
                    }
                    done.complete(null)
                } catch (e: Throwable) { done.completeExceptionally(e) }
            }
            val query = FileQuery(listener.localPort, content.size, key)
            TeamSpeakServer(ConnectionConfig(host = "unused.invalid", password = "test", minimumCommandInterval = Duration.ZERO), EventBus()) { query }.use { server ->
                server.connect()
                assertEquals(2, server.selectedServerId)
                val target = ByteArrayOutputStream()
                assertEquals(content.size.toLong(), server.download(1, "/Grüße.bin", target))
                assertContentEquals(content, target.toByteArray())
                val request = query.requests.single { it.name == "ftinitdownload" }
                assertEquals("/Grüße.bin", request.parameters["name"])
                assertEquals("0", request.parameters["proto"])
            }
            done.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun `upload transfers declared bytes and rejects oversized downloads`() {
        val key = "1234567890abc/def=1234567890abcde"
        val payload = "Kotlin äöü".toByteArray()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val received = CompletableFuture<ByteArray>()
            Thread.ofPlatform().daemon().start {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream()
                        assertEquals(key, String(input.readNBytes(key.length)))
                        received.complete(input.readNBytes(payload.size))
                    }
                } catch (e: Throwable) { received.completeExceptionally(e) }
            }
            val query = FileQuery(listener.localPort, payload.size, key)
            TeamSpeakServer(ConnectionConfig(host = "unused.invalid", password = "test"), EventBus()) { query }.use { server ->
                server.connect()
                server.upload(1, "/test.bin", payload.inputStream(), payload.size.toLong())
                assertContentEquals(payload, received.get(3, TimeUnit.SECONDS))
                assertFailsWith<IllegalArgumentException> { server.download(1, "/test.bin", ByteArrayOutputStream(), maxBytes = 1) }
                val ids = query.requests.filter { it.name.startsWith("ftinit") }.map { it.parameters["clientftfid"] }
                assertEquals(2, ids.toSet().size)
            }
        }
    }
}
