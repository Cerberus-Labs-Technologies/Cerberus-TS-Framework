package labs.cerberus.ts.plugin

import labs.cerberus.ts.configuration.SafeYaml
import java.io.InputStream
import java.util.Locale
import java.util.Properties

data class CommandDescription(val description: String = "", val usage: String = "", val aliases: List<String> = emptyList(),
    val permission: String? = null, val permissionMessage: String = "Permission denied.")

data class PluginDescriptor(val id: String, val version: String, val main: String,
    val dependencies: Set<String> = emptySet(), val api: Int = 2, val name: String = id,
    val softDependencies: Set<String> = emptySet(), val loadBefore: Set<String> = emptySet(),
    val commands: Map<String, CommandDescription> = emptyMap(), val authors: List<String> = emptyList(),
    val description: String = "", val website: String? = null) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]*"))) { "Invalid plugin ID" }
        require(version.isNotBlank() && version.length <= 100) { "Invalid plugin version" }
        require(main.isNotBlank() && api == 2) { "Plugin requires incompatible API" }
    }
    companion object {
        fun read(properties: Properties) = PluginDescriptor(
            properties.getProperty("id") ?: error("Missing plugin id"),
            properties.getProperty("version") ?: error("Missing plugin version"),
            properties.getProperty("main") ?: error("Missing plugin main class"),
            properties.getProperty("depends", "").split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
            properties.getProperty("api", "2").toInt())

        fun readYaml(input: InputStream): PluginDescriptor {
            val root = SafeYaml.read(input)
            val supported = setOf("name", "version", "main", "api-version", "depend", "softdepend", "loadbefore", "commands", "author", "authors", "description", "website")
            require(root.keys.all { it in supported }) { "Unsupported plugin.yml entries: ${root.keys - supported}" }
            fun text(key: String, default: String? = null) = root[key]?.let {
                require(it is String || it is Number) { "$key must be a string" }; it.toString()
            } ?: default
            fun strings(value: Any?, key: String): List<String> = when (value) {
                null -> emptyList()
                is String -> listOf(value)
                is List<*> -> value.map { require(it is String) { "$key must contain strings" }; it }
                else -> throw IllegalArgumentException("$key must be a string list")
            }
            fun ids(key: String) = strings(root[key], key).map { it.lowercase(Locale.ROOT) }.toSet()
            val name = requireNotNull(text("name")) { "Missing plugin name" }
            val api = text("api-version", "2.0")
            require(api in setOf("2", "2.0")) { "Unsupported Cerberus TS API version: $api" }
            val rawCommands = root["commands"] ?: emptyMap<String, Any?>()
            require(rawCommands is Map<*, *>) { "commands must be a mapping" }
            val commands = rawCommands.entries.associate { (key, value) ->
                require(key is String && key.matches(Regex("[a-z][a-z0-9_-]*"))) { "Invalid command name" }
                require(value == null || value is Map<*, *>) { "Command definition must be a mapping" }
                val fields = value ?: emptyMap<String, Any?>()
                require(fields.keys.all { it in setOf("description", "usage", "aliases", "permission", "permission-message") }) { "Unsupported command field: $key" }
                fun field(name: String, default: String = "") = fields[name]?.let {
                    require(it is String) { "$key.$name must be a string" }; it
                } ?: default
                val aliases = strings(fields["aliases"], "aliases")
                require(aliases.all { it.matches(Regex("[a-z][a-z0-9_-]*")) }) { "Invalid command alias" }
                key to CommandDescription(field("description"), field("usage"), aliases,
                    field("permission").takeIf(String::isNotBlank), field("permission-message", "Permission denied."))
            }
            return PluginDescriptor(name.lowercase(Locale.ROOT), requireNotNull(text("version")) { "Missing plugin version" },
                requireNotNull(text("main")) { "Missing plugin main class" }, ids("depend"), name = name,
                softDependencies = ids("softdepend"), loadBefore = ids("loadbefore"), commands = commands,
                authors = strings(root["authors"], "authors") + strings(root["author"], "author"),
                description = text("description", "").orEmpty(), website = text("website"))
        }
    }
}
