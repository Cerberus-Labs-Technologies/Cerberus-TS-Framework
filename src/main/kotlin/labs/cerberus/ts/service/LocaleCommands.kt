package labs.cerberus.ts.service

import labs.cerberus.ts.command.CommandContext
import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.query.command
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import java.util.Locale

internal fun LocalizationService.installCommands(commands: CommandService, server: TeamSpeakServer): AutoCloseable {
    fun execute(context: CommandContext, chosen: String?, target: String?) {
        val uid = target ?: context.uniqueId
        if (target != null && target != context.uniqueId) require(context.hasPermission("cerberus.locale.manage")) { "Permission denied." }
        if (chosen == null) {
            val country = if (uid != null && uid == context.uniqueId && context.clientId != null) {
                val row = server.execute(command("clientinfo", "clid" to context.clientId)).rows.firstOrNull()
                check(row == null || row["client_unique_identifier"] == uid) { "Client identity changed" }
                row?.get("client_country")
            } else null
            context.reply(localeOfIdentity(uid, country).toLanguageTag())
        } else {
            require(!uid.isNullOrBlank()) { "Console usage: locale <language|reset> <UID>" }
            val reset = chosen.lowercase(Locale.ROOT) in setOf("reset", "xx", "unset")
            val selected = if (reset) null else Locale.forLanguageTag(localeTag(chosen))
            setLocaleForClient(uid, selected)
            context.reply(if (selected == null) "Chosen locale reset to default." else "Chosen locale set to: ${selected.toLanguageTag()}")
        }
    }
    val registrations = mutableListOf<AutoCloseable>()
    try {
        registrations += commands.register("locale", "Show/set language: [language|reset] [UID]") {
            require(it.parsed.arguments.size <= 2 && it.parsed.options.isEmpty()) { "Usage: locale [language|reset] [UID]" }
            execute(it, it.parsed.arguments.getOrNull(0), it.parsed.arguments.getOrNull(1))
        }
        registrations += commands.register("locale-arg", "Show/set language: --locale=de --user=UID") {
            val options = it.parsed.options
            require(it.parsed.arguments.isEmpty() && options.keys.all { key -> key in setOf("locale", "l", "user", "u") } &&
                !("locale" in options && "l" in options) && !("user" in options && "u" in options) && options.values.all { value -> !value.isNullOrBlank() }) { "Usage: locale-arg [--locale=de] [--user=UID]" }
            execute(it, options["locale"] ?: options["l"], options["user"] ?: options["u"])
        }
    } catch (error: Throwable) { registrations.asReversed().forEach(AutoCloseable::close); throw error }
    return AutoCloseable { registrations.asReversed().forEach(AutoCloseable::close) }
}
