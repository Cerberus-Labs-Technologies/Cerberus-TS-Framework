package labs.cerberus.ts.query

import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One request in flight: the wire protocol has no response correlation IDs. */
class StreamQueryConnection(
    input: InputStream,
    private val output: OutputStream,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val interval: Duration = Duration.ofMillis(350),
    private val release: () -> Unit = {}
) : QueryConnection {
    private val reader = InputStreamReader(input, UTF_8)
    private val open = AtomicBoolean(true)
    private val commandLock = ReentrantLock(true)
    private val pending = AtomicReference<CompletableFuture<QueryResponse>?>()
    private val notifications = CopyOnWriteArrayList<(QueryNotification) -> Unit>()
    private val disconnects = CopyOnWriteArrayList<(Throwable?) -> Unit>()
    private val callbacks = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1024),
        Thread.ofPlatform().daemon().name("cerberus-query-events-", 0).factory())
    private var lastSent = 0L
    @Volatile private var failure: Throwable? = null
    private val readThread = Thread.ofPlatform().daemon().name("cerberus-query-reader").start(::readLoop)

    override val supportsNotifications = true
    override val isOpen get() = open.get()

    override fun execute(command: QueryCommand): QueryResponse {
        commandLock.lockInterruptibly()
        try {
            if (!isOpen) throw QueryDisconnectedException(cause = failure)
            val waitNanos = interval.toNanos() - (System.nanoTime() - lastSent)
            if (lastSent != 0L && waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos)
            if (!isOpen) throw QueryDisconnectedException(cause = failure)
            val answer = CompletableFuture<QueryResponse>()
            pending.set(answer)
            try {
                output.write((command.wire() + "\n").toByteArray(UTF_8))
                output.flush()
                lastSent = System.nanoTime()
                return answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS).checked()
            } catch (e: QueryException) {
                throw e
            } catch (e: Exception) {
                // A late response must never be assigned to a subsequent command.
                terminate(QueryDisconnectedException("Query request failed: ${command.name}", e))
                if (e is InterruptedException) Thread.currentThread().interrupt()
                throw QueryDisconnectedException("Query request failed: ${command.name}", e)
            } finally {
                pending.compareAndSet(answer, null)
            }
        } finally {
            commandLock.unlock()
        }
    }

    override fun onNotification(listener: (QueryNotification) -> Unit): AutoCloseable {
        notifications.add(listener)
        return AutoCloseable { notifications.remove(listener) }
    }

    override fun onDisconnect(listener: (Throwable?) -> Unit): AutoCloseable {
        synchronized(disconnects) {
            if (isOpen) disconnects.add(listener) else listener(failure)
        }
        return AutoCloseable { disconnects.remove(listener) }
    }

    private fun readLoop() {
        val rows = mutableListOf<QueryRow>()
        var responseSize = 0
        try {
            while (isOpen) {
                val line = readLine() ?: throw QueryDisconnectedException("Remote query endpoint closed")
                if (line.isEmpty() || line == "TS3" || line == "TS6" || line.startsWith("Welcome to the TeamSpeak ")) continue
                when {
                    line.startsWith("notify") -> {
                        val name = line.substringBefore(' ')
                        require(name.matches(Regex("notify[a-z0-9]+"))) { "Invalid notification name" }
                        val event = QueryNotification(name, QueryCodec.rows(line.substringAfter(' ', "")))
                        callbacks.execute { notifications.forEach { listener -> safely { listener(event) } } }
                    }
                    line.startsWith("error ") -> {
                        val fields = QueryCodec.rows(line.substringAfter(' ')).single()
                        val id = fields["id"]?.toIntOrNull() ?: error("Missing query error ID")
                        val response = QueryResponse(rows.toList(), QueryError(id, fields["msg"].orEmpty(), fields))
                        rows.clear()
                        responseSize = 0
                        val target = pending.getAndSet(null) ?: error("Unsolicited query response")
                        target.complete(response)
                    }
                    else -> {
                        check(pending.get() != null) { "Unsolicited query data" }
                        responseSize += line.length
                        require(responseSize <= 16 * 1024 * 1024) { "Query response exceeds 16 MiB" }
                        rows += QueryCodec.rows(line)
                    }
                }
            }
        } catch (e: Exception) {
            terminate(e)
        }
    }

    private fun readLine(): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (c == 10 || c == 13) return buffer.toString()
            require(buffer.length < 1024 * 1024) { "Query line exceeds 1 MiB" }
            buffer.append(c.toChar())
        }
    }

    private fun terminate(cause: Throwable?) {
        if (!open.compareAndSet(true, false)) return
        failure = cause
        pending.getAndSet(null)?.completeExceptionally(cause ?: QueryDisconnectedException())
        // Close the socket/SSH session before closing its reader to unblock a concurrent read.
        safely(release)
        safely { output.close() }
        safely { reader.close() }
        callbacks.shutdownNow()
        val listeners = synchronized(disconnects) { disconnects.toList().also { disconnects.clear() } }
        listeners.forEach { listener -> safely { listener(cause) } }
    }

    override fun close() = terminate(null)

    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Query callback or cleanup failed: ${e.javaClass.simpleName}")
        }
    }
}
