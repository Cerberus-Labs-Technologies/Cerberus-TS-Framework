package labs.cerberus.ts.command

import labs.cerberus.ts.event.TextMessage
import labs.cerberus.ts.service.PermissionService
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import java.util.concurrent.atomic.AtomicBoolean
import labs.cerberus.ts.service.LocalizationService
import labs.cerberus.ts.teamspeak.UserService

data class ParsedCommand(val name: String, val arguments: List<String>, val options: Map<String, String?>,
    val rawArguments: List<String> = arguments, val tokens: List<CommandToken> = rawArguments.map(::CommandToken))

object CommandParser {
    fun parse(input: String): ParsedCommand {
        val tokens = commandTokens(input)
        require(tokens.isNotEmpty() && tokens[0].value.lowercase(java.util.Locale.ROOT).matches(commandName)) { "Missing or invalid command name" }
        val arguments = mutableListOf<String>()
        val options = linkedMapOf<String, String?>()
        var literal = false
        tokens.drop(1).forEach { token ->
            if (!literal && token.optionEligible && token.value == "--") literal = true
            else if (!literal && token.optionEligible && token.value.matches(optionToken)) {
                val key = token.value.trimStart('-').substringBefore('=')
                require(key !in options) { "Duplicate option: $key" }
                options[key] = if ('=' in token.value) token.value.substringAfter('=') else null
            } else arguments += token.value
        }
        return ParsedCommand(tokens.first().value.lowercase(java.util.Locale.ROOT), arguments, options, tokens.drop(1).map { it.value }, tokens.drop(1))
    }
}

class CommandContext(val parsed: ParsedCommand, val clientId: Int?, val uniqueId: String?,
    val serverGroups: Set<Int>, private val respond: (String) -> Unit,
    val senderName: String = if (clientId == null) "CONSOLE" else uniqueId.orEmpty(),
    private val permissionCheck: (String) -> Boolean = { clientId == null }) {
    fun reply(message: String) = respond(message)
    fun hasPermission(permission: String) = permissionCheck(permission)
    internal var permissionMinimumCheck: ((String, Int) -> Boolean)? = null
    internal var reauthorize: () -> Boolean = { true }
    fun hasPermission(permission: String, minimum: Int): Boolean {
        require(minimum > 0)
        if (clientId == null) return true
        return permissionMinimumCheck?.invoke(permission, minimum) ?: (minimum == 1 && hasPermission(permission))
    }
    fun argument(index: Int): String = parsed.arguments.getOrNull(index) ?: throw IllegalArgumentException("Missing argument ${index + 1}")
    fun integer(index: Int): Int = argument(index).toIntOrNull() ?: throw IllegalArgumentException("Argument ${index + 1} must be an integer")
    fun boolean(index: Int): Boolean = when (argument(index).lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> throw IllegalArgumentException("Argument ${index + 1} must be a boolean")
    }
}

class CommandService @JvmOverloads constructor(private val server: TeamSpeakServer, private val permissions: PermissionService, val prefix: String = "!",
    private val localization: LocalizationService? = null) {
    private class Entry(val name: String, val description: String, val permission: String?,
        val permissionMessage: String, val execute: (CommandContext) -> Unit, val minimum: Int = 1,
        val specification: CommandSpec? = null, val active: AtomicBoolean = AtomicBoolean(true), val available: () -> Boolean = { true })
    @Volatile private var commands: Map<String, Entry> = emptyMap()
    val matchers = MatcherRegistry()
    val users = UserService(server)
    init { require(prefix.isNotBlank()); matchers.installDefaults(server, permissions, users) }

    @Synchronized fun register(name: String, description: String = "", permission: String? = null,
        permissionMessage: String = "Permission denied.",
        execute: (CommandContext) -> Unit): AutoCloseable {
        require(name.matches(commandName))
        val entry = Entry(name, description, permission, permissionMessage, execute)
        return install(entry, listOf(name), false)
    }
    fun register(spec: CommandSpec): AutoCloseable = registerCommand(spec)
    @JvmOverloads fun registerCommand(spec: CommandSpec, replace: Boolean = false, available: () -> Boolean = { true }): AutoCloseable {
        val compiled = CommandMatching(spec, matchers)
        val active = AtomicBoolean(true)
        val entry = Entry(spec.name, spec.description, spec.permission, "Permission denied.",
            { context -> compiled.execute(context, { active.get() && available() }) { key, params, fallback -> translated(context, key, params, fallback) } }, spec.permissionMinimum, spec, active, available)
        return install(entry, listOf(spec.name) + spec.aliases, replace)
    }
    @Synchronized private fun install(entry: Entry, labels: List<String>, replace: Boolean): AutoCloseable {
        val previous = labels.mapNotNull { commands[it] }.toSet()
        check(replace || previous.isEmpty()) { "Command or alias already registered" }
        previous.forEach { it.active.set(false) }
        commands = commands.filterValues { it !in previous } + labels.associateWith { entry }
        return AutoCloseable { remove(entry) }
    }
    @Synchronized private fun remove(entry: Entry) {
        entry.active.set(false)
        commands = commands.filterValues { it !== entry }
    }
    fun unregisterCommand(spec: CommandSpec) { commands.values.firstOrNull { it.specification === spec }?.let(::remove) }
    fun getCommand(name: String): CommandSpec? = commands[name.lowercase(java.util.Locale.ROOT)]?.specification
    fun getCommands(): List<CommandSpec> = commands.values.mapNotNull { it.specification }.distinct().sortedBy { it.name }

    fun handle(event: TextMessage) {
        if (!event.message.startsWith(prefix)) return
        try {
            val identity = server.identity(event.clientId)
            // Ignore events belonging to an earlier occupant of a recycled client ID.
            if (event.uniqueId != null && identity.uniqueId != event.uniqueId) return
            dispatch(event.message.removePrefix(prefix), event.clientId, identity.uniqueId, identity.serverGroups, identity.nickname, { permission, minimum ->
                val current = server.identity(event.clientId)
                current.uniqueId == identity.uniqueId && (permission == null || permissions.allows(current.uniqueId, permission, current.serverGroups, minimum))
            }) {
                if (server.identity(event.clientId).uniqueId == identity.uniqueId) server.privateMessage(event.clientId, it)
            }
        } catch (e: Exception) {
            System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Command dispatch failed: ${e.javaClass.simpleName}")
        }
    }

    fun console(input: String, reply: (String) -> Unit) = dispatch(input.removePrefix(prefix), null, null, emptySet(), "CONSOLE", reply = reply)

    private fun dispatch(input: String, clientId: Int?, uniqueId: String?, groups: Set<Int>, senderName: String,
        reauthorize: (String?, Int) -> Boolean = { _, _ -> true }, reply: (String) -> Unit) {
        try {
            val parsed = CommandParser.parse(input)
            val entry = commands[parsed.name] ?: run { reply("Unknown command. Use ${prefix}help."); return }
            if (!entry.available()) { reply("Plugin is not enabled."); return }
            if (clientId != null && entry.permission != null && !permissions.allows(requireNotNull(uniqueId), entry.permission, groups, entry.minimum)) {
                reply(entry.permissionMessage); return
            }
            val context = CommandContext(parsed, clientId, uniqueId, groups, reply, senderName) { permission ->
                clientId == null || permissions.allows(requireNotNull(uniqueId), permission, groups)
            }
            context.permissionMinimumCheck = { permission, minimum -> clientId == null || permissions.allows(requireNotNull(uniqueId), permission, groups, minimum) }
            context.reauthorize = { reauthorize(entry.permission, entry.minimum) }
            check(entry.active.get()) { "Command has been unloaded" }
            entry.execute(context)
        } catch (e: IllegalArgumentException) { reply(e.message ?: "Invalid command arguments") }
        catch (e: Exception) {
            reply("Command failed.")
            System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Command failed: ${e.javaClass.simpleName}")
        }
    }

    fun installBuiltins(): AutoCloseable {
        val registrations = listOf(
            register("help", "List available commands") { ctx ->
                val visible = commands.values.filter { it.permission == null || ctx.hasPermission(it.permission, it.minimum) }
                if (ctx.parsed.arguments.isNotEmpty()) {
                    require(ctx.parsed.arguments.size == 1)
                    val entry = commands[ctx.argument(0)]?.takeIf { it in visible }
                    ctx.reply(entry?.let { "${prefix}${it.specification?.usage ?: it.name}\n${it.description}" } ?: "Unknown command.")
                } else ctx.reply(visible.distinctBy { it.name }
                    .sortedBy { it.name }.joinToString("\n") { "$prefix${it.name} — ${it.description}" })
            },
            register("whoami", "Show your identity") { it.reply(it.uniqueId ?: "Local console") },
            register("whereami", "Show the selected virtual server") { it.reply("${server.config.host}, server ${server.selectedServerId}") },
            register("group-create", "Create permission group: <name>", "cerberus.permissions.manage") { permissions.createGroup(it.argument(0)); it.reply("Group created.") },
            register("group-link", "Link group: <name> <TS server group ID>", "cerberus.permissions.manage") { permissions.linkServerGroup(it.argument(0), it.integer(1)); it.reply("Group linked.") },
            register("grant", "Grant user permission: <uid> <permission> <value>", "cerberus.permissions.manage") { permissions.grantUser(it.argument(0), it.argument(1), it.integer(2)); it.reply("Permission saved.") }
        )
        return AutoCloseable { registrations.asReversed().forEach(AutoCloseable::close) }
    }

    private val messages by lazy {
        localization?.registerUnit("commandService")?.apply { loadDefaults(mapOf("langs" to mapOf(
            "en" to mapOf("matcher.type.missing" to "Parameter %[param] is missing.",
                "matcher.type.incompatible" to "Input %[input] for %[param] is not a valid %[type].",
                "matcher.type.ambiguousSearch" to "Ambiguous %[param]: %[results]"),
            "de" to mapOf("matcher.type.missing" to "Parameter %[param] fehlt.",
                "matcher.type.incompatible" to "Eingabe %[input] für %[param] ist kein gültiger Wert vom Typ %[type].",
                "matcher.type.ambiguousSearch" to "Mehrdeutiger Parameter %[param]: %[results]")
        ))) }
    }
    private fun translated(context: CommandContext, key: String, parameters: Map<String, Any>, fallback: String): String =
        messages?.getContext(localization!!.localeOfIdentity(context.uniqueId))?.uncheckedGetMessage(key, parameters) ?: fallback
}
