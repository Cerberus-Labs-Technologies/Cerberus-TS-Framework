package labs.cerberus.ts.web

import labs.cerberus.ts.service.RestResponse
import labs.cerberus.ts.service.RestService
import labs.cerberus.ts.plugin.ResourceScope
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

data class WebAsset(val bytes: ByteArray, val contentType: String)
data class WebPage(val id: String, val title: String, val description: String = "", val module: String,
    val stylesheet: String? = null, val order: Int = 100)
data class PluginWebPage(val plugin: String, val pluginName: String, val id: String, val title: String,
    val description: String, val module: String, val stylesheet: String?, val order: Int)

/** Plugin UI bundles are explicit allowlists, served locally; no external script URL is accepted. */
class WebInterfaceService(private val rest: RestService, bundle: Map<String, WebAsset> = bundledAssets()) : AutoCloseable {
    private val scope = ResourceScope()
    private val registrations = linkedMapOf<String, Pair<PluginWebPage, ResourceScope>>()
    private val closed = AtomicBoolean()
    init {
        require(bundle.keys.all(::safePath)) { "Invalid built web asset path" }
        try {
        val index = bundle["index.html"]
        scope.own(rest.publicRoute("GET", "/") {
            if (index == null) RestResponse(503, mapOf("error" to "Web interface assets have not been built"))
            else assetResponse(index)
        })
        bundle.filterKeys { it != "index.html" }.forEach { (path, asset) ->
            require(safePath(path)) { "Invalid built web asset path" }
            scope.own(rest.publicRoute("GET", "/$path") { assetResponse(asset) })
        }
        scope.own(rest.route("GET", "/api/web/pages") { RestResponse(body = pages()) })
        } catch (error: Throwable) {
            try { scope.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    @Synchronized fun pages(): List<PluginWebPage> = registrations.values.map { it.first }.sortedWith(compareBy<PluginWebPage> { it.order }.thenBy { it.plugin }.thenBy { it.id })

    @Synchronized fun register(pluginId: String, pluginName: String, page: WebPage, assets: Map<String, String>,
        resource: (String) -> InputStream?): AutoCloseable {
        check(!closed.get()) { "Web interface is closed" }
        require(pluginId.matches(Regex("[a-z][a-z0-9_.-]*")) && page.id.matches(Regex("[a-z][a-z0-9-]*")))
        require(page.title.isNotBlank() && page.title.length <= 100 && page.description.length <= 500)
        require(page.module in assets && (page.module.endsWith(".js") || page.module.endsWith(".mjs")) && (page.stylesheet == null || page.stylesheet in assets && page.stylesheet.endsWith(".css")))
        require(assets.isNotEmpty() && assets.size <= 128 && assets.keys.all { safePath(it) })
        val key = "$pluginId/${page.id}"
        check(key !in registrations) { "Plugin web page already registered: $key" }
        var total = 0
        val content = assets.map { (publicPath, resourcePath) ->
            require(safePath(resourcePath)) { "Invalid plugin web resource path" }
            val bytes = resource(resourcePath)?.use { it.readNBytes(16 * 1024 * 1024 + 1) } ?: error("Missing plugin web resource: $resourcePath")
            total += bytes.size
            require(bytes.size <= 16 * 1024 * 1024 && total <= 64 * 1024 * 1024) { "Plugin UI assets exceed size limit" }
            publicPath to WebAsset(bytes, contentType(publicPath))
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        content.sortedBy { it.first }.forEach { (path, asset) ->
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            digest.update(asset.bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(0.toByte()); digest.update(asset.bytes)
        }
        val version = java.util.HexFormat.of().formatHex(digest.digest()).take(16)
        val prefix = "/plugin-assets/$key/$version/"
        val owned = ResourceScope()
        try {
            content.forEach { (path, asset) -> owned.own(rest.publicRoute("GET", prefix + path) { assetResponse(asset) }) }
            val entry = PluginWebPage(pluginId, pluginName, page.id, page.title, page.description, prefix + page.module, page.stylesheet?.let { prefix + it }, page.order)
            registrations[key] = entry to owned
        } catch (e: Throwable) { try { owned.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }; throw e }
        return AutoCloseable { synchronized(this) {
            if (registrations[key]?.second === owned) { registrations.remove(key); owned.close() }
        } }
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Exception? = null
        (registrations.values.map { it.second }.asReversed() + scope).forEach { resource ->
            try { resource.close() } catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        registrations.clear(); failure?.let { throw it }
    }
    companion object {
        private fun safePath(path: String) = path.isNotBlank() && !path.startsWith('/') && path.split('/').all {
            it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+"))
        }
        private fun assetResponse(asset: WebAsset) = RestResponse(body = asset.bytes, contentType = asset.contentType, headers = mapOf(
            "Cache-Control" to "no-cache", "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"))
        private fun contentType(path: String) = when(path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "json" -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
        private fun bundledAssets(): Map<String, WebAsset> {
            val loader = WebInterfaceService::class.java.classLoader
            val manifest = loader.getResourceAsStream("web/assets.list")?.bufferedReader()?.use { it.readLines() } ?: return emptyMap()
            return manifest.filter(String::isNotBlank).associateWith { path ->
                require(safePath(path))
                WebAsset(loader.getResourceAsStream("web/$path")?.use { it.readAllBytes() } ?: error("Missing built web asset"), contentType(path))
            }
        }
    }
}
