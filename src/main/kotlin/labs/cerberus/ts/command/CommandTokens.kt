package labs.cerberus.ts.command

data class CommandToken(val value: String, val optionEligible: Boolean = true)
internal val commandName = Regex("[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*)?")
internal val optionToken = Regex("--?[a-zA-Z][a-zA-Z0-9_-]*(?:=.*)?")

/** Quote/escape provenance matters: a quoted '--name' is a value, not an option. */
internal fun commandTokens(input: String): List<CommandToken> {
    require(input.length <= 8192 && '\u0000' !in input) { "Command too long or contains NUL" }
    val tokens = mutableListOf<CommandToken>()
    val word = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false
    var eligible = true
    fun start(literal: Boolean) { if (!started) { started = true; eligible = !literal } }
    input.forEach { c ->
        when {
            escaped -> { word.append(c); escaped = false }
            c == '\\' -> { start(true); escaped = true }
            quote != null -> if (c == quote) quote = null else word.append(c)
            c == '"' || c == '\'' -> { start(true); quote = c }
            c.isWhitespace() -> if (started) { tokens += CommandToken(word.toString(), eligible); word.clear(); started = false }
            else -> { start(false); word.append(c) }
        }
    }
    require(!escaped && quote == null) { "Unterminated quote or escape" }
    if (started) tokens += CommandToken(word.toString(), eligible)
    require(tokens.size <= 512) { "Too many command tokens" }
    return tokens
}
