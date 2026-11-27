package labs.cerberus.ts

import labs.cerberus.ts.event.BotState
import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class PluginMailTest {
    @TempDir lateinit var directory: Path
    private fun pluginJar(): Path {
        val source = directory.resolve("MailFixture.java")
        Files.writeString(source, """
            public class MailFixture extends labs.cerberus.ts.plugin.JavaPlugin {
                @labs.cerberus.ts.service.TransportUnit(name="default")
                public labs.cerberus.ts.service.MailTransport mail;
                public void onLoad() {
                    if (mail == null) throw new IllegalStateException("Missing SMTP injection before onLoad");
                }
                public void onEnable() {
                    mail.send(new labs.cerberus.ts.service.MailMessage(java.util.List.of("recipient@example.test"), "Startup mail", "Injected on load, sent on enable."));
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        val classpath = listOf(JavaPlugin::class.java, Function1::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()); val errors = java.io.StringWriter()
        compiler.getStandardFileManager(null, null, null).use { files ->
            assertTrue(compiler.getTask(errors, files, null, listOf("-proc:none", "-classpath", classpath, "-d", classes.toString()), null,
                files.getJavaFileObjectsFromPaths(listOf(source))).call(), errors.toString())
        }
        val plugins = Files.createDirectories(directory.resolve("plugins"))
        JarOutputStream(Files.newOutputStream(plugins.resolve("mail-fixture.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml")); jar.write("name: MailFixture\nversion: 1.0.0\nmain: MailFixture\napi-version: '2.0'\n".toByteArray()); jar.closeEntry()
            jar.putNextEntry(JarEntry("MailFixture.class")); Files.copy(classes.resolve("MailFixture.class"), jar); jar.closeEntry()
        }
        return plugins
    }
    private fun unit(data: Path, name: String, port: Int) {
        val mail = Files.createDirectories(data.resolve("mail"))
        Files.writeString(mail.resolve("$name.yml"), "host: 127.0.0.1\nport: $port\nfrom: sender@example.test\nstarttls: false")
    }
    private fun bot(data: Path, plugins: Path): CerberusBot = CerberusBot(BotConfig(ConnectionConfig(password = "fixture", minimumCommandInterval = Duration.ZERO),
        dataDirectory = data, pluginDirectory = plugins, reconnectAttempts = 0, webEnabled = false)) {
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
    }
    @Test fun `bot loads SMTP units before injection into an actual Java Paper plugin jar`() {
        SmtpTestServer().use { smtp ->
            val data = directory.resolve("data"); unit(data, "default", smtp.port)
            val bot = bot(data, pluginJar()); lateinit var retained: MailTransport
            bot.use {
                bot.start()
                val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                while (bot.state != BotState.CONNECTED && bot.state != BotState.STOPPED && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(BotState.CONNECTED, bot.state); assertFalse(bot.failed)
                retained = bot.mailTransports.require()
                assertEquals("Startup mail", smtp.message.get(5, TimeUnit.SECONDS).subject)
            }
            assertTrue(bot.mailTransports.unitIds().isEmpty())
            assertFails { retained.dispatch(MailMessage(listOf("recipient@example.test"), "Closed", "Body")).get(2, TimeUnit.SECONDS) }
        }
    }
    @Test fun `failed plugin startup cleans configured mail units and invalid SMTP fails before connecting`() {
        val data = directory.resolve("data"); unit(data, "default", 1)
        val plugins = Files.createDirectories(directory.resolve("broken"))
        Files.writeString(plugins.resolve("broken.jar"), "not a jar")
        val failed = bot(data, plugins)
        assertFails { failed.start() }; assertTrue(failed.failed); assertEquals(BotState.STOPPED, failed.state)
        assertTrue(failed.mailTransports.unitIds().isEmpty())
        assertFails { failed.mailTransports.register("later", MailTransport {}) }
        val invalidData = directory.resolve("invalid-data"); unit(invalidData, "default", 99999)
        var connected = false
        val invalid = CerberusBot(BotConfig(ConnectionConfig(password = "fixture"), invalidData, directory.resolve("none"), webEnabled = false)) {
            connected = true; error("Must not connect")
        }
        assertFails { invalid.start() }; assertFalse(connected); assertTrue(invalid.failed)
        assertEquals(BotState.STOPPED, invalid.state)
    }
    @Test fun `profile notifications use automatically loaded mail unit and deliver through real SMTP`() {
        SmtpTestServer().use { smtp ->
            val data = directory.resolve("notify-data"); unit(data, "mail-notifications", smtp.port)
            bot(data, directory.resolve("no-plugins")).use { bot ->
                bot.start()
                bot.profiles.set(bot.profiles.getOrCreate("offline-uid").id, "notifications:email", "recipient@example.test")
                assertTrue(bot.notifications.send("mail", Notification.builder().addRecipient("offline-uid")
                    .summary("Profilnachricht").longText("Gesamter lokaler Versandweg.").build()))
                val received = smtp.message.get(5, TimeUnit.SECONDS)
                assertEquals("[Cerberus TS] Profilnachricht", received.subject)
                assertEquals("recipient@example.test", received.allRecipients.single().toString())
                assertTrue((received.content as String).contains("Gesamter lokaler Versandweg."))
            }
        }
    }
}
