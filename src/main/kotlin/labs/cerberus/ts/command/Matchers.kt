package labs.cerberus.ts.command

import java.util.concurrent.atomic.AtomicBoolean

sealed interface MatchResult<out T : Any> {
    class Success<T : Any>(val value: T, additionalValues: Map<String, Any> = emptyMap(), notices: List<String> = emptyList()) : MatchResult<T> {
        val additionalValues: Map<String, Any> = java.util.Map.copyOf(additionalValues)
        val notices: List<String> = java.util.List.copyOf(notices)
    }
    data class Rejected(val message: String) : MatchResult<Nothing>
}
class MatchingContext internal constructor(val command: CommandContext, val name: String, val type: Class<*>, val index: Int,
    values: Map<String, Any>, private val messages: (String, Map<String, Any>, String) -> String) {
    val values: Map<String, Any> = java.util.Map.copyOf(values)
    fun message(key: String, parameters: Map<String, Any>, fallback: String) = messages(key, parameters, fallback)
    fun incompatible(input: String): MatchResult.Rejected = MatchResult.Rejected(message("matcher.type.incompatible",
        mapOf("param" to name, "type" to type.simpleName, "input" to input.take(120)), "Invalid $name: expected ${type.simpleName}"))
    fun ambiguous(results: String): MatchResult.Rejected = MatchResult.Rejected(message("matcher.type.ambiguousSearch",
        mapOf("param" to name, "results" to results), "Ambiguous $name: $results"))
}
fun interface ValueMatcher<T : Any> { fun match(context: MatchingContext, input: String): MatchResult<T> }
interface CriterionMatcher<T : Any> {
    val supportedType: Class<T>
    fun tryMatch(context: MatchingContext, input: String): MatchResult<T>
}

/** Exact matches win; ambiguous assignable registrations require an explicit matcher. */
class MatcherRegistry {
    private class Entry(val type: Class<*>, val matcher: ValueMatcher<*>)
    private val registrations = linkedMapOf<Class<*>, MutableList<Entry>>()
    @Synchronized fun <T : Any> register(type: Class<T>, matcher: ValueMatcher<T>): AutoCloseable {
        val boxed = boxedType(type)
        val entry = Entry(boxed, matcher)
        registrations.getOrPut(boxed) { mutableListOf() } += entry
        val active = AtomicBoolean(true)
        return AutoCloseable { synchronized(this) {
            if (active.compareAndSet(true, false)) {
                registrations[boxed]?.remove(entry)
                if (registrations[boxed].isNullOrEmpty()) registrations.remove(boxed)
            }
        } }
    }
    fun <T : Any> registerMatcher(matcher: CriterionMatcher<T>): AutoCloseable = register(matcher.supportedType, matcher::tryMatch)
    @Synchronized internal fun snapshot(types: Set<Class<*>>): Map<Class<*>, ValueMatcher<*>> = types.associateWith { type ->
        val wanted = boxedType(type)
        val exact = registrations[wanted]?.lastOrNull()
        if (exact != null) exact.matcher else {
            val possible = registrations.filterKeys(wanted::isAssignableFrom).values.mapNotNull { it.lastOrNull() }
            val mostSpecific = possible.filter { candidate -> possible.none { other -> candidate !== other && candidate.type.isAssignableFrom(other.type) } }
            require(mostSpecific.size == 1) { if (mostSpecific.isEmpty()) "No matcher registered for ${type.name}" else "Ambiguous matchers for ${type.name}" }
            mostSpecific.single().matcher
        }
    }
}

class TypedCommandContext internal constructor(val command: CommandContext, val specification: CommandSpec,
    values: Map<String, Any>, notices: List<String>) {
    val values: Map<String, Any> = java.util.Map.copyOf(values)
    val notices: List<String> = java.util.List.copyOf(notices)
    val clientId get() = command.clientId
    val uniqueId get() = command.uniqueId
    val senderName get() = command.senderName
    fun reply(message: String) = command.reply(message)
    fun hasPermission(permission: String, minimum: Int = 1) = command.hasPermission(permission, minimum)
    fun hasOne(name: String): Boolean = name in values
    fun getOne(name: String): Any? = values[name]
    fun <T : Any> getOne(name: String, type: Class<T>): T? = values[name]?.let {
        require(boxedType(type).isInstance(it)) { "Value $name has a different type" }
        @Suppress("UNCHECKED_CAST")
        it as T
    }
    fun <T : Any> getRequiredOne(name: String, type: Class<T>): T = getOne(name, type) ?: error("Missing matched value: $name")
    inline fun <reified T : Any> get(name: String): T? = getOne(name, T::class.java)
    inline fun <reified T : Any> required(name: String): T = getRequiredOne(name, T::class.java)
}

internal fun boxedType(type: Class<*>): Class<*> = when (type) {
    Int::class.javaPrimitiveType -> Int::class.javaObjectType
    Long::class.javaPrimitiveType -> Long::class.javaObjectType
    Short::class.javaPrimitiveType -> Short::class.javaObjectType
    Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
    Float::class.javaPrimitiveType -> Float::class.javaObjectType
    Double::class.javaPrimitiveType -> Double::class.javaObjectType
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    else -> type
}
