package labs.cerberus.ts.configuration

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Named/categorized configuration files; regular JavaPlugin config.yml remains plugin-owned. */
class ConfigurationService(private val directory: Path) {
    private val configurations = ConcurrentHashMap<Path, YamlConfiguration>()
    fun path(id: String, owner: String? = null, category: String = "", format: String = "json"): Path {
        fun segment(value: String) {
            require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) && value.length <= 160) { "Invalid configuration path component" }
        }
        segment(id); owner?.let(::segment)
        val extension = when (format.lowercase(Locale.ROOT)) {
            "", "json", "application/json" -> "json"
            "yaml", "yml", "application/yaml" -> "yml"
            else -> throw IllegalArgumentException("Unsupported configuration format")
        }
        var parent = directory.toAbsolutePath().normalize()
        if (owner != null) parent = parent.resolve(owner)
        if (category.isNotEmpty()) category.split('/').forEach { segment(it); parent = parent.resolve(it) }
        Files.createDirectories(parent)
        return parent.resolve("$id.$extension")
    }
    fun configuration(id: String, owner: String? = null, category: String = "", format: String = "json"): YamlConfiguration =
        configurations.computeIfAbsent(path(id, owner, category, format), YamlConfiguration::loadConfiguration)
}
