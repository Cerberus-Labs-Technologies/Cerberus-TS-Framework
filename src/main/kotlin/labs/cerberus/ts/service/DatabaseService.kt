package labs.cerberus.ts.service

import jakarta.persistence.EntityManager
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Manages application databases; never reads TeamSpeak's internal database schema. */
class DatabaseService : AutoCloseable {
    private data class Lease(val connection: Connection, val autoCommit: Boolean)
    private class Source(val dataSource: DataSource, val unit: PersistenceUnit?) {
        val transactions = TransactionRunner(
            open = { dataSource.connection.let { connection ->
                try { Lease(connection, connection.autoCommit) }
                catch (error: Throwable) { try { connection.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
            } },
            begin = { it.connection.autoCommit = false }, commit = { it.connection.commit() },
            rollback = { it.connection.rollback() }, release = { lease ->
                try { lease.connection.autoCommit = lease.autoCommit } finally { lease.connection.close() }
            })
        fun checkCanClose() { check(!transactions.activeOnCurrentThread && unit?.activeOnCurrentThread != true) { "Cannot close a database from its transaction" } }
        fun close() { checkCanClose(); transactions.close(); unit?.close() }
    }
    private val sources = linkedMapOf<String, Source>()
    private var closed = false

    /** Caller retains ownership of an externally supplied DataSource. */
    @Synchronized fun register(name: String, source: DataSource): AutoCloseable = registerSource(name, Source(source, null))

    @Synchronized fun createUnit(config: PersistenceUnitConfig, entities: Collection<Class<*>> = emptyList(),
        loader: ClassLoader = javaClass.classLoader): PersistenceUnit {
        check(!closed && config.id !in sources) { "Database service closed or unit already registered" }
        val unit = PersistenceUnit.open(config, entities, loader)
        try { registerSource(config.id, Source(unit.dataSource, unit)) }
        catch (error: Throwable) { try { unit.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
        return unit
    }

    /** Reads all configs before opening connections; failure removes only units created by this call. */
    @Synchronized fun load(directory: Path, entities: Collection<Class<*>> = emptyList(),
        loader: ClassLoader = javaClass.classLoader): List<PersistenceUnit> {
        check(!closed)
        if (!Files.exists(directory)) return emptyList()
        require(Files.isDirectory(directory)) { "Database configuration path must be a directory" }
        val configs = Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) &&
            it.fileName.toString().substringAfterLast('.') in setOf("json", "yaml", "yml") }.sorted().map(PersistenceUnitConfig::read).toList() }
        require(configs.map { it.id }.distinct().size == configs.size) { "Duplicate persistence unit configuration" }
        require(configs.none { it.id in sources }) { "Persistence unit already registered" }
        val created = mutableListOf<PersistenceUnit>()
        try { configs.forEach { created += createUnit(it, entities, loader) }; return created.toList() }
        catch (error: Throwable) {
            created.asReversed().forEach { unit -> try { sources.remove(unit.id)?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } }
            throw error
        }
    }

    @Synchronized fun findUnit(name: String): PersistenceUnit? = sources[name]?.unit
    fun requireUnit(name: String = "default"): PersistenceUnit = findUnit(name) ?: error("Missing persistence unit: $name")
    @Synchronized fun unitIds(): Set<String> = sources.filterValues { it.unit != null }.keys.toSet()
    /** Finishes active work, closes an owned unit and releases its ID for a later registration. */
    fun removeUnit(name: String): Boolean {
        val entry = synchronized(this) { sources[name]?.takeIf { it.unit != null } } ?: return false
        entry.close()
        return synchronized(this) { if (sources[name] === entry) { sources.remove(name); true } else false }
    }
    @Synchronized private fun source(name: String): Source {
        check(!closed) { "Database service is closed" }
        return sources[name] ?: error("Missing DataSource: $name")
    }
    fun <T> transaction(name: String = "default", action: (Connection) -> T): T {
        val source = source(name)
        return if (source.unit != null) source.unit.withConnection(action) else source.transactions.execute { action(it.connection) }
    }
    fun <T> transaction(name: String, action: (Connection) -> T, onError: (Throwable) -> Unit): T? {
        val source = source(name)
        return if (source.unit != null) source.unit.withConnection(action, onError) else source.transactions.recover({ action(it.connection) }, onError)
    }
    fun <T> withEntityManager(name: String = "default", action: (EntityManager) -> T): T = requireUnit(name).withEntityManager(action)

    private fun registerSource(name: String, source: Source): AutoCloseable {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Invalid database unit ID" }
        check(!closed && name !in sources) { "Database service closed or source already registered" }
        sources[name] = source
        val active = AtomicBoolean(true)
        return AutoCloseable {
            if (active.get()) {
                // Do not hold the registry monitor while waiting for in-flight transactions.
                source.close()
                synchronized(this) { if (sources[name] === source) sources.remove(name); active.set(false) }
            }
        }
    }
    override fun close() {
        val entries = synchronized(this) {
            if (closed) return
            sources.values.forEach { it.checkCanClose() }
            closed = true; sources.values.toList().also { sources.clear() }
        }
        var failure: Throwable? = null
        entries.asReversed().forEach { source -> try { source.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } }
        failure?.let { throw it }
    }
}
