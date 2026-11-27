package labs.cerberus.ts

import labs.cerberus.ts.event.*
import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.command.CommandParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ServicesTest {
    @TempDir lateinit var directory: Path

    @Test fun `profiles persist merge identities and prefer destination properties`() {
        val path = directory.resolve("profiles.json")
        val profiles = ProfileService(path)
        val first = profiles.getOrCreate("ts6:alice")
        val second = profiles.getOrCreate("ts6:bob")
        profiles.set(first.id, "language", "de")
        profiles.set(second.id, "language", "en")
        profiles.set(second.id, "email", "bob@example.com")
        assertFails { profiles.link(first.id, "ts6:bob") }
        profiles.merge(first.id, second.id)
        val reloaded = ProfileService(path)
        assertEquals(first.id, reloaded.find("ts6:bob")?.id)
        assertEquals("de", reloaded.find(first.id)?.properties?.get("language"))
        assertEquals("bob@example.com", reloaded.find(first.id)?.properties?.get("email"))
        assertNull(reloaded.find(second.id))
        assertTrue(reloaded.delete(first.id))
        assertNull(ProfileService(path).find("ts6:alice"))
    }

    @Test fun `corrupt persistence never silently resets`() {
        val file = directory.resolve("profiles.json")
        Files.writeString(file, "{truncated")
        assertFails { ProfileService(file) }
        assertEquals("{truncated", Files.readString(file))
    }

    @Test fun `permissions support specificity inheritance denies links and cycle rejection`() {
        val path = directory.resolve("permissions.json")
        val permissions = PermissionService(path)
        permissions.createGroup("reader")
        permissions.createGroup("admin")
        permissions.createGroup("restricted")
        permissions.grantGroup("reader", "channel.*", 1)
        permissions.grantGroup("admin", "channel.delete", 5)
        permissions.inherit("admin", "reader")
        assertFails { permissions.inherit("reader", "admin") }
        permissions.addMember("alice", "admin")
        assertTrue(permissions.allows("alice", "channel.read"))
        assertEquals(5, permissions.value("alice", "channel.delete"))
        assertFalse(permissions.allows("unknown", "channel.read"))
        permissions.grantGroup("restricted", "channel.delete", 0)
        permissions.addMember("alice", "restricted")
        assertFalse(permissions.allows("alice", "channel.delete"))
        permissions.grantUser("alice", "channel.delete", 10)
        assertTrue(permissions.allows("alice", "channel.delete", minimum = 10))
        permissions.linkServerGroup("reader", 42)
        val reloaded = PermissionService(path)
        assertTrue(reloaded.allows("bob", "channel.read", setOf(42)))
        assertFalse(reloaded.allows("bob", "channel.read", setOf(41)))
        assertEquals(10, reloaded.value("alice", "channel.delete"))
    }

    @Test fun `tokens are hashed scoped revoked and expired`() {
        val path = directory.resolve("tokens.json")
        val clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC)
        val tokens = TokenService(path, clock)
        val token = tokens.generate(setOf("GET /plugins/*"), Duration.ofMinutes(1))
        assertFalse(Files.readString(path).contains(token))
        assertTrue(tokens.verify(token, "GET", "/plugins/test"))
        assertFalse(tokens.verify(token, "DELETE", "/plugins/test"))
        assertFalse(tokens.verify(token, "GET", "/plugins-evil/test"))
        assertFalse(tokens.verify(token, "GET", "/plugins/../admin"))
        assertFalse(tokens.verify(token, "GET", "/plugins/%2e%2e/admin"))
        assertTrue(TokenService(path, clock).verify(token, "GET", "/plugins/test"))
        assertFalse(TokenService(path, Clock.offset(clock, Duration.ofMinutes(1))).verify(token, "GET", "/plugins/test"))
        assertTrue(tokens.revoke(token))
        assertFalse(tokens.verify(token, "GET", "/plugins/test"))
    }

    @Test fun `event order cancellation listener isolation and removal`() {
        class Event : Cancellable { override var cancelled = false }
        val failures = mutableListOf<Throwable>()
        val bus = EventBus { failures += it }
        val order = mutableListOf<String>()
        bus.subscribe(Event::class.java, -10) { order += "first"; it.cancelled = true }
        bus.subscribe(Event::class.java) { order += "skipped" }
        bus.subscribe(Event::class.java, 10, true) { throw IllegalStateException("bad listener") }
        val subscription = bus.subscribe(Event::class.java, 20, true) { order += "last" }
        bus.publish(Event())
        assertEquals(listOf("first", "last"), order)
        assertEquals(1, failures.size)
        subscription.close(); order.clear()
        bus.publish(Event())
        assertEquals(listOf("first"), order)
    }

    @Test fun `plugin dependencies have deterministic order and reject duplicates cycles and missing plugins`() {
        fun descriptor(id: String, vararg dependencies: String) = PluginDescriptor(id, "2.0.0", "example.Plugin", dependencies.toSet())
        val base = descriptor("base")
        val child = descriptor("child", "base")
        assertEquals(listOf(base, child), PluginManager.dependencyOrder(listOf(child, base)))
        assertFails { PluginManager.dependencyOrder(listOf(base, base)) }
        assertFails { PluginManager.dependencyOrder(listOf(child)) }
        assertFails { PluginManager.dependencyOrder(listOf(descriptor("a", "b"), descriptor("b", "a"))) }
    }

    @Test fun `resource scope unwinds all registrations after failure`() {
        val scope = ResourceScope()
        val order = mutableListOf<Int>()
        scope.own(AutoCloseable { order += 1 })
        scope.own(AutoCloseable { order += 2; error("cleanup failure") })
        scope.own(AutoCloseable { order += 3 })
        assertFails { scope.close() }
        assertEquals(listOf(3, 2, 1), order)
        scope.close()
        assertFails { scope.own(AutoCloseable { order += 4 }) }
        assertEquals(4, order.last())
    }

    @Test fun `command parser handles empty quotes escaped quotes negative numbers and option terminator`() {
        val parsed = CommandParser.parse("TeSt \"hello \\\"world\\\"\" '' -12 --enabled --name=Alice -- --literal")
        assertEquals("test", parsed.name)
        assertEquals(listOf("hello \"world\"", "", "-12", "--literal"), parsed.arguments)
        assertEquals(mapOf("enabled" to null, "name" to "Alice"), parsed.options)
        assertFails { CommandParser.parse("test \"unterminated") }
        assertFails { CommandParser.parse("test --a --a") }
    }

    @Test fun `localization resolves language fallback and repeated trailing parameters`() {
        val localization = LocalizationService()
        localization.register("en", mapOf("hello" to "Hello %[name] %[name]"))
        localization.register("de", mapOf("hello" to "Hallo %[name]"))
        assertEquals("Hallo Anna", localization.text("hello", "de-DE", mapOf("name" to "Anna")))
        assertEquals("Hello Anna Anna", localization.text("hello", "fr", mapOf("name" to "Anna")))
        assertFails { localization.text("hello") }
    }

    @Test fun `scheduler survives failed invocation and cancellation stops repeating task`() {
        TaskService().use { tasks ->
            val latch = CountDownLatch(2)
            val handle = tasks.schedule(interval = Duration.ofMillis(10)) { latch.countDown(); error("test") }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            handle.close()
        }
    }

    @Test fun `service injection visits parent fields and rejects missing dependencies`() {
        open class Parent { @Inject lateinit var events: EventBus }
        class Child : Parent()
        val child = Child()
        val services = Services()
        assertFails { services.inject(child) }
        val bus = EventBus()
        val handle = services.register(EventBus::class.java, bus)
        services.inject(child)
        assertSame(bus, child.events)
        handle.close()
        assertFails { services.get<EventBus>() }
    }
}
