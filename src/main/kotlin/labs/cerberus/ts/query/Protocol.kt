package labs.cerberus.ts.query

typealias QueryRow = Map<String, String>

/** Wire values are escaped once, after splitting the protocol's structural delimiters. */
object QueryCodec {
    private val escapes = mapOf(
        '\\' to '\\', '/' to '/', ' ' to 's', '|' to 'p', '\u0007' to 'a',
        '\b' to 'b', '\u000c' to 'f', '\n' to 'n', '\r' to 'r', '\t' to 't', '\u000b' to 'v'
    )
    private val unescapes = escapes.entries.associate { (k, v) -> v to k }

    fun encode(value: String): String = buildString {
        value.forEach { c -> escapes[c]?.let { append('\\').append(it) } ?: append(c) }
    }

    fun decode(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\') append(c) else {
                require(i < value.length) { "Truncated query escape" }
                append(unescapes[value[i++]] ?: throw IllegalArgumentException("Unknown query escape"))
            }
        }
    }

    fun rows(line: String): List<QueryRow> = if (line.isBlank()) emptyList() else line.split('|').map { row ->
        buildMap {
            row.split(' ').filter(String::isNotEmpty).forEach { field ->
                val key = field.substringBefore('=')
                require(key.matches(Regex("[a-zA-Z0-9_]+"))) { "Invalid query field" }
                require(!containsKey(key)) { "Duplicate query field: $key" }
                put(key, decode(field.substringAfter('=', "")))
            }
        }
    }
}

enum class QueryScope { VIRTUAL_SERVER, INSTANCE }

class QueryCommand(
    val name: String,
    parameters: Map<String, String> = emptyMap(),
    options: Set<String> = emptySet(),
    additionalRows: List<Map<String, String>> = emptyList(),
    val scope: QueryScope = QueryScope.VIRTUAL_SERVER
) {
    val parameters = parameters.toMap()
    val options = options.toSet()
    val additionalRows = additionalRows.map { it.toMap() }
    init {
        require(name.matches(Regex("[a-z][a-z0-9]*"))) { "Invalid command name" }
        (listOf(this.parameters) + this.additionalRows).forEach { row ->
            require(row.keys.all { it.matches(Regex("[a-zA-Z0-9_]+")) }) { "Invalid parameter name" }
            require(row.values.none { '\u0000' in it }) { "NUL is not supported" }
        }
        require(this.options.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*")) }) { "Invalid query option" }
        require(this.additionalRows.all { it.isNotEmpty() }) { "Empty batch row" }
    }

    fun wire(): String = buildString {
        append(name)
        if (parameters.isNotEmpty()) append(' ').append(encodeRow(parameters))
        additionalRows.forEach { append('|').append(encodeRow(it)) }
        options.forEach { append(" -").append(it) }
    }

    // Commands may contain passwords, messages and keys. Never log their wire representation.
    override fun toString() = "QueryCommand($name)"

    private fun encodeRow(row: QueryRow) = row.entries.joinToString(" ") { (k, v) -> "$k=${QueryCodec.encode(v)}" }
}

fun command(name: String, vararg parameters: Pair<String, Any>): QueryCommand =
    QueryCommand(name, parameters.associate { it.first to it.second.toString() })

data class QueryError(val id: Int, val message: String, val details: QueryRow = emptyMap())
data class QueryResponse(val rows: List<QueryRow>, val error: QueryError) {
    fun checked(): QueryResponse = apply { if (error.id != 0) throw QueryException(error) }
    fun single(): QueryRow = checked().rows.single()
}

class QueryException(val error: QueryError) : RuntimeException("TeamSpeak query error ${error.id}")
class QueryDisconnectedException(message: String = "Query connection closed", cause: Throwable? = null) :
    java.io.IOException(message, cause)

data class QueryNotification(val name: String, val rows: List<QueryRow>)

interface QueryConnection : AutoCloseable {
    val supportsNotifications: Boolean
    val isOpen: Boolean
    fun execute(command: QueryCommand): QueryResponse
    fun onNotification(listener: (QueryNotification) -> Unit): AutoCloseable
    fun onDisconnect(listener: (Throwable?) -> Unit): AutoCloseable
}
