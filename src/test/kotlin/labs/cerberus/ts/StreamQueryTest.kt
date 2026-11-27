package labs.cerberus.ts

import labs.cerberus.ts.query.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.*
import kotlin.test.*

class WireFixture(private val script: (Socket) -> Unit) : AutoCloseable {
    private val listener = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val port get() = listener.localPort
    val completion = CompletableFuture<Void>()
    private val thread = Thread.ofPlatform().daemon().start {
        try { listener.accept().use { it.soTimeout = 5000; script(it) }; completion.complete(null) }
        catch (e: Throwable) { completion.completeExceptionally(e) }
    }
    fun connect(timeout: Duration = Duration.ofSeconds(2)): StreamQueryConnection {
        val socket = Socket("localhost", port)
        return StreamQueryConnection(socket.getInputStream(), socket.getOutputStream(), timeout, Duration.ZERO, socket::close)
    }
    override fun close() { listener.close(); thread.interrupt() }
}

class StreamQueryTest {
    @Test fun `LFCR greeting notifications and multiline answers are demultiplexed`() {
        WireFixture { socket ->
            val out = socket.getOutputStream()
            out.write("TS6\n\rWelcome to the TeamSpeak 6 ServerQuery interface\n\r".toByteArray()); out.flush()
            assertEquals("clientlist", socket.getInputStream().bufferedReader().readLine())
            out.write("clid=1 name=Käthe\\sMüller\n\rnotifytextmessage invokerid=2 msg=Hallo\\pWelt\n\rclid=2 name=Bob\n\rerror id=0 msg=ok\n\r".toByteArray()); out.flush()
            Thread.sleep(100)
        }.use { fixture ->
            fixture.connect().use { connection ->
                val event = CompletableFuture<QueryNotification>()
                connection.onNotification { event.complete(it) }
                val rows = connection.execute(command("clientlist")).rows
                assertEquals(listOf("1", "2"), rows.map { it["clid"] })
                assertEquals("Käthe Müller", rows[0]["name"])
                assertEquals("Hallo|Welt", event.get(2, TimeUnit.SECONDS).rows[0]["msg"])
            }
            fixture.completion.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun `server errors keep details and leave stream usable`() {
        WireFixture { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            assertEquals("hostinfo", reader.readLine())
            out.write("error id=2568 msg=insufficient\\sclient\\spermissions failed_permid=13\n".toByteArray()); out.flush()
            assertEquals("version", reader.readLine())
            out.write("version=6.0.0\nerror id=0 msg=ok\n".toByteArray()); out.flush()
        }.use { fixture -> fixture.connect().use { connection ->
            val failure = assertFailsWith<QueryException> { connection.execute(command("hostinfo")) }
            assertEquals("13", failure.error.details["failed_permid"])
            assertEquals("6.0.0", connection.execute(command("version")).single()["version"])
        }; fixture.completion.get(3, TimeUnit.SECONDS) }
    }

    @Test fun `concurrent callers receive their own responses`() {
        WireFixture { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            repeat(20) {
                val request = reader.readLine()
                val id = request.substringAfter("id=")
                out.write("id=$id\nerror id=0 msg=ok\n".toByteArray()); out.flush()
            }
        }.use { fixture -> fixture.connect().use { connection ->
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures = (1..20).map { id -> executor.submit<String> { connection.execute(command("test", "id" to id)).single().getValue("id") } }
                assertEquals((1..20).map(Int::toString), futures.map { it.get(5, TimeUnit.SECONDS) })
            }
        }; fixture.completion.get(3, TimeUnit.SECONDS) }
    }

    @Test fun `timeout closes connection to prevent late response reuse`() {
        WireFixture { socket -> socket.getInputStream().bufferedReader().readLine(); Thread.sleep(300) }.use { fixture ->
            fixture.connect(Duration.ofMillis(50)).use { connection ->
                assertFailsWith<QueryDisconnectedException> { connection.execute(command("slow")) }
                assertFalse(connection.isOpen)
                assertFailsWith<QueryDisconnectedException> { connection.execute(command("next")) }
            }
        }
    }

    @Test fun `EOF completes waiting request and disconnect callback`() {
        WireFixture { socket -> socket.getInputStream().bufferedReader().readLine() }.use { fixture ->
            fixture.connect().use { connection ->
                val disconnected = CountDownLatch(1)
                connection.onDisconnect { disconnected.countDown() }
                assertFailsWith<QueryDisconnectedException> { connection.execute(command("version")) }
                assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `event listeners can issue queries without blocking reader`() {
        WireFixture { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            reader.readLine()
            out.write("notifycliententerview clid=2\nerror id=0 msg=ok\n".toByteArray()); out.flush()
            assertEquals("clientinfo clid=2", reader.readLine())
            out.write("client_nickname=Alice\nerror id=0 msg=ok\n".toByteArray()); out.flush()
            Thread.sleep(100)
        }.use { fixture -> fixture.connect().use { connection ->
            val result = CompletableFuture<String>()
            connection.onNotification { result.complete(connection.execute(command("clientinfo", "clid" to 2)).single().getValue("client_nickname")) }
            connection.execute(command("version"))
            assertEquals("Alice", result.get(3, TimeUnit.SECONDS))
        }; fixture.completion.get(3, TimeUnit.SECONDS) }
    }
}
