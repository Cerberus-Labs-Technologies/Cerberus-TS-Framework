package labs.cerberus.ts

import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MailConfigurationTest {
    @TempDir lateinit var directory: Path
    private val message = MailMessage(listOf("recipient@example.test"), "Konfiguriert ✓", "Aus einer geladenen SMTP-Unit.")
    private fun config(name: String, text: String, parent: Path = directory): Path {
        Files.createDirectories(parent)
        return parent.resolve(name).also { Files.writeString(it, text.trimIndent()) }
    }

    @Test fun `legacy json properties override connection credentials tls and envelope and yaml units load together`() {
        SmtpTestServer(authenticate = true).use { smtp ->
            config("default.json", """
                {"host":"invalid.example.test","port":"0","from":"sender@example.test",
                 "username":"old-user","user":"ignored-user","pass":"old-pass","password":"ignored-pass",
                 "properties":{"mail.smtp.host":"127.0.0.1","mail.smtp.port":${smtp.port},
                   "mail.smtp.user":"configured-user","mail.smtp.pass":"configured-pass",
                   "mail.smtp.from":"bounce@example.test","mail.smtp.starttls.enable":false}}
            """)
            config("other.yml", "from: other@example.test\nstarttls: false")
            config("ignored.txt", "not a configuration")
            MailTransports().use { registry ->
                assertEquals(setOf("default", "other"), registry.load(directory))
                registry.require().dispatch(message).get(5, TimeUnit.SECONDS)
                val received = smtp.message.get(5, TimeUnit.SECONDS)
                assertEquals(message.subject, received.subject)
                assertEquals("sender@example.test", received.from.single().toString())
                assertEquals("bounce@example.test", smtp.envelope.get(5, TimeUnit.SECONDS))
                assertEquals("configured-user" to "configured-pass", smtp.credentials.get(5, TimeUnit.SECONDS))
            }
        }
    }
    @Test fun `configured starttls and implicit tls perform real handshakes and authenticate only after encryption`() {
        val context = SmtpTestServer.context(directory.resolve("certificates"))
        for ((mode, serverMode) in listOf("STARTTLS" to SmtpTestServer.Tls.STARTTLS, "IMPLICIT" to SmtpTestServer.Tls.IMPLICIT)) {
            SmtpTestServer(serverMode, context, authenticate = true).use { smtp ->
                val units = directory.resolve(mode)
                config("default.yaml", """
                    host: 127.0.0.1
                    port: ${smtp.port}
                    from: sender@example.test
                    user: configured-user
                    password: configured-pass
                    tls: $mode
                    properties:
                      mail.smtp.ssl.trust: '127.0.0.1'
                """, units)
                MailTransports().use { registry ->
                    registry.load(units); registry.require().dispatch(message).get(8, TimeUnit.SECONDS)
                    assertEquals(message.subject, smtp.message.get(5, TimeUnit.SECONDS).subject)
                    assertTrue(smtp.handshakes.get(5, TimeUnit.SECONDS).startsWith("TLSv1."))
                    assertEquals("configured-user" to "configured-pass", smtp.credentials.get(5, TimeUnit.SECONDS))
                    assertFalse(smtp.authBeforeTls)
                }
            }
        }
    }
    @Test fun `tls rejects an untrusted certificate and a trusted but incorrect server identity`() {
        for ((name, trust) in listOf("localhost" to false, "wrong.example.test" to true)) {
            val context = SmtpTestServer.context(directory.resolve(name), name)
            SmtpTestServer(SmtpTestServer.Tls.STARTTLS, context).use { smtp ->
                val units = directory.resolve("units-$name")
                config("default.yml", """
                    host: 127.0.0.1
                    port: ${smtp.port}
                    from: sender@example.test
                """.trimIndent() + if (trust) "\nproperties:\n  mail.smtp.ssl.trust: '127.0.0.1'" else "", units)
                MailTransports().use { registry ->
                    registry.load(units)
                    assertFails { registry.require().dispatch(message).get(8, TimeUnit.SECONDS) }
                    assertFalse(smtp.envelope.isDone)
                    assertFalse(smtp.credentials.isDone)
                }
            }
        }
    }
    @Test fun `loaded default tls refuses a plaintext server before credentials or mail are sent`() {
        SmtpTestServer(authenticate = true).use { smtp ->
            config("default.yml", "host: 127.0.0.1\nport: ${smtp.port}\nfrom: sender@example.test\nuser: user\npass: pass")
            MailTransports().use { registry ->
                registry.load(directory)
                assertFails { registry.require().dispatch(message).get(5, TimeUnit.SECONDS) }
                assertFalse(smtp.credentials.isDone); assertFalse(smtp.envelope.isDone)
            }
        }
    }
    @Test fun `invalid TLS flags timeouts scalar settings and duplicate JSON fields are rejected without leaking values`() {
        val file = config("unit.yml", "from: sender@example.test")
        for (text in listOf(
            "from: sender@example.test\nproperties: {mail.smtp.starttls.enable: typo}",
            "from: sender@example.test\nproperties: {mail.smtp.ssl.checkserveridentity: typo}",
            "from: sender@example.test\nproperties: {mail.smtp.timeout: invalid}",
            "from: sender@example.test\nport: 99999",
            "from: sender@example.test\nproperties: {nested: [SECRET-MUST-NOT-APPEAR]}",
            "from: sender@example.test\npassword: [SECRET-MUST-NOT-APPEAR]"
        )) {
            Files.writeString(file, text)
            MailTransports().use { registry ->
                val error = assertFails { registry.load(directory) }
                assertFalse(error.stackTraceToString().contains("SECRET-MUST-NOT-APPEAR"))
                assertTrue(registry.unitIds().isEmpty())
            }
        }
        Files.delete(file)
        val json = config("unit.json", "{\"password\":\"SECRET-MUST-NOT-APPEAR\",\"password\":\"duplicate\"}")
        val error = assertFails { SmtpTransportConfig.read(json) }
        assertFalse(error.stackTraceToString().contains("SECRET-MUST-NOT-APPEAR"))
        assertFails { SmtpTransportConfig.read(directory.resolve("absent.yml")) }
    }
    @Test fun `failed batches duplicate ids and syntax errors retain only previous registrations and redact parser input`() {
        MailTransports().use { registry ->
            val external = MailTransport {}; registry.register("existing", external)
            val good = config("a.yml", "from: sender@example.test")
            val bad = config("b.yml", "from: sender@example.test\nuser: configured-user")
            assertFails { registry.load(directory) }; assertEquals(setOf("existing"), registry.unitIds())
            Files.writeString(bad, "id: a\nfrom: sender@example.test")
            assertFails { registry.load(directory) }; assertEquals(setOf("existing"), registry.unitIds())
            Files.writeString(bad, "password: SECRET-MUST-NOT-APPEAR\npassword: duplicate")
            val failure = assertFails { registry.load(directory) }
            assertFalse(failure.stackTraceToString().contains("SECRET-MUST-NOT-APPEAR"))
            assertEquals(setOf("existing"), registry.unitIds())
            Files.delete(bad)
            assertEquals(setOf("a"), registry.load(directory))
            assertFails { registry.load(directory) }; assertSame(external, registry.require("existing"))
            val owned = registry.require("a")
            assertTrue(registry.remove("a")); assertFalse(registry.remove("a"))
            assertFails { owned.dispatch(message).get(2, TimeUnit.SECONDS) }
            assertEquals("SmtpTransportConfig(id=a)", SmtpTransportConfig.read(good).toString())
            assertTrue(registry.load(directory.resolve("absent")).isEmpty())
        }
    }
    @Test fun `closing configured registry terminates a stalled active SMTP session and queued dispatch`() {
        SmtpTestServer(stallAfterMail = true).use { smtp ->
            config("default.yml", """
                host: 127.0.0.1
                port: ${smtp.port}
                from: sender@example.test
                tls: NONE
                properties:
                  mail.smtp.timeout: 250
                  mail.smtp.quitwait: false
            """)
            val registry = MailTransports(); registry.load(directory)
            val transport = registry.require()
            val active = transport.dispatch(message)
            smtp.envelope.get(5, TimeUnit.SECONDS)
            val queued = transport.dispatch(message)
            try {
                registry.close()
                assertFails { active.get(2, TimeUnit.SECONDS) }
                assertFails { queued.get(2, TimeUnit.SECONDS) }
                smtp.connectionEnded.get(2, TimeUnit.SECONDS)
                assertFalse(smtp.message.isDone)
            } finally { registry.close() }
        }
    }
    @Test fun `old handles cannot remove replacements and registry closes only configured services`() {
        class External : MailTransport, AutoCloseable {
            var closed = false
            override fun send(message: MailMessage) {}
            override fun close() { closed = true }
        }
        val registry = MailTransports(); val external = External()
        val first = registry.register("plugin", external); first.close()
        registry.register("plugin", external); first.close()
        assertSame(external, registry.require("plugin"))
        val defaults = config("defaults.json", "{}")
        SmtpTransportConfig.read(defaults).open().use { service ->
            assertEquals("mail@localhost", service.mime(message).from.single().toString())
        }
        Files.delete(defaults)
        config("default.yml", "from: sender@example.test\nstarttls: false")
        registry.load(directory); val configured = registry.require()
        val snapshot = registry.unitIds(); registry.close(); registry.close()
        assertEquals(setOf("plugin", "default"), snapshot); assertTrue(registry.unitIds().isEmpty())
        assertFalse(external.closed)
        assertFails { configured.dispatch(message).get(2, TimeUnit.SECONDS) }
        assertFails { registry.register("late", external) }; assertFails { registry.load(directory) }
    }
}
