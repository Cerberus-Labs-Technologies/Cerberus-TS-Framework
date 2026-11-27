package labs.cerberus.ts

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.Command
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.response.CommandResponse
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.event.UnknownTeamspeakEvent
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import labs.cerberus.ts.voice.Ts3jVoiceConnection
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class VoiceAvatarTest {
    private class Client(private val port: Int, val received: AtomicBoolean, private val refuse: Boolean = false) : LocalTeamspeakClientSocket() {
        private val callbacks = mutableListOf<TS3Listener>()
        var connected = true
        var published: String? = null
        override fun isConnected() = connected
        override fun getClientId() = 7
        override fun getRemoteSocketAddress() = InetSocketAddress("127.0.0.1", 9987)
        override fun addListener(listener: TS3Listener) { callbacks += listener }
        override fun setMicrophone(microphone: Microphone?) {}
        override fun disconnect(reason: String?) { connected = false }
        override fun close() { connected = false }
        override fun executeCommand(command: Command): CommandResponse<Iterable<SingleCommand>> {
            val values = command.toMap()
            if (command.name == "ftinitupload") {
                val id = values.getValue("clientftfid")
                assertEquals("0", values["cid"]); assertEquals("0", values["proto"])
                fun emit(name: String, row: Map<String, String>) = callbacks.forEach { it.onUnknownEvent(UnknownTeamspeakEvent(name, row)) }
                emit("notifystartupload", mapOf("clientftfid" to "65534", "port" to "1", "ftkey" to "wrong-transfer"))
                if (refuse) emit("notifyfiletransferstatus", mapOf("clientftfid" to id, "status" to "2565"))
                else emit("notifystartupload", mapOf("clientftfid" to id, "port" to port.toString(), "ftkey" to "test-key", "seekpos" to "0"))
            } else if (command.name == "clientupdate") {
                assertTrue(received.get(), "Avatar flag must be set after the complete file transfer")
                published = values["client_flag_avatar"]
            }
            return object : CommandResponse<Iterable<SingleCommand>> {
                override fun getCommand() = command
                override fun get(): Iterable<SingleCommand> = emptyList()
                override fun get(timeout: Long): Iterable<SingleCommand> = emptyList()
            }
        }
    }
    @Test fun `avatar awaits its correlated notification transfers bytes and publishes MD5 only after EOF`() {
        val image = ByteArray(100) { it.toByte() }; val received = AtomicBoolean()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
            val bytes = CompletableFuture<ByteArray>()
            Thread.ofPlatform().daemon().start {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5000
                        val content = socket.getInputStream().readNBytes("test-key".length + image.size)
                        received.set(true); bytes.complete(content)
                    }
                } catch (e: Exception) { bytes.completeExceptionally(e) }
            }
            val client = Client(listener.localPort, received)
            Ts3jVoiceConnection(client).use { voice -> voice.setAvatar(image) }
            assertContentEquals("test-key".toByteArray() + image, bytes.get(5, TimeUnit.SECONDS))
            val digest = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(digest, client.published)
        }
    }
    @Test fun `asynchronous avatar refusal never publishes an avatar flag`() {
        val client = Client(1, AtomicBoolean(), refuse = true)
        Ts3jVoiceConnection(client).use { voice ->
            val error = assertFailsWith<ExecutionException> { voice.setAvatar(byteArrayOf(1)) }
            assertTrue(error.cause!!.message!!.contains("2565")); assertNull(client.published)
        }
    }
}
