package labs.cerberus.ts

import jakarta.persistence.Entity
import labs.cerberus.ts.event.*
import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class PluginPersistenceTest {
    @TempDir lateinit var directory: Path
    private val marker = "cerberus.test.entity.initialized"
    private fun pluginJar(): Path {
        val source = Files.createDirectories(directory.resolve("java/fixture"))
        Files.writeString(source.resolve("Record.java"), """
            package fixture;
            @jakarta.persistence.Entity @jakarta.persistence.Table(name="plugin_records")
            public class Record {
                static { System.setProperty("$marker", "true"); }
                @jakarta.persistence.Id public int id;
                public String label;
                public Record() {}
                public Record(int id, String label) { this.id=id; this.label=label; }
            }
        """.trimIndent())
        Files.writeString(source.resolve("FixturePlugin.java"), """
            package fixture;
            public class FixturePlugin implements labs.cerberus.ts.plugin.CerberusPlugin {
                @labs.cerberus.ts.service.Inject public labs.cerberus.ts.service.DatabaseService database;
                public void onLoad(labs.cerberus.ts.plugin.PluginContext context) {}
                public void onEnable() {
                    database.requireUnit("default").withEntityManager(manager -> {
                        manager.persist(new Record(1, "plugin enabled")); return null;
                    });
                }
                public void onDisable() {}
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("compiled"))
        val classpath = listOf(Entity::class.java, CerberusPlugin::class.java, Function1::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val errors = java.io.StringWriter()
        compiler.getStandardFileManager(null, null, null).use { files ->
            val inputs = files.getJavaFileObjectsFromPaths(listOf(source.resolve("Record.java"), source.resolve("FixturePlugin.java")))
            assertTrue(compiler.getTask(errors, files, null, listOf("-proc:none", "-classpath", classpath, "-d", classes.toString()), null, inputs).call(), errors.toString())
        }
        val plugins = Files.createDirectories(directory.resolve("plugins"))
        JarOutputStream(Files.newOutputStream(plugins.resolve("fixture.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml"))
            jar.write("name: Fixture\nversion: 1.0.0\nmain: fixture.FixturePlugin\napi-version: '2.0'\n".toByteArray()); jar.closeEntry()
            Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, jar); jar.closeEntry()
            } }
        }
        return plugins
    }
    private fun databaseConfig(data: Path) {
        val configs = Files.createDirectories(data.resolve("database"))
        Files.writeString(configs.resolve("default.yml"), """
            jdbcUrl: 'jdbc:h2:mem:${UUID.randomUUID()}'
            hibernate:
              hibernate.hbm2ddl.auto: create-drop
        """.trimIndent())
    }

    @Test fun `actual java plugin jar entities are discovered without initialization and available on enable`() {
        val plugins = pluginJar(); val data = directory.resolve("data"); databaseConfig(data)
        System.clearProperty(marker)
        DatabaseService().use { db ->
            val services = Services().apply { register(EventBus::class.java, EventBus(db)); register(DatabaseService::class.java, db) }
            PluginManager(services, data.resolve("plugins")).use { manager ->
                manager.load(plugins)
                val mapped = manager.persistenceClasses()
                assertEquals(setOf("fixture.Record"), mapped.map { it.name }.toSet())
                assertNull(System.getProperty(marker))
                db.load(data.resolve("database"), mapped, manager.persistenceClassLoader())
                manager.enable()
                assertEquals("true", System.getProperty(marker))
                assertEquals(1, db.transaction { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from plugin_records where label='plugin enabled'").use { it.next(); it.getInt(1) }
                } })
            }
        }
        System.clearProperty(marker)
    }

    @Test fun `bot bootstraps configured persistence after discovery and before plugin enable`() {
        val plugins = pluginJar(); val data = directory.resolve("bot-data"); databaseConfig(data)
        val config = BotConfig(ConnectionConfig(password = "fixture", minimumCommandInterval = Duration.ZERO), dataDirectory = data,
            pluginDirectory = plugins, reconnectAttempts = 0, webEnabled = false)
        CerberusBot(config) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = true
                override fun execute(command: QueryCommand) = QueryResponse(when (command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "99", "virtualserver_id" to "1"))
                    else -> emptyList()
                }, QueryError(0, "ok"))
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }.use { bot ->
            bot.start()
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (bot.state != BotState.CONNECTED && bot.state != BotState.STOPPED && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(BotState.CONNECTED, bot.state); assertFalse(bot.failed)
            assertEquals(setOf("default"), bot.database.unitIds())
            assertEquals(1, bot.database.transaction { connection -> connection.createStatement().use { sql ->
                sql.executeQuery("select count(*) from plugin_records").use { it.next(); it.getInt(1) }
            } })
        }
        System.clearProperty(marker)
    }
}
