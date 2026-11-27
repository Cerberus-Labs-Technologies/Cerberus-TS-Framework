package labs.cerberus.ts.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import labs.cerberus.ts.rest.ControllerBindings
import labs.cerberus.ts.rest.RestException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

data class RestRequest @JvmOverloads constructor(val method: String, val path: String, val query: String?, val body: ByteArray,
    val pathParameters: Map<String, String> = emptyMap(), val headers: Map<String, List<String>> = emptyMap()) {
    val queryParameters: Map<String, List<String>> by lazy {
        try {
            query?.takeIf(String::isNotEmpty)?.split('&')?.groupBy({ URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) },
                { URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }).orEmpty()
        } catch (_: IllegalArgumentException) { throw RestException(400, "Invalid query encoding") }
    }
}
data class RestResponse @JvmOverloads constructor(val status: Int = 200, val body: Any? = emptyMap<String, String>(),
    val headers: Map<String, String> = emptyMap(), val contentType: String = "application/json; charset=utf-8") {
    init {
        require(status in 100..599)
        (headers + ("Content-Type" to contentType)).forEach { (name, value) ->
            require(name.matches(Regex("[A-Za-z0-9-]+")) && name.lowercase() !in setOf("content-length", "transfer-encoding", "connection")) { "Invalid HTTP response header" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP response header value" }
        }
    }
}

class RestService(private val tokens: TokenService, database: DatabaseService? = null) : AutoCloseable {
    private val invocation = MethodInvocation(database)
    private data class Route(val method: String, val path: String, val segments: List<String>, val key: String,
        val secured: Boolean, val action: (RestRequest) -> RestResponse, val active: AtomicBoolean = AtomicBoolean(true)) {
        val specificity = segments.count { !it.startsWith(':') }
        fun match(candidate: String): Map<String, String>? {
            val parts = candidate.split('/')
            if (parts.size != segments.size) return null
            val values = linkedMapOf<String, String>()
            segments.zip(parts).forEach { (pattern, value) ->
                if (pattern.startsWith(':')) { if (value.isEmpty()) return null; values[pattern.drop(1)] = value }
                else if (pattern != value) return null
            }
            return values
        }
    }
    @Volatile private var routes: List<Route> = emptyList()
    private val mapper = ObjectMapper().registerKotlinModule().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val concurrency = Semaphore(64)
    @Volatile private var http: HttpServer? = null
    private var closed = false
    val port: Int? get() = http?.address?.port

    fun route(method: String, path: String, action: (RestRequest) -> RestResponse): AutoCloseable =
        register(listOf(prepare(method, path, true, action)))
    /** Public endpoints are explicit; existing route() calls always require a token. */
    fun publicRoute(method: String, path: String, action: (RestRequest) -> RestResponse): AutoCloseable =
        register(listOf(prepare(method, path, false, action)))
    fun registerController(controller: Any): AutoCloseable = registerController(controller, "")
    fun registerController(controller: Any, prefix: String): AutoCloseable = register(ControllerBindings.routes(controller, mapper, invocation).map {
        prepare(it.method, prefix + it.path, it.secured, it.handler)
    })
    private fun prepare(method: String, path: String, secured: Boolean, action: (RestRequest) -> RestResponse): Route {
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS") && TokenService.validPath(path))
        require('?' !in path && '#' !in path)
        val segments = path.split('/').map { if (it.startsWith('{') && it.endsWith('}')) ":${it.substring(1, it.length - 1)}" else it }
        val names = segments.filter { it.startsWith(':') }.map { it.drop(1) }
        require(names.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) } && names.distinct().size == names.size) { "Invalid or duplicate path parameter" }
        val shape = segments.joinToString("/") { if (it.startsWith(':')) ":" else it }
        return Route(method, path, segments, "$method $shape", secured, action)
    }
    @Synchronized private fun register(entries: List<Route>): AutoCloseable {
        check(!closed) { "REST service is closed" }
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate controller route" }
        check(routes.none { existing -> entries.any { it.key == existing.key } }) { "Duplicate REST route" }
        val combined = routes + entries
        combined.forEachIndexed { index, route -> combined.drop(index + 1).forEach { other ->
            require(route.method != other.method || route.specificity != other.specificity || route.segments.size != other.segments.size ||
                route.segments.zip(other.segments).any { (a, b) -> !a.startsWith(':') && !b.startsWith(':') && a != b }) { "Ambiguous REST routes" }
        } }
        routes = (routes + entries).sortedWith(compareByDescending<Route> { it.specificity }.thenBy { it.key })
        return AutoCloseable { synchronized(this) {
            entries.forEach { it.active.set(false) }; routes = routes.filterNot { route -> entries.any { it === route } }
        } }
    }

    @Synchronized fun start(address: InetSocketAddress = InetSocketAddress("127.0.0.1", 8080)) {
        check(!closed && http == null)
        val server = HttpServer.create(address, 32)
        server.executor = executor
        server.createContext("/") { exchange ->
            val accepted = concurrency.tryAcquire()
            try {
                val path = exchange.requestURI.path
                val route = routes.firstOrNull { it.method == exchange.requestMethod && it.match(path) != null }
                val response = try {
                    when {
                        !accepted -> RestResponse(503, mapOf("error" to "Busy"))
                        !TokenService.validPath(path) || Regex("%(?:2f|5c|00)", RegexOption.IGNORE_CASE).containsMatchIn(exchange.requestURI.rawPath) -> RestResponse(400, mapOf("error" to "Invalid path"))
                        (route?.secured != false) && !tokens.verify(exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ").orEmpty(), exchange.requestMethod, path) ->
                            RestResponse(401, mapOf("error" to "Unauthorized"))
                        route == null || !route.active.get() -> RestResponse(404, mapOf("error" to "Not found"))
                        else -> {
                            val body = exchange.requestBody.readNBytes(1024 * 1024 + 1)
                            if (body.size > 1024 * 1024) RestResponse(413, mapOf("error" to "Body too large"))
                            else route.action(RestRequest(exchange.requestMethod, path, exchange.requestURI.rawQuery, body,
                                route.match(path).orEmpty(), exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }))
                        }
                    }
                } catch (e: RestException) { RestResponse(e.status, mapOf("error" to e.message)) }
                catch (_: Exception) { RestResponse(500, mapOf("error" to "Internal server error")) }
                val contentType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: response.contentType
                val bytes = when {
                    response.body is ByteArray -> response.body
                    contentType.substringBefore(';').equals("application/json", true) -> mapper.writeValueAsBytes(response.body)
                    response.body == null -> byteArrayOf()
                    else -> response.body.toString().toByteArray(Charsets.UTF_8)
                }
                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                response.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                val noBody = response.status in setOf(204, 304) || exchange.requestMethod == "HEAD"
                exchange.sendResponseHeaders(response.status, if (noBody || bytes.isEmpty()) -1 else bytes.size.toLong())
                if (!noBody && bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            } catch (_: Exception) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally { exchange.close(); if (accepted) concurrency.release() }
        }
        try { server.start(); http = server } catch (e: Exception) { server.stop(0); throw e }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; http?.stop(0); http = null; executor.shutdownNow()
        routes.forEach { it.active.set(false) }; routes = emptyList()
    }
}
