package labs.cerberus.ts.voice

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.event.UnknownTeamspeakEvent
import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.identity.LocalIdentity
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.github.manevolent.ts3j.protocol.packet.transformation.PacketTransformation
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** TS3J's pinned revision includes the TeamSpeak 5/6 license handshake. Identity is a local INI path. */
class Ts3jVoiceProvider : VoiceProvider {
    override val id = "ts3j"
    @Synchronized override fun connect(host: String, port: Int, identity: String, nickname: String, password: String?): VoiceConnection {
        require(port in 1..65535 && nickname.length in 3..30)
        val path = Path.of(identity).toAbsolutePath()
        Files.createDirectories(path.parent)
        val local = if (Files.exists(path)) Files.newInputStream(path).use(LocalIdentity::read) else {
            LocalIdentity.generateNew(8).also { generated ->
                // Create with restrictive permissions before writing the private identity.
                val attributes = if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
                Files.createFile(path, *attributes)
                generated.save(path.toFile())
            }
        }
        val client = object : LocalTeamspeakClientSocket() {
            override fun setTransformation(transformation: PacketTransformation) {
                super.setTransformation(PacketAead(transformation))
            }
        }
        val connection = Ts3jVoiceConnection(client)
        try {
            client.identity = local
            client.nickname = nickname
            client.connect(InetSocketAddress(host, port), password, 10_000)
            connection.startAudio()
            return connection
        } catch (e: Exception) { connection.close(); throw e }
    }
}

internal class Ts3jVoiceConnection(private val client: LocalTeamspeakClientSocket) : VoiceConnection {
    private val closed = AtomicBoolean()
    private val audio = ArrayBlockingQueue<ByteArray>(20)
    private val listeners = CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()
    private val transferId = java.util.concurrent.atomic.AtomicInteger()
    private val uploads = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<Map<String, String>>>()
    override val isConnected get() = !closed.get() && client.isConnected
    override val clientId get() = client.clientId
    @Volatile override var paused = false
    override val bufferedPackets get() = audio.size
    override val supportsPresentation = true

    init {
        client.addListener(object : TS3Listener {
            override fun onUnknownEvent(event: UnknownTeamspeakEvent) {
                if (event.command !in setOf("notifystartupload", "notifyfiletransferstatus")) return
                val row = event.map
                val id = row["clientftfid"]?.toIntOrNull() ?: return
                val pending = uploads[id] ?: return
                if (event.command == "notifystartupload") pending.complete(row.toMap())
                else {
                    val status = row["status"]?.toIntOrNull() ?: return
                    if (status != 0) pending.completeExceptionally(IllegalStateException("Avatar transfer refused: $status"))
                }
            }
        })
        client.setExceptionHandler { error ->
            if (!closed.get()) System.getLogger(javaClass.name).log(System.Logger.Level.WARNING,
                "Voice transport failed: ${error.javaClass.simpleName}")
        }
        client.setVoiceHandler { packet ->
            if (packet.codecType == CodecType.OPUS_MUSIC || packet.codecType == CodecType.OPUS_VOICE)
                listeners.forEach { listener -> runCatching { listener(packet.clientId, packet.codecData.copyOf()) } }
        }
    }

    fun startAudio() {
        client.microphone = object : Microphone {
            override fun isReady() = !paused && audio.isNotEmpty()
            override fun getCodec() = CodecType.OPUS_MUSIC
            override fun provide(): ByteArray = if (paused) byteArrayOf() else audio.poll() ?: byteArrayOf()
        }
    }
    override fun move(channelId: Int, password: String?) {
        check(isConnected) { "Voice bot disconnected" }
        try { client.clientMove(clientId, channelId, password) }
        catch (e: CommandException) { if (e.errorId != 0x0302) throw e } // Already in the requested channel.
    }
    override fun sendOpus(packet: ByteArray) {
        require(packet.size in 1..1275) { "Invalid Opus frame size" }
        val copy = packet.copyOf()
        while (isConnected) if (audio.offer(copy, 100, TimeUnit.MILLISECONDS)) return
        error("Voice bot disconnected")
    }
    override fun clearAudio() = audio.clear()
    override fun onOpus(listener: (Int, ByteArray) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    override fun privateMessage(clientId: Int, message: String) = client.sendPrivateMessage(clientId, message)
    override fun setDescription(description: String) {
        require(description.codePointCount(0, description.length) <= 200)
        client.setDescription(description)
    }
    override fun setAvatar(image: ByteArray) {
        require(image.size in 1..64000) { "Avatar exceeds 64 kB" }
        val digest = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it.toInt() and 255) }
        val id = transferId.updateAndGet { if (it >= 65535) 1 else it + 1 }
        val pending = java.util.concurrent.CompletableFuture<Map<String, String>>()
        check(uploads.putIfAbsent(id, pending) == null)
        val row = try {
            val rows = command("ftinitupload", "clientftfid" to id.toString(),
                "name" to "/avatar_$digest", "cid" to "0", "cpw" to "", "size" to image.size.toString(),
                "overwrite" to "1", "resume" to "0", "proto" to "0")
            // Voice receives a correlated notification; Query would return an inline row.
            rows.firstOrNull { "ftkey" in it } ?: pending.get(10, TimeUnit.SECONDS)
        } finally { uploads.remove(id) }
        check((row["seekpos"]?.toLongOrNull() ?: 0) == 0L) { "Unexpected avatar upload offset" }
        val port = row.getValue("port").toInt().also { require(it in 1..65535) }
        val key = row.getValue("ftkey").also { require(it.isNotEmpty() && it.all { c -> c.code in 33..126 }) }
        val host = row["ip"]?.takeIf { it.isNotBlank() } ?: client.remoteSocketAddress.address.hostAddress
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().apply { write(key.toByteArray(Charsets.US_ASCII)); write(image); flush() }
            socket.shutdownOutput()
            check(socket.getInputStream().read() == -1) { "Unexpected avatar upload response" }
        }
        command("clientupdate", "client_flag_avatar" to digest)
    }
    private fun command(name: String, vararg parameters: Pair<String, String>): List<Map<String, String>> {
        check(isConnected) { "Voice bot disconnected" }
        val command = SingleCommand(name, ProtocolRole.CLIENT, *parameters.map { CommandSingleParameter(it.first, it.second) }.toTypedArray())
        return client.executeCommand(command).get(10_000).map { it.toMap() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        audio.clear(); listeners.clear()
        uploads.values.forEach { it.completeExceptionally(IllegalStateException("Voice connection closed")) }; uploads.clear()
        client.microphone = null
        try { if (client.isConnected) client.disconnect("Cerberus Music stopped") }
        finally { runCatching { client.close() } }
    }
}
