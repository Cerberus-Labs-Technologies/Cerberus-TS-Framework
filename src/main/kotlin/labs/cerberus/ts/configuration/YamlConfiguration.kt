package labs.cerberus.ts.configuration

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException

/** Shared safe YAML loader for plugin metadata and plugin configuration. */
internal object SafeYaml {
    fun read(input: InputStream): MutableMap<String, Any?> {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 20
            nestingDepthLimit = 30
            codePointLimit = 1024 * 1024
        }
        val bytes = input.readNBytes(1024 * 1024 + 1)
        require(bytes.size <= 1024 * 1024) { "YAML exceeds 1 MiB" }
        val root: Any? = Yaml(SafeConstructor(options)).load(bytes.toString(Charsets.UTF_8))
        fun normalize(value: Any?, depth: Int = 0): Any? {
            require(depth <= 30) { "Recursive or excessively nested YAML value" }
            return when (value) {
            is Map<*, *> -> value.entries.associateTo(linkedMapOf()) {
                require(it.key is String) { "YAML keys must be strings" }
                it.key as String to normalize(it.value, depth + 1)
            }
            is List<*> -> value.map { normalize(it, depth + 1) }
            null, is String, is Number, is Boolean -> value
            else -> throw IllegalArgumentException("Unsupported YAML value")
            }
        }
        require(root == null || root is Map<*, *>) { "YAML root must be a mapping" }
        @Suppress("UNCHECKED_CAST")
        return normalize(root ?: emptyMap<String, Any?>()) as MutableMap<String, Any?>
    }
}

class YamlConfiguration internal constructor(private val values: MutableMap<String, Any?>, private val source: Path? = null) {
    private var defaults: Map<String, Any?> = emptyMap()
    fun loadDefaults(input: InputStream) { defaults = SafeYaml.read(input) }
    fun reload() {
        val file = checkNotNull(source) { "Configuration has no source file" }
        val updated = read(file)
        values.clear(); values.putAll(updated)
    }
    fun get(path: String): Any? {
        fun find(root: Map<String, Any?>) = path.split('.').fold(root as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }
        return find(values) ?: find(defaults)
    }
    fun contains(path: String) = get(path) != null
    @JvmOverloads fun getString(path: String, default: String? = null): String? = get(path)?.toString() ?: default
    @JvmOverloads fun getInt(path: String, default: Int = 0): Int = (get(path) as? Number)?.toInt() ?: default
    @JvmOverloads fun getBoolean(path: String, default: Boolean = false): Boolean = get(path) as? Boolean ?: default
    fun getStringList(path: String): List<String> = (get(path) as? List<*>)?.mapNotNull { it as? String }.orEmpty()
    fun set(path: String, value: Any?) {
        val keys = path.split('.').also { require(it.all(String::isNotBlank)) }
        var current = values
        keys.dropLast(1).forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val child = current[key] as? MutableMap<String, Any?> ?: linkedMapOf<String, Any?>().also { current[key] = it }
            current = child
        }
        if (value == null) current.remove(keys.last()) else current[keys.last()] = value
    }
    fun save() = save(checkNotNull(source) { "Configuration has no source file" })
    fun save(file: Path) {
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".config-", ".yml")
        try {
            val text = if (file.fileName.toString().endsWith(".json", true))
                com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(values)
            else Yaml().dump(values)
            Files.writeString(temporary, text, Charsets.UTF_8)
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object {
        private fun read(file: Path): MutableMap<String, Any?> {
            if (!Files.exists(file)) return linkedMapOf()
            val bytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "Configuration exceeds 1 MiB" }
            if (file.fileName.toString().endsWith(".json", true)) {
                val mapper = com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                require(mapper.readTree(bytes)?.isObject == true) { "JSON configuration root must be an object" }
            }
            return bytes.inputStream().use(SafeYaml::read)
        }
        fun loadConfiguration(file: Path): YamlConfiguration = YamlConfiguration(read(file), file)
    }
}
