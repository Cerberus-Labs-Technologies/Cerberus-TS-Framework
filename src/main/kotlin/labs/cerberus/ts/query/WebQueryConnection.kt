package labs.cerberus.ts.query

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** POST JSON avoids putting messages and command credentials into request URLs. */
class WebQueryConnection(private val config: ConnectionConfig) : QueryConnection {
    private val client = HttpClient.newBuilder().connectTimeout(config.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = ObjectMapper()
    private val open = AtomicBoolean(true)
    private val lock = ReentrantLock()
    private val disconnects = CopyOnWriteArrayList<(Throwable?) -> Unit>()
    private var lastSent = 0L
    override val supportsNotifications = false
    override val isOpen get() = open.get()

    init { require(config.transport in setOf(QueryTransport.HTTP, QueryTransport.HTTPS)) }

    override fun execute(command: QueryCommand): QueryResponse = lock.withLock {
        check(isOpen) { "WebQuery connection closed" }
        require(command.name !in setOf("login", "logout", "use", "quit", "help", "servernotifyregister", "servernotifyunregister") && !command.name.startsWith("ft")) {
            "${command.name} requires a persistent query session; use SSH"
        }
        val prefix = when {
            command.scope == QueryScope.INSTANCE -> ""
            config.voicePort != null -> "/byport/${config.voicePort}"
            else -> "/${config.serverId}"
        }
        val uri = URI(config.transport.name.lowercase(), null, config.host, config.port,
            "$prefix/${command.name}", null, null)
        val options = command.options.associate { "-$it" to "" }
        val requestBody: Any = if (command.additionalRows.isEmpty()) command.parameters + options
            else (listOf(command.parameters) + command.additionalRows).map { it + options }
        val request = HttpRequest.newBuilder(uri).timeout(config.commandTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(requestBody)))
        config.apiKey?.let { request.header("x-api-key", it) }
        val delay = config.minimumCommandInterval.toNanos() - (System.nanoTime() - lastSent)
        if (lastSent != 0L && delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
        lastSent = System.nanoTime()
        val future = client.sendAsync(request.build(), HttpResponse.BodyHandler { LimitedBodySubscriber() })
        val response = try {
            future.get(config.commandTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw QueryDisconnectedException("WebQuery request failed: ${command.name}", e)
        }
        val content = response.body()
        val root = try { json.readTree(content) } catch (e: Exception) {
            throw QueryDisconnectedException("Invalid WebQuery JSON (HTTP ${response.statusCode()})")
        }
        val status = root?.get("status") ?: throw QueryDisconnectedException("Missing WebQuery status")
        val code = status.get("code")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
            ?: throw QueryDisconnectedException("Missing WebQuery status code")
        val details = status.properties().associate { it.key to it.value.asText() }
        val error = QueryError(code, status.path("message").asText(), details)
        if (code != 0) throw QueryException(error)
        if (response.statusCode() !in 200..299) throw QueryDisconnectedException("WebQuery HTTP ${response.statusCode()}")
        val body = root.get("body")
        val nodes = when {
            body == null || body.isNull -> emptyList()
            body.isArray -> body.toList()
            body.isObject -> listOf(body)
            else -> throw QueryDisconnectedException("Invalid WebQuery body")
        }
        QueryResponse(nodes.map { row ->
            require(row.isObject) { "Invalid WebQuery row" }
            row.properties().associate { (key, value) -> key to if (value.isNull) "" else value.asText() }
        }, error)
    }

    override fun onNotification(listener: (QueryNotification) -> Unit): AutoCloseable =
        throw UnsupportedOperationException("WebQuery has no push notifications; use SSH")

    override fun onDisconnect(listener: (Throwable?) -> Unit): AutoCloseable {
        synchronized(disconnects) { if (isOpen) disconnects.add(listener) else listener(null) }
        return AutoCloseable { disconnects.remove(listener) }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        client.shutdownNow()
        val listeners = synchronized(disconnects) { disconnects.toList().also { disconnects.clear() } }
        listeners.forEach { it(null) }
    }

    private class LimitedBodySubscriber : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(buffers: List<ByteBuffer>) {
            if (result.isDone) return
            for (buffer in buffers) {
                if (buffer.remaining() > 16 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel()
                    result.completeExceptionally(QueryDisconnectedException("WebQuery response exceeds 16 MiB"))
                    return
                }
                val part = ByteArray(buffer.remaining())
                buffer.get(part); bytes.write(part)
            }
            subscription.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}
