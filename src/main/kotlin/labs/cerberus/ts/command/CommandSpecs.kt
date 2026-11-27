package labs.cerberus.ts.command

import java.util.Locale

internal sealed interface Criterion {
    data class Value(val name: String, val type: Class<*>, val shorthand: String? = null) : Criterion
    data class Optional(val child: Criterion) : Criterion
    class FirstOf(children: List<Criterion>) : Criterion { val children: List<Criterion> = java.util.List.copyOf(children) }
}
class ParameterSpec internal constructor(internal val criterion: Criterion)
class ArgumentSpec internal constructor(internal val criterion: Criterion)

class ParamSpecBuilder {
    private var name: String? = null
    private var type: Class<*>? = null
    fun name(value: String) = apply { name = value }
    fun type(value: Class<*>) = apply { type = value }
    fun optional(child: ParameterSpec) = ParameterSpec(Criterion.Optional(child.criterion))
    fun firstMatching(vararg children: ParameterSpec): ParameterSpec {
        require(children.isNotEmpty()); return ParameterSpec(Criterion.FirstOf(children.map { it.criterion }))
    }
    fun build() = ParameterSpec(Criterion.Value(requireNotNull(name) { "Parameter needs a name" }, requireNotNull(type) { "Parameter needs a type" }))
}
class ArgumentSpecBuilder {
    private var name: String? = null
    private var shorthand: String? = null
    private var type: Class<*>? = null
    fun name(value: String) = apply { name = value }
    fun shorthand(value: String) = apply { shorthand = value }
    fun type(value: Class<*>) = apply { type = value }
    fun optional(child: ArgumentSpec) = ArgumentSpec(Criterion.Optional(child.criterion))
    fun firstMatching(vararg children: ArgumentSpec): ArgumentSpec {
        require(children.isNotEmpty()); return ArgumentSpec(Criterion.FirstOf(children.map { it.criterion }))
    }
    fun build() = ArgumentSpec(Criterion.Value(requireNotNull(name) { "Option needs a name" }, requireNotNull(type) { "Option needs a type" }, shorthand))
}

fun interface TypedCommandExecutor { fun execute(context: TypedCommandContext) }
class CommandSpec internal constructor(val name: String, aliases: List<String>, parameters: List<ParameterSpec>, arguments: List<ArgumentSpec>,
    val permission: String?, val permissionMinimum: Int, val description: String, val executor: TypedCommandExecutor) {
    val aliases: List<String> = java.util.List.copyOf(aliases)
    val parameters: List<ParameterSpec> = java.util.List.copyOf(parameters)
    val arguments: List<ArgumentSpec> = java.util.List.copyOf(arguments)
    val usage: String get() = name + (parameters.map { signature(it.criterion, false) } + arguments.map { signature(it.criterion, true) })
        .joinToString(" ", prefix = if (parameters.isEmpty() && arguments.isEmpty()) "" else " ")
}
class CommandSpecBuilder {
    private var name: String? = null
    private val aliases = mutableListOf<String>()
    private val parameters = mutableListOf<ParameterSpec>()
    private val arguments = mutableListOf<ArgumentSpec>()
    private var permission: String? = null
    private var minimum = 1
    private var description = ""
    private var executor: TypedCommandExecutor? = null
    fun name(value: String) = apply { name = value.lowercase(Locale.ROOT) }
    fun alias(vararg values: String) = apply { aliases += values.map { it.lowercase(Locale.ROOT) } }
    fun parameters(vararg values: ParameterSpec) = apply { check(arguments.isEmpty()) { "Use parameters or options, not both" }; parameters += values }
    fun arguments(vararg values: ArgumentSpec) = apply { check(parameters.isEmpty()) { "Use parameters or options, not both" }; arguments += values }
    @JvmOverloads fun permission(value: String, minimum: Int = 1) = apply {
        require(value.isNotBlank() && minimum > 0); permission = value; this.minimum = minimum
    }
    fun description(value: String) = apply { description = value }
    fun executor(value: TypedCommandExecutor) = apply { executor = value }
    fun build(): CommandSpec {
        val command = requireNotNull(name) { "Command needs a name" }
        require((listOf(command) + aliases).all { it.matches(commandName) }) { "Invalid command/alias name" }
        require(aliases.distinct().size == aliases.size && command !in aliases) { "Duplicate command alias" }
        return CommandSpec(command, aliases, parameters, arguments, permission, minimum, description, requireNotNull(executor) { "Command needs an executor" })
    }
}

/** Java static imports and Kotlin imports share the same builder API. */
object Commands {
    @JvmStatic fun commandSpec() = CommandSpecBuilder()
    @JvmStatic fun commandSpec(name: String, vararg aliases: String) = commandSpec().name(name).alias(*aliases)
    @JvmStatic fun paramSpec() = ParamSpecBuilder()
    @JvmStatic fun paramSpec(name: String) = paramSpec().name(name)
    @JvmStatic fun paramSpec(name: String, type: Class<*>) = paramSpec(name).type(type).build()
    @JvmStatic fun argumentSpec() = ArgumentSpecBuilder()
    @JvmStatic fun argumentSpec(name: String, shorthand: String) = argumentSpec().name(name).shorthand(shorthand)
    @JvmStatic fun argumentSpec(name: String, shorthand: String, type: Class<*>) = argumentSpec(name, shorthand).type(type).build()
}
internal fun signature(criterion: Criterion, option: Boolean): String = when (criterion) {
    is Criterion.Value -> if (option) "--${criterion.name}=<${criterion.type.simpleName}>" else "<${criterion.name}:${criterion.type.simpleName}>"
    is Criterion.Optional -> "[${signature(criterion.child, option)}]"
    is Criterion.FirstOf -> criterion.children.joinToString("|", "(", ")") { signature(it, option) }
}
