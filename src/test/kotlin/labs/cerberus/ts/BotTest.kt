package labs.cerberus.ts

import labs.cerberus.ts.event.*
import labs.cerberus.ts.query.*
import labs.cerberus.ts.teamspeak.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class FakeQuery : QueryConnection {
    override val supportsNotifications = false
    @Volatile override var isOpen = true
    val requests = CopyOnWriteArrayList<QueryCommand>()
    private val disconnects = CopyOnWriteArrayList<(Throwable?) -> Unit>()
    override fun execute(command: QueryCommand): QueryResponse {
        check(isOpen)
        requests += command
        return QueryResponse(when (command.name) {
            "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
            "whoami" -> listOf(mapOf("client_id" to "1"))
            "clientlist" -> listOf(mapOf("clid" to "2", "cid" to "3", "client_nickname" to "Alice", "client_unique_identifier" to "uid"))
            else -> emptyList()
        }, QueryError(0, "ok"))
    }
    override fun onNotification(listener: (QueryNotification) -> Unit): AutoCloseable = error("No WebQuery events")
    override fun onDisconnect(listener: (Throwable?) -> Unit): AutoCloseable {
        disconnects += listener
        return AutoCloseable { disconnects -= listener }
    }
    fun drop() { isOpen = false; disconnects.forEach { it(QueryDisconnectedException()) } }
    override fun close() { isOpen = false }
}

class BotTest {
    @TempDir lateinit var directory: Path
    private fun config(attempts: Int = 2) = BotConfig(ConnectionConfig(password = "test"), directory.resolve("data"),
        directory.resolve("plugins"), reconnectAttempts = attempts, reconnectDelay = Duration.ofMillis(10), keepAlive = Duration.ofHours(1))

    @Test fun `bot recovers from initial failure and resubscribes after disconnect`() {
        val attempts = AtomicInteger()
        val connections = CopyOnWriteArrayList<FakeQuery>()
        CerberusBot(config()) {
            if (attempts.incrementAndGet() == 1) throw QueryDisconnectedException()
            FakeQuery().also { connections += it }
        }.use { bot ->
            val connected = CountDownLatch(1)
            val reconnected = CountDownLatch(2)
            bot.events.subscribe<StateChanged> { if (it.current == BotState.CONNECTED) { connected.countDown(); reconnected.countDown() } }
            bot.start()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            connections.first().drop()
            assertTrue(reconnected.await(5, TimeUnit.SECONDS))
            assertEquals(3, attempts.get())
            assertEquals(BotState.CONNECTED, bot.state)
        }
        assertTrue(connections.all { !it.isOpen })
    }

    @Test fun `zero reconnect attempts stops after first failed connection`() {
        val attempts = AtomicInteger()
        CerberusBot(config(0)) { attempts.incrementAndGet(); throw QueryDisconnectedException() }.use { bot ->
            bot.start()
            val stopped = CountDownLatch(1)
            Thread.ofPlatform().daemon().start { bot.awaitShutdown(); stopped.countDown() }
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertEquals(1, attempts.get())
            assertTrue(bot.failed)
            assertEquals(BotState.STOPPED, bot.state)
        }
    }

    @Test fun `query cache invalidates on disconnect and manual refresh`() {
        val fake = FakeQuery()
        TeamSpeakServer(ConnectionConfig(password = "test"), EventBus()) { fake }.use { server ->
            server.connect()
            assertEquals("Alice", server.clients().single().nickname)
            server.clients()
            assertEquals(1, fake.requests.count { it.name == "clientlist" })
            server.clients(refresh = true)
            assertEquals(2, fake.requests.count { it.name == "clientlist" })
            fake.drop()
            assertFailsWith<QueryDisconnectedException> { server.clients() }
        }
    }

    @Test fun `environment overrides configuration and secrets are not interpolated into output`() {
        val config = BotConfig.load(directory.resolve("missing.properties"), mapOf(
            "CERBERUS_TRANSPORT" to "HTTPS", "CERBERUS_API_KEY" to "never-log-this", "CERBERUS_SERVER_ID" to "4"))
        assertEquals(QueryTransport.HTTPS, config.connection.transport)
        assertEquals(10443, config.connection.port)
        assertEquals(4, config.connection.serverId)
        assertFalse(config.connection.toString().contains("never-log-this"))
    }
}
