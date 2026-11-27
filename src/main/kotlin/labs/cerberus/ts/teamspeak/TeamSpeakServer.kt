package labs.cerberus.ts.teamspeak

import labs.cerberus.ts.event.*
import labs.cerberus.ts.query.*
import java.net.InetSocketAddress
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class Client(val id: Int, val channelId: Int, val databaseId: Int?, val uniqueId: String?,
    val nickname: String, val isQueryClient: Boolean, val properties: QueryRow) {
    companion object {
        fun from(row: QueryRow) = Client(row.getValue("clid").toInt(), row["cid"]?.toIntOrNull() ?: 0,
            row["client_database_id"]?.toIntOrNull(), row["client_unique_identifier"], row["client_nickname"].orEmpty(),
            row["client_type"] == "1", row)
    }
}
data class Channel(val id: Int, val parentId: Int, val name: String, val properties: QueryRow) {
    companion object {
        fun from(row: QueryRow) = Channel(row.getValue("cid").toInt(), row["pid"]?.toIntOrNull() ?: 0,
            row["channel_name"].orEmpty(), row)
    }
}
data class ClientIdentity(val uniqueId: String, val serverGroups: Set<Int>, val nickname: String)

class TeamSpeakServer(val config: ConnectionConfig, private val events: EventBus,
    private val connector: (ConnectionConfig) -> QueryConnection = QueryConnections::open) : AutoCloseable {
    private val connection = AtomicReference<QueryConnection?>()
    private val generation = AtomicLong()
    private val transferIds = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var clientSnapshot: Snapshot<Client>? = null
    @Volatile private var channelSnapshot: Snapshot<Channel>? = null
    @Volatile var version: QueryRow = emptyMap(); private set
    @Volatile var ownClientId: Int? = null; private set
    @Volatile var selectedServerId: Int = config.serverId; private set
    private data class Snapshot<T>(val generation: Long, val time: Long, val values: List<T>)
    val isConnected get() = connection.get()?.isOpen == true
    val supportsNotifications get() = connection.get()?.supportsNotifications == true

    @Synchronized fun connect() {
        check(connection.get() == null) { "Already connected" }
        val candidate = connector(config)
        try {
            if (candidate.supportsNotifications) {
                candidate.execute(config.voicePort?.let { command("use", "port" to it) }
                    ?: command("use", "sid" to config.serverId))
                candidate.execute(command("clientupdate", "client_nickname" to config.nickname))
            }
            version = candidate.execute(QueryCommand("version", scope = QueryScope.INSTANCE)).single()
            val who = candidate.execute(command("whoami")).single()
            ownClientId = who["client_id"]?.toIntOrNull()
            selectedServerId = who["virtualserver_id"]?.toIntOrNull() ?: config.serverId
            if (candidate.supportsNotifications) candidate.onNotification { event ->
                invalidate()
                events.publish(QueryEvent(event))
                if (event.name == "notifytextmessage") event.rows.forEach { row ->
                    val id = row["invokerid"]?.toIntOrNull() ?: return@forEach
                    if (id != ownClientId) events.publish(TextMessage(id, row["invokeruid"], row["invokername"].orEmpty(),
                        row["targetmode"]?.toIntOrNull() ?: 0, row["msg"].orEmpty()))
                }
            }
            if (candidate.supportsNotifications) {
                listOf("server", "channel", "textserver", "textchannel", "textprivate").forEach { event ->
                    candidate.execute(if (event == "channel") command("servernotifyregister", "event" to event, "id" to 0)
                        else command("servernotifyregister", "event" to event))
                }
            }
            check(candidate.isOpen) { "Disconnected during initialization" }
            connection.set(candidate)
            candidate.onDisconnect {
                if (connection.compareAndSet(candidate, null)) {
                    invalidate()
                    events.publish(ConnectionLost(it))
                }
            }
            check(connection.get() === candidate && candidate.isOpen) { "Disconnected during initialization" }
        } catch (e: Exception) { connection.compareAndSet(candidate, null); candidate.close(); throw e }
    }

    fun execute(command: QueryCommand): QueryResponse =
        (connection.get() ?: throw QueryDisconnectedException()).execute(command)

    fun clients(refresh: Boolean = false): List<Client> {
        val current = clientSnapshot
        if (!refresh && current != null && valid(current)) return current.values
        val stamp = generation.get()
        val values = execute(QueryCommand("clientlist", options = setOf("uid", "groups"))).rows.map(Client::from)
        if (stamp == generation.get()) clientSnapshot = Snapshot(stamp, System.nanoTime(), values)
        return values
    }
    fun channels(refresh: Boolean = false): List<Channel> {
        val current = channelSnapshot
        if (!refresh && current != null && valid(current)) return current.values
        val stamp = generation.get()
        val values = execute(command("channellist")).rows.map(Channel::from)
        if (stamp == generation.get()) channelSnapshot = Snapshot(stamp, System.nanoTime(), values)
        return values
    }
    private fun valid(snapshot: Snapshot<*>) = isConnected && snapshot.generation == generation.get() &&
        System.nanoTime() - snapshot.time < 30_000_000_000L
    fun invalidate() { generation.incrementAndGet(); clientSnapshot = null; channelSnapshot = null }

    /** Authorization uses fresh server data, never a cached, reusable client ID. */
    fun identity(clientId: Int): ClientIdentity {
        val row = execute(command("clientinfo", "clid" to clientId)).single()
        return ClientIdentity(row.getValue("client_unique_identifier"),
            row["client_servergroups"].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet(),
            row["client_nickname"].orEmpty())
    }

    fun privateMessage(clientId: Int, message: String) = execute(command("sendtextmessage", "targetmode" to 1, "target" to clientId, "msg" to message))
    fun channelMessage(message: String) = execute(command("sendtextmessage", "targetmode" to 2, "target" to 0, "msg" to message))
    fun serverMessage(message: String) = execute(command("sendtextmessage", "targetmode" to 3, "target" to selectedServerId, "msg" to message))
    fun poke(clientId: Int, message: String) = execute(command("clientpoke", "clid" to clientId, "msg" to message))
    fun move(clientId: Int, channelId: Int, password: String? = null): QueryResponse = execute(QueryCommand("clientmove",
        mapOf("clid" to "$clientId", "cid" to "$channelId") + (password?.let { mapOf("cpw" to it) } ?: emptyMap())))
    fun kick(clientId: Int, fromServer: Boolean = true, reason: String = "") = execute(command("clientkick", "clid" to clientId,
        "reasonid" to if (fromServer) 5 else 4, "reasonmsg" to reason))
    fun serverInfo() = execute(command("serverinfo")).single()
    fun user(databaseId: Int) = execute(command("clientdbinfo", "cldbid" to databaseId)).single()
    fun permissionId(name: String) = execute(command("permidgetbyname", "permsid" to name)).single().getValue("permid").toInt()
    fun permissionOverview(databaseId: Int, channelId: Int, permissionId: Int) = execute(command("permoverview",
        "cldbid" to databaseId, "cid" to channelId, "permid" to permissionId)).rows

    /** File negotiation requires a stream session. Byte streams are not query-escaped. */
    fun download(channelId: Int, name: String, destination: OutputStream, channelPassword: String = "", maxBytes: Long = 64L * 1024 * 1024): Long {
        require(supportsNotifications) { "File transfer requires SSH or TS3 raw query" }
        val row = execute(command("ftinitdownload", "clientftfid" to nextTransferId(), "name" to name, "cid" to channelId,
            "cpw" to channelPassword, "seekpos" to 0, "proto" to 0)).single()
        val size = row.getValue("size").toLong()
        require(size in 0..maxBytes) { "File exceeds configured limit" }
        fileSocket(row).use { socket ->
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "File transfer ended prematurely" }
                destination.write(buffer, 0, n); remaining -= n
            }
        }
        return size
    }

    fun upload(channelId: Int, name: String, source: InputStream, size: Long, channelPassword: String = "", overwrite: Boolean = false) {
        require(supportsNotifications && size >= 0) { "File transfer requires SSH or TS3 raw query and a nonnegative size" }
        val row = execute(command("ftinitupload", "clientftfid" to nextTransferId(), "name" to name, "cid" to channelId,
            "cpw" to channelPassword, "size" to size, "overwrite" to if (overwrite) 1 else 0, "resume" to 0, "proto" to 0)).single()
        require((row["seekpos"]?.toLongOrNull() ?: 0) == 0L) { "Unexpected upload offset" }
        fileSocket(row).use { socket ->
            val output = socket.getOutputStream()
            val buffer = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "Source shorter than declared file size" }
                output.write(buffer, 0, n); remaining -= n
            }
            output.flush()
        }
    }

    private fun fileSocket(row: QueryRow): Socket {
        val socket = Socket()
        try {
            val port = row.getValue("port").toInt().also { require(it in 1..65535) }
            val key = row.getValue("ftkey").also { require(it.isNotEmpty() && it.all { c -> c.code in 33..126 }) }
            val host = row["ip"]?.takeIf { it.isNotBlank() } ?: config.host
            socket.connect(InetSocketAddress(host, port), config.connectTimeout.toMillis().toInt())
            socket.soTimeout = config.commandTimeout.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            socket.getOutputStream().apply { write(key.toByteArray(Charsets.US_ASCII)); flush() }
            return socket
        } catch (e: Exception) { socket.close(); throw e }
    }

    private fun nextTransferId() = transferIds.updateAndGet { if (it >= 65535) 1 else it + 1 }

    @Synchronized override fun close() {
        val previous = connection.getAndSet(null)
        invalidate(); ownClientId = null
        previous?.close()
    }
}

data class ConnectionLost(val cause: Throwable?)
