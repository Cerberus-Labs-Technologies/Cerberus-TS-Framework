package labs.cerberus.ts

import labs.cerberus.ts.command.*
import labs.cerberus.ts.configuration.YamlConfiguration
import labs.cerberus.ts.event.*
import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

data class PluginProbe(val message: String)

class PaperFixturePlugin : JavaPlugin(), Listener {
    override fun onLoad() { trace += "load:$name:${getCommand("greet") != null}:$isEnabled" }
    override fun onEnable() {
        trace += "enable:$isEnabled"
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        getCommand("greet")!!.setExecutor { sender, command, label, args ->
            if (args.isEmpty()) false else {
                sender.sendMessage("${config.getString("greeting")}:${sender.name}:${command.name}:$label:${args.joinToString("|")}:${sender.hasPermission("test.allowed")}")
                true
            }
        }
    }
    @EventHandler fun onProbe(event: PluginProbe) { trace += event.message }
    override fun onDisable() { trace += "disable:$isEnabled" }
    companion object { val trace = mutableListOf<String>() }
}

class DefaultExecutorPlugin : JavaPlugin() {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        sender.sendMessage("default:$label:${args.joinToString("|")}")
        return true
    }
}

class EnablingFailurePlugin : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        throw IllegalStateException("Enable failure")
    }
    @EventHandler fun onProbe(event: PluginProbe) { calls++ }
    companion object { var calls = 0 }
}

class PaperPluginTest {
    @TempDir lateinit var directory: Path

    private fun jar(name: String, main: String = "labs.cerberus.ts.PaperFixturePlugin", extra: String = "", greeting: String = "Hallo"): Path {
        val path = directory.resolve("jars/$name.jar")
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            fun resource(name: String, text: String) {
                jar.putNextEntry(JarEntry(name)); jar.write(text.toByteArray()); jar.closeEntry()
            }
            resource("plugin.yml", """
                name: $name
                version: '1.0'
                main: $main
                api-version: '2.0'
                commands:
                  greet:
                    usage: '<command> <name>'
                    aliases: [hi]
                $extra
            """.trimIndent())
            resource("config.yml", "greeting: $greeting\n")
        }
        return path
    }

    private fun fixture(action: (PluginManager, CommandService, EventBus, TeamSpeakServer) -> Unit) {
        val services = Services()
        val events = EventBus { throw AssertionError("Event handler failed", it) }
        val server = TeamSpeakServer(ConnectionConfig(password = "test"), events) { FakeQuery() }
        val commands = CommandService(server, PermissionService(directory.resolve("permissions.json")))
        services.register(EventBus::class.java, events)
        services.register(CommandService::class.java, commands)
        services.register(TeamSpeakServer::class.java, server)
        TaskService().use { tasks ->
            services.register(TaskService::class.java, tasks)
            PluginManager(services, directory.resolve("data")).use { manager -> action(manager, commands, events, server) }
        }
    }

    @Test fun `Paper lifecycle metadata commands aliases raw args usage and listeners work together`() {
        jar("Example")
        PaperFixturePlugin.trace.clear()
        fixture { manager, commands, events, _ ->
            manager.load(directory.resolve("jars"))
            val plugin = manager.getPlugin("EXAMPLE") as JavaPlugin
            assertEquals(listOf("load:Example:true:false"), PaperFixturePlugin.trace)
            assertFalse(plugin.isEnabled)
            val replies = mutableListOf<String>()
            commands.console("greet user", replies::add)
            assertEquals(listOf("Plugin is not enabled."), replies)
            manager.enable(); manager.enable()
            assertTrue(plugin.isEnabled)
            assertEquals(1, PaperFixturePlugin.trace.count { it.startsWith("enable:") })
            replies.clear()
            commands.console("hi \"Kotlin Welt\" --loud -12", replies::add)
            assertEquals(listOf("Hallo:CONSOLE:greet:hi:Kotlin Welt|--loud|-12:true"), replies)
            replies.clear(); commands.console("hi", replies::add)
            assertEquals(listOf("!hi <name>"), replies)
            events.publish(PluginProbe("once"))
            assertEquals(1, PaperFixturePlugin.trace.count { it == "once" })
            manager.close()
            assertFalse(plugin.isEnabled)
            events.publish(PluginProbe("after-disable"))
            assertFalse("after-disable" in PaperFixturePlugin.trace)
            assertEquals("disable:false", PaperFixturePlugin.trace.last())
            replies.clear(); commands.console("hi user", replies::add)
            assertTrue(replies.single().startsWith("Unknown command"))
        }
    }

    @Test fun `default onCommand executor is used without setExecutor`() {
        jar("Default", "labs.cerberus.ts.DefaultExecutorPlugin")
        fixture { manager, commands, _, _ ->
            manager.load(directory.resolve("jars")); manager.enable()
            val replies = mutableListOf<String>()
            commands.console("hi --name=Anna", replies::add)
            assertEquals(listOf("default:hi:--name=Anna"), replies)
        }
    }

    @Test fun `config defaults are copied once and reload save preserve operator changes`() {
        jar("Example")
        fixture { manager, _, _, _ ->
            manager.load(directory.resolve("jars")); manager.enable()
            val plugin = manager.getPlugin("example") as JavaPlugin
            assertEquals("Hallo", plugin.config.getString("greeting"))
            Files.writeString(plugin.dataFolder.toPath().resolve("config.yml"), "greeting: Servus\n")
            plugin.saveDefaultConfig(); plugin.reloadConfig()
            assertEquals("Servus", plugin.config.getString("greeting"))
            plugin.config.set("nested.number", 42); plugin.saveConfig(); plugin.reloadConfig()
            assertEquals(42, plugin.config.getInt("nested.number"))
            Files.writeString(plugin.dataFolder.toPath().resolve("config.yml"), "nested: {number: 21}\n")
            plugin.reloadConfig()
            assertEquals("Hallo", plugin.config.getString("greeting"))
            assertEquals(21, plugin.config.getInt("nested.number"))
        }
    }

    @Test fun `each plugin reads bundled resources from its own jar`() {
        jar("Alpha", "labs.cerberus.ts.DefaultExecutorPlugin", greeting = "Alpha")
        // Separate command names avoid a deliberate collision in the global command registry.
        val beta = directory.resolve("jars/Beta.jar")
        JarOutputStream(Files.newOutputStream(beta)).use { out ->
            out.putNextEntry(JarEntry("plugin.yml")); out.write("name: Beta\nversion: '1'\nmain: labs.cerberus.ts.DefaultExecutorPlugin\n".toByteArray()); out.closeEntry()
            out.putNextEntry(JarEntry("config.yml")); out.write("greeting: Beta\n".toByteArray()); out.closeEntry()
        }
        fixture { manager, _, _, _ ->
            manager.load(directory.resolve("jars"))
            for (name in listOf("Alpha", "Beta")) {
                val plugin = manager.getPlugin(name) as JavaPlugin
                plugin.saveDefaultConfig()
                assertEquals(name, plugin.config.getString("greeting"))
            }
        }
    }

    @Test fun `failed enable removes declared commands and registered event handlers`() {
        jar("Broken", "labs.cerberus.ts.EnablingFailurePlugin")
        EnablingFailurePlugin.calls = 0
        fixture { manager, commands, events, _ ->
            manager.load(directory.resolve("jars"))
            val plugin = manager.getPlugin("broken") as JavaPlugin
            assertFails { manager.enable() }
            assertFalse(plugin.isEnabled)
            events.publish(PluginProbe("ignored"))
            assertEquals(0, EnablingFailurePlugin.calls)
            val replies = mutableListOf<String>()
            commands.console("greet test", replies::add)
            assertTrue(replies.single().startsWith("Unknown command"))
        }
    }

    @Test fun `scheduler supports tick notation cancellation and plugin ownership`() {
        jar("Example")
        fixture { manager, _, _, _ ->
            manager.load(directory.resolve("jars")); manager.enable()
            val plugin = manager.getPlugin("example") as JavaPlugin
            val ran = CountDownLatch(1)
            plugin.server.scheduler.runTaskLater(plugin, Runnable { ran.countDown() }, 1)
            assertTrue(ran.await(2, TimeUnit.SECONDS))
            val cancelled = CountDownLatch(1)
            plugin.server.scheduler.runTaskLater(plugin, Runnable { cancelled.countDown() }, 2).cancel()
            assertFalse(cancelled.await(150, TimeUnit.MILLISECONDS))
            val disabled = CountDownLatch(1)
            plugin.server.scheduler.runTaskLater(plugin, Runnable { disabled.countDown() }, 2)
            manager.close()
            assertFalse(disabled.await(150, TimeUnit.MILLISECONDS))
            assertFails { plugin.server.scheduler.runTask(plugin, Runnable { }) }
        }
    }

    @Test fun `Paper scheduler serializes normal tasks keeps async work independent and permanently cancels repeats on unload`() {
        jar("Example")
        fixture { manager, _, _, _ ->
            manager.load(directory.resolve("jars")); manager.enable()
            val plugin = manager.getPlugin("example") as JavaPlugin
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val asyncRan = CountDownLatch(1)
            val interrupted = CountDownLatch(1); val queuedRan = CountDownLatch(1)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val repeat = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                calls.incrementAndGet(); entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { interrupted.countDown() }
            }, 0, 1)
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val queued = plugin.server.scheduler.runTask(plugin, Runnable { queuedRan.countDown() })
                val async = plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { asyncRan.countDown() })
                assertTrue(asyncRan.await(3, TimeUnit.SECONDS)); assertTrue(async.awaitTermination(java.time.Duration.ofSeconds(3)))
                assertEquals(TaskExecution.SERIAL, repeat.snapshot.execution); assertEquals(TaskExecution.ASYNC, async.snapshot.execution)
                assertEquals(1, queuedRan.count)
                manager.close()
                assertTrue(interrupted.await(3, TimeUnit.SECONDS)); assertTrue(repeat.isCancelled); assertTrue(queued.isCancelled)
                release.countDown(); assertTrue(repeat.awaitTermination(java.time.Duration.ofSeconds(3)))
                assertEquals(1, calls.get()); assertEquals(1, queuedRan.count)
                assertFails { plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {}) }
            } finally { release.countDown() }
        }
    }

    @Test fun `YAML rejects duplicate keys unsafe tags recursive values and unknown descriptor fields`() {
        fun read(text: String) = PluginDescriptor.readYaml(text.byteInputStream())
        val base = "name: Example\nversion: '1'\nmain: example.Main\n"
        assertFails { read(base + "name: Duplicate\n") }
        assertFails { read("!!java.lang.Runtime {}") }
        assertFails { read(base + "api-version: '99.0'\n") }
        assertFails { read(base + "permissions: {}\n") }
        val file = directory.resolve("recursive.yml")
        Files.writeString(file, "value: &root {child: *root}\n")
        assertFailsWith<IllegalArgumentException> { YamlConfiguration.loadConfiguration(file) }
    }

    @Test fun `optional dependency order tolerates absent plugins and optional cycles`() {
        val a = PluginDescriptor("a", "1", "Main", softDependencies = setOf("b", "missing"))
        val b = PluginDescriptor("b", "1", "Main", softDependencies = setOf("a"))
        assertEquals(listOf("b", "a"), PluginManager.dependencyOrder(listOf(a, b)).map { it.id })
        val first = a.copy(softDependencies = emptySet(), loadBefore = setOf("b"))
        assertEquals(listOf("a", "b"), PluginManager.dependencyOrder(listOf(b, first)).map { it.id })
    }

    @Test fun `plugin YAML command permissions and sender permission checks use fresh TeamSpeak identity`() {
        val path = directory.resolve("jars/Example.jar")
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml"))
            jar.write("""
                name: Example
                version: '1'
                main: labs.cerberus.ts.PaperFixturePlugin
                commands:
                  greet:
                    aliases: [hi]
                    permission: example.greet
                    permission-message: Kein Zugriff.
            """.trimIndent().toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("config.yml")); jar.write("greeting: Hallo\n".toByteArray()); jar.closeEntry()
        }
        val replies = mutableListOf<String>()
        val base = FakeQuery()
        val query = object : QueryConnection by base {
            override fun execute(command: QueryCommand): QueryResponse = when (command.name) {
                "clientinfo" -> QueryResponse(listOf(mapOf("client_unique_identifier" to "uid", "client_nickname" to "Anna", "client_servergroups" to "9")), QueryError(0, "ok"))
                "sendtextmessage" -> QueryResponse(emptyList(), QueryError(0, "ok")).also { replies += command.parameters.getValue("msg") }
                else -> base.execute(command)
            }
        }
        val events = EventBus()
        val server = TeamSpeakServer(ConnectionConfig(password = "test"), events) { query }
        val permissions = PermissionService(directory.resolve("permissions.json"))
        val commands = CommandService(server, permissions)
        val services = Services().apply {
            register(EventBus::class.java, events); register(CommandService::class.java, commands)
        }
        server.use {
            server.connect()
            PluginManager(services, directory.resolve("data")).use { manager ->
                manager.load(directory.resolve("jars")); manager.enable()
                val event = TextMessage(2, "uid", "Anna", 1, "!hi Kotlin")
                commands.handle(event)
                assertEquals(listOf("Kein Zugriff."), replies)
                permissions.grantUser("uid", "example.greet", 1)
                permissions.grantUser("uid", "test.allowed", 1)
                replies.clear(); commands.handle(event)
                assertEquals(listOf("Hallo:Anna:greet:hi:Kotlin:true"), replies)
                replies.clear(); commands.handle(event.copy(uniqueId = "old-recycled-id"))
                assertTrue(replies.isEmpty())
            }
        }
    }

    @Test fun `plugin descriptor reads familiar metadata and canonicalizes dependency names`() {
        val descriptor = PluginDescriptor.readYaml("""
            name: MyPlugin
            version: '1.2-SNAPSHOT'
            main: example.Main
            api-version: '2.0'
            author: Anna
            authors: [Ben]
            description: Example
            website: https://example.com
            depend: [Base]
            softdepend: [Optional]
            loadbefore: [Later]
        """.trimIndent().byteInputStream())
        assertEquals("MyPlugin", descriptor.name)
        assertEquals("myplugin", descriptor.id)
        assertEquals(listOf("Ben", "Anna"), descriptor.authors)
        assertEquals(setOf("base"), descriptor.dependencies)
        assertEquals(setOf("optional"), descriptor.softDependencies)
        assertEquals(setOf("later"), descriptor.loadBefore)
    }
}
