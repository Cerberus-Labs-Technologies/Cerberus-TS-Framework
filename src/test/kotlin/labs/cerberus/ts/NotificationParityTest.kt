package labs.cerberus.ts

import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.concurrent.*
import kotlin.test.*

class NotificationParityTest {
    @TempDir lateinit var directory: Path
    @Test fun `legacy urgency lifespan boundaries route to messages pokes and profile mail with fresh uid checks`() {
        val wire = Fixture(); wire.server.use { server ->
            server.connect(); val profiles = ProfileService(directory.resolve("profiles.json")); val mail = MailTransports()
            val offline = profiles.getOrCreate("offline"); profiles.set(offline.id, "notifications:email", "offline@example.test")
            val delivered = mutableListOf<MailMessage>()
            mail.register("mail-notifications", MailTransport { delivered += it })
            NotificationService(server, profiles, mail).use { notifications ->
                val value = Notification.builder().addRecipient("alice").addRecipient("offline").addRecipient("query")
                    .shortText("Hallo").longText("Langer Inhalt").summary("Warnung").urgency(Urgency.WARN).lifespan(Lifespan.SHORTER).build()
                val result = notifications.dispatch(value).get(3, TimeUnit.SECONDS)
                assertEquals(setOf("teamspeak.message", "teamspeak.poke", "mail"), result.successfulChannels)
                assertTrue(result.failures.isEmpty())
                assertEquals(listOf("sendtextmessage", "clientpoke"), wire.sent.map { it.name })
                assertEquals("2", wire.sent[0].parameters["target"]); assertEquals("2", wire.sent[1].parameters["clid"])
                assertEquals("Langer Inhalt", delivered.single().text); assertEquals(listOf("offline@example.test"), delivered.single().to)
                wire.sent.clear()
                notifications.dispatch(value.copy(lifespan = Lifespan.LONG.level)).get(3, TimeUnit.SECONDS)
                assertTrue(wire.sent.isEmpty()); assertEquals(2, delivered.size)
            }
        }
    }
    @Test fun `reused client ids cannot receive identity-addressed notifications and unicode is byte bounded`() {
        val wire = Fixture(); wire.server.use { server ->
            server.connect()
            NotificationService(server).use { notifications ->
                wire.reused = true
                notifications.dispatch(Notification.builder().addRecipient("alice").shortText("Secret").build()).get(3, TimeUnit.SECONDS)
                assertTrue(wire.sent.isEmpty())
                wire.reused = false
                val value = Notification.builder().addRecipient("alice").shortText("🎵".repeat(400)).urgency(Urgency.WARN).lifespan(Lifespan.SHORTER).build()
                notifications.dispatch(value).get(3, TimeUnit.SECONDS)
                assertEquals(listOf(1024, 100), wire.sent.map { it.parameters.getValue(if (it.name == "clientpoke") "msg" else "msg").toByteArray().size })
                wire.sent.forEach { assertTrue(it.parameters.getValue("msg").codePoints().allMatch { cp -> cp == 0x1f3b5 }) }
            }
        }
    }
    @Test fun `unavailable mail expiration custom filters errors and registration removal are observable`() {
        val wire = Fixture(); wire.server.use { server ->
            server.connect(); val clock = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC)
            NotificationService(server, ProfileService(directory.resolve("profiles.json")), MailTransports(), clock).use { notifications ->
                var good = 0
                val handle = notifications.register("custom", NotificationFilter(0..200, 0..200), NotificationChannel { good++ })
                notifications.register("broken", NotificationFilter(0..200, 0..200), NotificationChannel { error("Custom failure") })
                val value = Notification.builder().addRecipient("offline").shortText("Info").urgency(Urgency.DISMISSABLE).lifespan(Lifespan.SHORT).build()
                val result = notifications.dispatch(value).get(3, TimeUnit.SECONDS)
                assertEquals(setOf("custom"), result.successfulChannels); assertEquals(setOf("broken"), result.failures.keys)
                assertEquals(1, good); handle.close()
                assertTrue(notifications.dispatch(value).get(3, TimeUnit.SECONDS).successfulChannels.isEmpty())
                assertTrue(notifications.dispatch(value.copy(expiresAt = clock.instant())).get(3, TimeUnit.SECONDS).expired)
                assertEquals(1, good)
                val unavailable = notifications.dispatch(value.copy(urgency = Urgency.BASIC.level, lifespan = Lifespan.BASIC.level)).get(3, TimeUnit.SECONDS)
                assertEquals(setOf("mail"), unavailable.unavailableChannels)
            }
        }
    }
    @Test fun `builder reset snapshots direct sending and shutdown complete pending work`() {
        val wire = Fixture(); wire.server.use { server ->
            server.connect(); val notifications = NotificationService(server)
            assertTrue(notifications.send("teamspeak.message", Notification("2", "Direct")))
            assertEquals("Direct", wire.sent.single().parameters["msg"])
            val builder = Notification.builder().addRecipient("alice").shortText("Before")
            val built = builder.build(); builder.reset().addRecipient("bob").shortText("After")
            assertEquals(listOf("alice"), built.recipients); assertEquals("Before", built.longText)
            val entered = CountDownLatch(1)
            notifications.register("blocking", NotificationFilter(0..100, 0..100), NotificationChannel { entered.countDown(); CountDownLatch(1).await() })
            val pending = notifications.dispatch(built.copy(urgency = 0, lifespan = 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS)); notifications.close()
            assertFails { pending.get(2, TimeUnit.SECONDS) }
            assertFails { notifications.dispatch(built).get(2, TimeUnit.SECONDS) }
        }
    }
    private class Fixture {
        val sent = CopyOnWriteArrayList<QueryCommand>()
        @Volatile var reused = false
        val server = TeamSpeakServer(ConnectionConfig(password = "fixture"), EventBus()) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = false
                override fun execute(command: QueryCommand) = QueryResponse(when(command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "1", "virtualserver_id" to "1"))
                    "clientlist" -> listOf(mapOf("clid" to "2", "cid" to "1", "client_nickname" to "Alice", "client_unique_identifier" to "alice", "client_type" to "0"),
                        mapOf("clid" to "3", "client_unique_identifier" to "query", "client_type" to "1"))
                    "clientinfo" -> listOf(mapOf("client_unique_identifier" to if (reused) "bob" else "alice"))
                    "sendtextmessage", "clientpoke" -> { sent += command; emptyList() }
                    else -> error("Unexpected command: ${command.name}")
                }, QueryError(0, "ok"))
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
    }
}
