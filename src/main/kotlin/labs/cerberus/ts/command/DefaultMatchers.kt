package labs.cerberus.ts.command

import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.UUID

internal fun MatcherRegistry.installDefaults(server: TeamSpeakServer, permissions: PermissionService, users: UserService) {
    fun <T : Any> scalar(type: Class<T>, parse: (String) -> T) {
        register(type) { context, input ->
            try { MatchResult.Success(parse(input)) } catch (_: IllegalArgumentException) { context.incompatible(input) }
        }
    }
    scalar(String::class.java) { it }
    scalar(Int::class.javaObjectType, String::toInt)
    scalar(Long::class.javaObjectType, String::toLong)
    scalar(Short::class.javaObjectType, String::toShort)
    scalar(Byte::class.javaObjectType, String::toByte)
    scalar(Double::class.javaObjectType) { it.toDouble().also { value -> require(value.isFinite()) } }
    scalar(Float::class.javaObjectType) { it.toFloat().also { value -> require(value.isFinite()) } }
    scalar(BigInteger::class.java, ::BigInteger)
    scalar(BigDecimal::class.java, ::BigDecimal)
    scalar(UUID::class.java, UUID::fromString)
    scalar(Locale::class.java) { Locale.forLanguageTag(localeTag(if (it == "unset") "xx" else it)) }
    scalar(Boolean::class.javaObjectType) {
        val value = it.lowercase(Locale.ROOT)
        require(value in BOOLEAN_VALUES)
        value in setOf("true", "t", "yes", "y", "1")
    }
    fun <T : Any> one(context: MatchingContext, input: String, values: List<T>, id: (T) -> Any): MatchResult<T> = when (values.size) {
        0 -> context.incompatible(input)
        1 -> MatchResult.Success(values.single(), mapOf("${context.name}Id" to id(values.single())))
        else -> context.ambiguous("${values.size} matches; use an explicit ID")
    }
    val uniqueId = Regex("[A-Za-z0-9+/]{27}=")
    register(Client::class.java) { context, input ->
        val clients = server.clients(refresh = true).filter { !it.isQueryClient }
        val candidates = when {
            input.startsWith("c:") -> clients.filter { it.id == input.drop(2).toIntOrNull() }
            input.startsWith("db:") -> input.drop(3).toIntOrNull()?.let { id -> clients.filter { it.databaseId == id } }.orEmpty()
            input.all(Char::isDigit) && input.isNotEmpty() -> input.toIntOrNull()?.let { id -> clients.filter { it.databaseId == id } }.orEmpty()
            input.startsWith("uid:") -> clients.filter { it.uniqueId == input.drop(4) }
            input.matches(uniqueId) -> clients.filter { it.uniqueId == input }
            else -> clients.filter { input.isNotBlank() && it.nickname.contains(input, ignoreCase = true) }
        }
        one(context, input, candidates) { it.id }
    }
    register(Channel::class.java) { context, input ->
        val channels = server.channels(refresh = true)
        val id = (if (input.startsWith("cid:")) input.drop(4) else input).toIntOrNull()
        val candidates = if (id != null) channels.filter { it.id == id } else channels.filter { input.isNotBlank() && !input.startsWith("cid:") && it.name.contains(input, ignoreCase = true) }
        one(context, input, candidates) { it.id }
    }
    register(TeamSpeakUser::class.java) { context, input ->
        val candidates = when {
            input.startsWith("db:") -> input.drop(3).toIntOrNull()?.takeIf { it > 0 }?.let { listOfNotNull(users.findByDatabaseId(it)) }.orEmpty()
            input.startsWith("c:") -> server.clients(refresh = true).firstOrNull { it.id == input.drop(2).toIntOrNull() && !it.isQueryClient }?.let { listOfNotNull(users.fromClient(it)) }.orEmpty()
            input.startsWith("uid:") -> users.findByUniqueId(input.drop(4))
            input.matches(uniqueId) -> users.findByUniqueId(input)
            else -> users.findByNickname("%$input%")
        }
        one(context, input, candidates) { it.databaseId }
    }
    register(PermissionGroup::class.java) { context, input -> permissions.findGroup(input)?.let { MatchResult.Success(it) } ?: context.incompatible(input) }
    register(PermissionSubject::class.java) { context, input -> permissions.findSubject(input)?.let { MatchResult.Success(it, mapOf("${context.name}Id" to it.id)) } ?: context.incompatible(input) }
}
