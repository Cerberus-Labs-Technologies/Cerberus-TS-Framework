package labs.cerberus.ts.service

import labs.cerberus.ts.configuration.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale

/** No data-class toString: SMTP credentials must not become diagnostic output. */
class SmtpTransportConfig private constructor(val id: String, private val host: String, private val port: Int,
    private val from: String, private val username: String?, private val password: String?, private val tls: SmtpTls,
    private val properties: Map<String, String>) {
    internal fun open() = MailService(host, port, from, username, password, tls = tls, extraProperties = properties)
    override fun toString() = "SmtpTransportConfig(id=$id)"

    companion object {
        @JvmStatic fun read(file: Path): SmtpTransportConfig {
            require(Files.isRegularFile(file)) { "SMTP configuration must be a regular file" }
            val config = try { YamlConfiguration.loadConfiguration(file) }
            catch (_: Exception) { throw IllegalArgumentException("Cannot parse SMTP configuration: ${file.fileName}") }
            fun scalar(name: String, default: String? = null): String? = config.get(name)?.let {
                require(it is String || it is Number || it is Boolean) { "SMTP setting must be scalar: $name" }
                it.toString()
            } ?: default
            fun boolean(name: String, default: Boolean) = scalar(name)?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull()
                ?: if (config.contains(name)) throw IllegalArgumentException("SMTP setting must be boolean: $name") else default
            val id = scalar("id", file.fileName.toString().substringBeforeLast('.'))!!
            require(id.isNotBlank() && id.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid SMTP unit ID" }
            val properties = config.get("properties")?.let { value ->
                require(value is Map<*, *>) { "SMTP properties must be a mapping" }
                value.entries.associate { (key, property) ->
                    require(key is String && key.isNotBlank() && (property is String || property is Number || property is Boolean)) { "SMTP property must have a name and scalar value" }
                    key to property.toString()
                }
            }.orEmpty()
            val tls = scalar("tls")?.let { value -> SmtpTls.entries.firstOrNull { it.name.equals(value, true) }
                ?: throw IllegalArgumentException("Invalid SMTP TLS mode") }
                ?: if (boolean("implicitTls", false)) SmtpTls.IMPLICIT else if (boolean("starttls", true)) SmtpTls.STARTTLS else SmtpTls.NONE
            // Legacy units used port 25; the direct constructor keeps its submission default (587).
            val port = scalar("port", "25")!!.toIntOrNull() ?: throw IllegalArgumentException("Invalid SMTP port")
            return SmtpTransportConfig(id, scalar("host", "localhost")!!, port, scalar("from", "mail@localhost")!!,
                scalar("username") ?: scalar("user"), scalar("pass") ?: scalar("password"), tls, properties)
        }
    }
}

/** Owns configured units; plugin-registered transports retain caller ownership. */
class MailTransports : AutoCloseable {
    private class Entry(val transport: MailTransport, val owned: Boolean)
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    @Synchronized fun register(name: String, transport: MailTransport): AutoCloseable {
        check(!closed) { "Mail transports are closed" }
        require(name.isNotBlank()); check(name !in entries) { "Mail transport already registered: $name" }
        val entry = Entry(transport, false); entries[name] = entry
        return AutoCloseable { synchronized(this) { if (entries[name] === entry) entries.remove(name) } }
    }
    @Synchronized fun find(name: String = "default"): MailTransport? = entries[name]?.transport
    fun require(name: String = "default"): MailTransport = find(name) ?: error("Mail transport not registered: $name")
    @Synchronized fun unitIds(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(entries.keys))

    /** Create all units before publishing any. No SMTP connection is opened here. */
    @Synchronized fun load(directory: Path): Set<String> {
        check(!closed) { "Mail transports are closed" }
        if (!Files.exists(directory)) return emptySet()
        require(Files.isDirectory(directory)) { "Mail configuration path must be a directory" }
        val configs = Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) &&
            it.fileName.toString().substringAfterLast('.').lowercase(Locale.ROOT) in setOf("json", "yaml", "yml") }
            .sorted().map(SmtpTransportConfig::read).toList() }
        require(configs.map { it.id }.distinct().size == configs.size) { "Duplicate SMTP unit configuration" }
        require(configs.none { it.id in entries }) { "SMTP unit already registered" }
        val created = linkedMapOf<String, MailService>()
        try { configs.forEach { created[it.id] = it.open() } }
        catch (error: Throwable) {
            created.values.toList().asReversed().forEach { try { it.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } }
            throw error
        }
        created.forEach { (id, service) -> entries[id] = Entry(service, true) }
        return Collections.unmodifiableSet(LinkedHashSet(created.keys))
    }
    fun remove(name: String): Boolean {
        val entry = synchronized(this) { entries.remove(name) } ?: return false
        if (entry.owned) (entry.transport as AutoCloseable).close()
        return true
    }
    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            entries.values.filter { it.owned }.map { it.transport as AutoCloseable }.asReversed().also { entries.clear() }
        }
        var failure: Exception? = null
        owned.forEach { try { it.close() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) } }
        failure?.let { throw it }
    }
}
