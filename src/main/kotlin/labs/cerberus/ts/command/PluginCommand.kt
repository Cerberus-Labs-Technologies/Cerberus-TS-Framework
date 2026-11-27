package labs.cerberus.ts.command

import labs.cerberus.ts.plugin.CommandDescription
import labs.cerberus.ts.plugin.JavaPlugin

interface CommandSender {
    val name: String
    fun sendMessage(message: String)
    fun hasPermission(permission: String): Boolean
}

class ConsoleCommandSender internal constructor(private val context: CommandContext) : CommandSender {
    override val name = "CONSOLE"
    override fun sendMessage(message: String) = context.reply(message)
    override fun hasPermission(permission: String) = true
}

class ClientCommandSender internal constructor(private val context: CommandContext) : CommandSender {
    val clientId: Int get() = requireNotNull(context.clientId)
    val uniqueId: String get() = requireNotNull(context.uniqueId)
    override val name get() = context.senderName
    override fun sendMessage(message: String) = context.reply(message)
    override fun hasPermission(permission: String) = context.hasPermission(permission)
}

fun interface CommandExecutor {
    /** Return false to display the command's configured usage. */
    fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean
}

open class Command(val name: String, val description: String, val usage: String, val aliases: List<String>, val permission: String?)

class PluginCommand internal constructor(name: String, definition: CommandDescription, val plugin: JavaPlugin) :
    Command(name, definition.description, definition.usage, definition.aliases, definition.permission) {
    @Volatile private var executor: CommandExecutor = plugin
    fun setExecutor(executor: CommandExecutor?) { this.executor = executor ?: plugin }
    internal fun execute(context: CommandContext, prefix: String) {
        if (!plugin.isEnabled) { context.reply("Plugin is not enabled."); return }
        val sender = if (context.clientId == null) ConsoleCommandSender(context) else ClientCommandSender(context)
        val label = context.parsed.name
        if (!executor.onCommand(sender, this, label, context.parsed.rawArguments.toTypedArray()) && usage.isNotBlank()) {
            sender.sendMessage(usage.replace("<command>", "$prefix$label"))
        }
    }
}
