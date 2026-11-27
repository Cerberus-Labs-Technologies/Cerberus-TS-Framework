package labs.cerberus.ts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.hibernate.Session
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.StandardServiceRegistry
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.sql.DataSource
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Explicit JDBC URL, bounded pool and Hibernate properties. Credentials are omitted from toString. */
class PersistenceUnitConfig(val id: String, val jdbcUrl: String, val username: String? = null, val password: String? = null,
    pool: Map<String, String> = emptyMap(), hibernate: Map<String, String> = emptyMap(),
    entities: Set<String> = emptySet()) {
    val pool: Map<String, String> = java.util.Map.copyOf(pool)
    val hibernate: Map<String, String> = java.util.Map.copyOf(hibernate)
    val entities: Set<String> = java.util.Set.copyOf(entities)
    init {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Invalid persistence unit ID" }
        require(jdbcUrl.startsWith("jdbc:")) { "Invalid JDBC URL" }
        require(pool.keys.none { it in setOf("jdbcUrl", "username", "password", "dataSource", "dataSourceClassName", "dataSourceJNDI") }) { "Use top-level connection settings" }
        require(hibernate.keys.none { it.startsWith("hibernate.connection.") || it.startsWith("jakarta.persistence.jdbc.") ||
            it.startsWith("jakarta.persistence.jtaDataSource") || it.startsWith("jakarta.persistence.nonJtaDataSource") || it == "hibernate.multi_tenant_connection_provider" }) { "Hibernate connection settings cannot replace the managed pool" }
    }
    override fun toString() = "PersistenceUnitConfig(id=$id)"
    companion object {
        /** YAML also accepts Jeak's JSON configuration, including url/user/pass/dataSourceOpts. */
        fun read(path: Path): PersistenceUnitConfig {
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false; codePointLimit = 1_048_576; maxAliasesForCollections = 20 }
            val root = Files.newBufferedReader(path).use { Yaml(SafeConstructor(options)).load<Any?>(it) } as? Map<*, *>
                ?: error("Persistence unit configuration must be an object: ${path.fileName}")
            require(root.keys.all { it in setOf("id", "jdbcUrl", "url", "username", "user", "password", "pass", "host", "port", "schema", "driver", "dataSourceOpts", "pool", "hibernate", "entities") }) { "Unknown persistence unit setting in ${path.fileName}" }
            fun string(name: String, default: String? = null): String? = root[name]?.let {
                require(it is String || it is Number || it is Boolean) { "Expected scalar setting: $name" }; it.toString()
            } ?: default
            fun properties(name: String): Map<String, String> {
                val values = root[name] ?: return emptyMap()
                require(values is Map<*, *>) { "Expected settings object: $name" }
                return values.entries.associate { (key, value) ->
                    require(key is String && (value is String || value is Number || value is Boolean)) { "Expected scalar property: $name" }
                    key to value.toString()
                }
            }
            val id = string("id", path.fileName.toString().substringBeforeLast('.'))!!
            val url = string("jdbcUrl") ?: string("url") ?: run {
                val driver = string("driver", "mariadb")!!; val host = string("host", "localhost")!!
                val port = string("port", "3306")!!; val schema = string("schema", "jeakbot")!!
                require(driver.matches(Regex("[A-Za-z0-9]+")) && port.toInt() in 1..65535)
                "jdbc:$driver://$host:$port/$schema"
            }
            val entityNames = root["entities"]?.let { value ->
                require(value is List<*> && value.all { it is String && it.isNotBlank() }) { "entities must contain class names" }
                value.map { it as String }.toSet()
            }.orEmpty()
            return PersistenceUnitConfig(id, url, string("username") ?: string("user"), string("password") ?: string("pass"),
                properties("dataSourceOpts") + properties("pool"), properties("hibernate"), entityNames)
        }
    }
}

/** Owns its pool, ORM factory and every EntityManager opened through this API. */
class PersistenceUnit private constructor(val config: PersistenceUnitConfig, private val pool: HikariDataSource,
    private val registry: StandardServiceRegistry, private val factory: SessionFactory) : AutoCloseable {
    val id get() = config.id
    val dataSource: DataSource get() = pool
    private val lock = ReentrantReadWriteLock(true)
    private var closed = false
    private val managers = IdentityHashMap<EntityManager, EntityManager>()
    private val transactions = TransactionRunner(open = { entityManager },
        begin = { it.transaction.begin() }, commit = { it.transaction.commit() },
        rollback = { if (it.transaction.isActive) it.transaction.rollback() }, release = { it.close() })
    val entityManager: EntityManager get() = lock.read {
        check(!closed) { "Persistence unit is closed" }
        val actual = factory.createEntityManager()
        val proxy = Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java)) { proxy, method, args ->
            when {
                method.name == "equals" && method.parameterCount == 1 -> proxy === args!![0]
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "toString" && method.parameterCount == 0 -> "ManagedEntityManager(unit=$id)"
                method.name == "close" && method.parameterCount == 0 -> lock.read {
                    synchronized(managers) { managers.remove(proxy) }?.close(); null
                }
                method.name == "isOpen" -> lock.read { !closed && actual.isOpen }
                else -> lock.read {
                    check(!closed) { "Persistence unit is closed" }
                    try { method.invoke(actual, *(args ?: emptyArray())) } catch (error: InvocationTargetException) { throw error.targetException }
                }
            }
        } as EntityManager
        synchronized(managers) { managers[proxy] = actual }
        proxy
    }
    fun <T> withEntityManager(action: (EntityManager) -> T): T = transactions.execute(action)
    internal val activeOnCurrentThread: Boolean get() = transactions.activeOnCurrentThread
    fun <T> withConnection(action: (java.sql.Connection) -> T): T = withEntityManager { manager ->
        manager.flush()
        jdbc(manager, action)
    }
    internal fun <T> withConnection(action: (java.sql.Connection) -> T, onError: (Throwable) -> Unit): T? =
        withEntityManager({ manager -> manager.flush(); jdbc(manager, action) }, onError)
    private fun <T> jdbc(manager: EntityManager, action: (java.sql.Connection) -> T): T {
        var result: T? = null
        manager.unwrap(Session::class.java).doWork { connection: java.sql.Connection -> result = action(connection) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    /** On nested failure the callback runs after the outer rollback; result is null. */
    fun <T> withEntityManager(action: (EntityManager) -> T, onError: (Throwable) -> Unit): T? = transactions.recover(action, onError)
    override fun close() {
        transactions.close() // waits for active transactions; rejects closing from inside one
        lock.write {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } }
            val open = synchronized(managers) { managers.values.toList().also { managers.clear() } }
            open.forEach { manager -> cleanup { if (manager.isOpen) {
                try { if (manager.transaction.isActive) manager.transaction.rollback() } finally { manager.close() }
            } } }
            cleanup { factory.close() }; cleanup { StandardServiceRegistryBuilder.destroy(registry) }; cleanup { pool.close() }
            failure?.let { throw it }
        }
    }
    companion object {
        internal fun open(config: PersistenceUnitConfig, entities: Collection<Class<*>>, loader: ClassLoader): PersistenceUnit {
            val thread = Thread.currentThread()
            val previousLoader = thread.contextClassLoader
            thread.contextClassLoader = loader
            try { return initialize(config, entities, loader) }
            finally { thread.contextClassLoader = previousLoader }
        }
        private fun initialize(config: PersistenceUnitConfig, entities: Collection<Class<*>>, loader: ClassLoader): PersistenceUnit {
            val hikari = Properties().apply {
                setProperty("maximumPoolSize", "4"); setProperty("minimumIdle", "0"); setProperty("connectionTimeout", "10000")
                setProperty("poolName", "cerberus-${config.id}"); putAll(config.pool)
            }
            val pool = HikariDataSource(HikariConfig(hikari).apply {
                jdbcUrl = config.jdbcUrl; username = config.username; password = config.password
            })
            var registry: StandardServiceRegistry? = null
            try {
                val bootstrap = BootstrapServiceRegistryBuilder().applyClassLoader(loader).enableAutoClose().build()
                registry = StandardServiceRegistryBuilder(bootstrap).applySettings(config.hibernate)
                    .applySetting("hibernate.connection.datasource", pool).build()
                val metadata = MetadataSources(registry)
                val selected = if (config.entities.isEmpty()) entities else config.entities.map { Class.forName(it, false, loader) }
                selected.distinct().forEach(metadata::addAnnotatedClass)
                return PersistenceUnit(config, pool, registry, metadata.buildMetadata().buildSessionFactory())
            } catch (error: Throwable) {
                try { registry?.let(StandardServiceRegistryBuilder::destroy) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                try { pool.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
