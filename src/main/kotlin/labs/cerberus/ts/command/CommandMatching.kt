package labs.cerberus.ts.command

/** Schema is validated before command registration; every speculative path owns immutable state. */
internal class CommandMatching(private val spec: CommandSpec, private val registry: MatcherRegistry) {
    private val options = spec.arguments.isNotEmpty()
    private val roots = spec.parameters.map { it.criterion } + spec.arguments.map { it.criterion }
    private val leaves = mutableListOf<Criterion.Value>()
    private val names: Set<String>
    private val shorthands: Map<String, List<Criterion.Value>>
    init {
        var nodes = 0
        fun visit(node: Criterion, depth: Int) {
            require(++nodes <= 256 && depth <= 16) { "Command schema too complex" }
            when (node) {
                is Criterion.Value -> {
                    require(node.name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "Invalid criterion name" }
                    require(node.shorthand == null || node.shorthand.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "Invalid shorthand" }
                    leaves += node
                }
                is Criterion.Optional -> visit(node.child, depth + 1)
                is Criterion.FirstOf -> { require(node.children.isNotEmpty()); node.children.forEach { visit(it, depth + 1) } }
            }
        }
        roots.forEach { visit(it, 0) }
        names = leaves.map { it.name }.toSet()
        require(names.size == leaves.size) { "Duplicate criterion name" }
        shorthands = leaves.filter { it.shorthand != null && it.shorthand != it.name }.groupBy { it.shorthand!! }
        registry.snapshot(leaves.map { it.type }.toSet())
    }
    private data class Input(val positional: List<String>, val options: Map<String, String>)
    private data class State(val index: Int = 0, val consumed: Set<String> = emptySet(), val values: Map<String, Any> = emptyMap(), val notices: List<String> = emptyList())

    fun execute(context: CommandContext, active: () -> Boolean, messages: (String, Map<String, Any>, String) -> String) {
        val input = input(context.parsed.tokens)
        val matchers = registry.snapshot(leaves.map { it.type }.toSet())
        var steps = 0
        var best: Pair<Int, String>? = null
        fun reject(state: State, message: String) {
            val score = state.index + state.consumed.size
            if (best == null || score > best!!.first) best = score to message
        }
        fun evaluate(node: Criterion, state: State): Sequence<State> = sequence {
            require(++steps <= 2048) { "Command matching limit exceeded" }
            check(active()) { "Command has been unloaded" }
            when (node) {
                is Criterion.Optional -> {
                    fun present(child: Criterion): Boolean = when (child) {
                        is Criterion.Value -> child.name in input.options
                        is Criterion.Optional -> present(child.child)
                        is Criterion.FirstOf -> child.children.any(::present)
                    }
                    if (if (options) present(node.child) else state.index < input.positional.size) yieldAll(evaluate(node.child, state))
                    yield(state)
                }
                is Criterion.FirstOf -> node.children.forEach { yieldAll(evaluate(it, state)) }
                is Criterion.Value -> {
                    val raw = if (options) input.options[node.name] else input.positional.getOrNull(state.index)
                    if (raw == null) reject(state, messages("matcher.type.missing", mapOf("param" to node.name), "Missing parameter: ${node.name}"))
                    else {
                        val matching = MatchingContext(context, node.name, node.type, state.index, state.values, messages)
                        when (val result = matchers.getValue(node.type).match(matching, raw)) {
                            is MatchResult.Rejected -> reject(state, result.message)
                            is MatchResult.Success<*> -> {
                                check(boxedType(node.type).isInstance(result.value)) { "Matcher returned wrong value type" }
                                check(result.additionalValues.keys.none { it in names || it in state.values } && node.name !in state.values) { "Matcher output collides with another criterion" }
                                yield(State(if (options) state.index else state.index + 1,
                                    if (options) state.consumed + node.name else state.consumed,
                                    state.values + result.additionalValues + (node.name to result.value), state.notices + result.notices))
                            }
                        }
                    }
                }
            }
        }
        fun all(index: Int, state: State): Sequence<State> = sequence {
            if (index == roots.size) {
                if (state.index == input.positional.size && state.consumed.size == input.options.size) yield(state)
                else reject(state, if (options) "Unexpected options: ${(input.options.keys - state.consumed).joinToString()}" else "Unexpected argument at position ${state.index + 1}")
            } else evaluate(roots[index], state).forEach { yieldAll(all(index + 1, it)) }
        }
        val result = all(0, State()).firstOrNull() ?: throw IllegalArgumentException((best?.second ?: "Command did not match") + "\nUsage: ${spec.usage}")
        check(active()) { "Command has been unloaded" }
        require(context.reauthorize()) { "Permission or sender identity changed while matching command" }
        spec.executor.execute(TypedCommandContext(context, spec, result.values, result.notices))
        result.notices.forEach(context::reply)
    }

    private fun input(tokens: List<CommandToken>): Input {
        val positional = mutableListOf<String>()
        val values = linkedMapOf<String, String>()
        var literal = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index++]
            if (!literal && token.optionEligible && token.value == "--") { literal = true; continue }
            if (!literal && token.optionEligible && token.value.matches(optionToken)) {
                require(options) { "This command uses positional parameters; quote option-like values or use --" }
                val key = token.value.trimStart('-').substringBefore('=')
                val leaf = leaves.firstOrNull { it.name == key } ?: shorthands[key]?.let { candidates ->
                    require(candidates.size == 1) { "Ambiguous shorthand: $key; use the full option name" }; candidates.single()
                } ?: throw IllegalArgumentException("Unknown option: $key")
                require(leaf.name !in values) { "Duplicate option: ${leaf.name}" }
                val value = if ('=' in token.value) token.value.substringAfter('=') else {
                    val next = tokens.getOrNull(index)
                    val candidate = next?.takeUnless { it.optionEligible && (it.value == "--" || it.value.matches(optionToken)) }
                    if (boxedType(leaf.type) == Boolean::class.javaObjectType && (candidate == null || candidate.value.lowercase(java.util.Locale.ROOT) !in BOOLEAN_VALUES)) "true"
                    else { require(candidate != null) { "Missing value for option: ${leaf.name}" }; index++; candidate.value }
                }
                values[leaf.name] = value
            } else positional += token.value
        }
        require(!options || positional.isEmpty()) { "This command uses named options, not positional parameters" }
        return Input(positional, values)
    }
}
internal val BOOLEAN_VALUES = setOf("true", "t", "yes", "y", "1", "false", "f", "no", "n", "0")
