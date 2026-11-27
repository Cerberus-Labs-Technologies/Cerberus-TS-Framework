package labs.cerberus.ts.service

import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.time.Clock
import java.time.Duration
import java.time.Instant

class TokenService(path: Path, private val clock: Clock = Clock.systemUTC()) {
    private data class Token(val scopes: Set<String>, val expiresAt: Instant?)
    private val store = JsonStore(path)
    private val tokens = linkedMapOf<String, Token>()
    init {
        store.read().path("tokens").properties().forEach { (hash, node) ->
            tokens[hash] = Token(node.path("scopes").map { it.asText() }.toSet(),
                node.get("expiresAt")?.takeUnless { it.isNull }?.asText()?.let(Instant::parse))
        }
    }
    @Synchronized fun generate(scopes: Set<String>, lifetime: Duration? = null): String {
        require(scopes.isNotEmpty() && scopes.all { it.matches(Regex("(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /[a-zA-Z0-9_./*-]*")) })
        require(lifetime == null || (!lifetime.isNegative && !lifetime.isZero))
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        tokens[hash(token)] = Token(scopes.toSet(), lifetime?.let { clock.instant().plus(it) })
        save()
        return token
    }
    @Synchronized fun verify(token: String, method: String, path: String): Boolean {
        if (token.length !in 20..200 || !validPath(path)) return false
        val value = tokens[hash(token)] ?: return false
        if (value.expiresAt?.let { !it.isAfter(clock.instant()) } == true) return false
        return value.scopes.any { scope ->
            val (verb, pattern) = scope.split(' ', limit = 2)
            verb == method && if (pattern.endsWith("/*")) path.startsWith(pattern.dropLast(1)) else path == pattern
        }
    }
    @Synchronized fun revoke(token: String): Boolean = (tokens.remove(hash(token)) != null).also { if (it) save() }
    private fun hash(token: String) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    private fun save() = store.write(mapOf("tokens" to tokens.mapValues { (_, token) ->
        mapOf("scopes" to token.scopes, "expiresAt" to token.expiresAt?.toString())
    }))
    companion object {
        fun validPath(path: String) = path.startsWith('/') && path.split('/').none { it == "." || it == ".." } &&
            path.none { it == '\\' || it == '%' || it.code < 32 }
    }
}
