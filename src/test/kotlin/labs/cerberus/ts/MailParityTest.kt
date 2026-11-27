package labs.cerberus.ts

import jakarta.mail.*
import jakarta.mail.internet.MimeMessage
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MailParityTest {
    @TempDir lateinit var directory: Path
    @Test fun `real smtp sends to cc bcc unicode multipart alternatives custom headers and exact attachment bytes`() {
        SmtpFixture().use { smtp ->
            MailService("127.0.0.1", smtp.port, "sender@example.test", tls = SmtpTls.NONE).use { service ->
                val bytes = byteArrayOf(0, 1, 2, 13, 10, -1, 42); val original = bytes.copyOf()
                val file = directory.resolve("notes.txt"); Files.writeString(file, "Anhänge ✓")
                val attachment = MailAttachment.fromBytes("grüße.bin", bytes)
                val builder = MailMessage.builder().to("alice@example.test").to("alice@example.test")
                    .toCC("bob@example.test").toCC("bob@example.test").toBCC("secret@example.test").toBCC("secret@example.test")
                    .addSubjectText("Grüße 🎵").addText("Hallo Welt! ✓").html("<p>Hallo <b>Welt!</b> ✓</p>")
                    .header("X-Cerberus-Job", "42").header("X-Removed", "temporary").header("x-removed", null)
                    .attach(attachment).attach(attachment).attach(MailAttachment.fromFile("notes.txt", file.toFile(), "text/plain"))
                val message = builder.build(); bytes.fill(9); builder.to("later@example.test").addText("Later")
                service.dispatch(message).get(5, TimeUnit.SECONDS)
                val captured = smtp.message.get(5, TimeUnit.SECONDS)
                assertEquals(setOf("alice@example.test", "bob@example.test", "secret@example.test"), smtp.recipients.toSet())
                assertEquals(3, smtp.recipients.size)
                assertNull(captured.getHeader("X-Removed"))
                assertNull(captured.getHeader("Bcc")); assertEquals("Grüße 🎵", captured.subject)
                assertEquals("42", captured.getHeader("X-Cerberus-Job").single())
                val mixed = assertIs<Multipart>(captured.content); assertEquals(3, mixed.count)
                val alternative = assertIs<Multipart>(mixed.getBodyPart(0).content); assertEquals(2, alternative.count)
                assertEquals("Hallo Welt! ✓", (alternative.getBodyPart(0).content as String).trimEnd('\r', '\n'))
                assertTrue(alternative.getBodyPart(1).isMimeType("text/html"))
                assertEquals("grüße.bin", mixed.getBodyPart(1).fileName)
                assertContentEquals(original, mixed.getBodyPart(1).inputStream.use { it.readAllBytes() })
                assertEquals("Anhänge ✓", mixed.getBodyPart(2).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) })
                assertEquals(listOf("alice@example.test"), message.to)
            }
        }
    }
    @Test fun `default smtp refuses plaintext fallback and asynchronous failure remains visible`() {
        SmtpFixture().use { smtp ->
            MailService("127.0.0.1", smtp.port, "sender@example.test").use { service ->
                val result = service.dispatch(MailMessage(listOf("alice@example.test"), "Test", "Body"))
                assertFails { result.get(5, TimeUnit.SECONDS) }
                assertTrue(smtp.recipients.isEmpty())
            }
        }
    }
    @Test fun `plain and html mail without attachments and header validation`() {
        MailService("localhost", from = "sender@example.test").use { service ->
            val plain = service.mime(MailMessage(listOf("alice@example.test"), "Simple", "Plain ✓"))
            assertTrue(plain.isMimeType("text/plain")); assertEquals("Plain ✓", plain.content)
            val html = service.mime(MailMessage(emptyList(), "HTML", "Plain", bcc = listOf("secret@example.test"), html = "<b>Rich</b>"))
            assertTrue(html.isMimeType("multipart/alternative")); assertEquals(2, (html.content as Multipart).count)
            assertFails { MailMessage(listOf("alice@example.test\r\nBcc: evil@example.test"), "Hi", "Body") }
            assertFails { MailMessage(listOf("alice@example.test"), "Subject\nInjected", "Body") }
            assertFails { MailMessage(listOf("alice@example.test"), "Subject", "Body", headers = mapOf("Bcc" to "evil@example.test")) }
            assertFails { MailMessage(listOf("alice@example.test"), "Subject", "Body", headers = mapOf("X-Test" to "a\r\nb")) }
            assertFails { MailAttachment.fromBytes("evil\r\nname", byteArrayOf()) }
            assertFails { MailAttachment.fromPath("directory", directory) }
            assertFails { MailAttachment.fromFile("missing", directory.resolve("missing").toFile()) }
        }
    }
    @Test fun `validated message sender headers override transport default and MIME encodes display names`() {
        SmtpTestServer().use { smtp ->
            MailService("127.0.0.1", smtp.port, "default@example.test", tls = SmtpTls.NONE).use { service ->
                val message = MailMessage.builder().to("recipient@example.test")
                    .header("From", "Grüße <author@example.test>")
                    .header("Sender", "old@example.test").header("sender", "courier@example.test")
                    .addSubjectText("Custom sender").addText("Body").build()
                service.dispatch(message).get(5, TimeUnit.SECONDS)
                val received = smtp.message.get(5, TimeUnit.SECONDS)
                val author = received.from.single() as jakarta.mail.internet.InternetAddress
                assertEquals("author@example.test", author.address); assertEquals("Grüße", author.personal)
                assertEquals("courier@example.test", received.sender.toString())
                assertEquals("author@example.test", smtp.envelope.get(5, TimeUnit.SECONDS))
                assertFails { MailMessage(listOf("recipient@example.test"), "Bad", "Body", headers = mapOf("From" to "not-an-address")) }
                assertFails { MailMessage(listOf("recipient@example.test"), "Bad", "Body", headers = mapOf("Sender" to "x@example.test\r\nBcc: injected@example.test")) }
            }
        }
    }
    @Test fun `named transports dispatch asynchronously unregister and reject duplicate units`() {
        val registry = MailTransports(); val delivered = CompletableFuture<MailMessage>()
        val transport = MailTransport { delivered.complete(it) }
        val handle = registry.register("plugin-mail", transport)
        assertFails { registry.register("plugin-mail", transport) }
        val message = MailMessage(listOf("alice@example.test"), "Info", "Body")
        registry.require("plugin-mail").dispatch(message).get(2, TimeUnit.SECONDS)
        assertSame(message, delivered.get(2, TimeUnit.SECONDS)); handle.close()
        assertNull(registry.find("plugin-mail")); assertFails { registry.require("plugin-mail") }
        val smtp = MailService("localhost", from = "sender@example.test"); smtp.close()
        assertFails { smtp.dispatch(message).get(2, TimeUnit.SECONDS) }
    }

    /** Actual SMTP dialogue; deliberately does not advertise STARTTLS for the downgrade refusal check. */
    private class SmtpFixture : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        val message = CompletableFuture<MimeMessage>()
        val recipients = CopyOnWriteArrayList<String>()
        private val thread = Thread.ofPlatform().daemon().name("test-smtp").start {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val output = socket.getOutputStream()
                    fun reply(value: String) { output.write((value + "\r\n").toByteArray(Charsets.US_ASCII)); output.flush() }
                    reply("220 localhost test SMTP")
                    while (true) {
                        val line = input.readLine() ?: break
                        when {
                            line.startsWith("EHLO ") -> { reply("250-localhost"); reply("250 8BITMIME") }
                            line.startsWith("HELO ") || line.startsWith("MAIL FROM:") -> reply("250 OK")
                            line.startsWith("RCPT TO:") -> { recipients += line.substringAfter('<').substringBefore('>'); reply("250 OK") }
                            line == "DATA" -> {
                                reply("354 End with dot")
                                val content = StringBuilder()
                                while (true) {
                                    val part = input.readLine() ?: error("Truncated SMTP DATA")
                                    if (part == ".") break
                                    content.append(if (part.startsWith("..")) part.drop(1) else part).append("\r\n")
                                }
                                message.complete(MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(content.toString().toByteArray(Charsets.ISO_8859_1))))
                                reply("250 Message accepted")
                            }
                            line == "QUIT" -> { reply("221 Bye"); break }
                            line == "RSET" -> reply("250 Reset")
                            else -> reply("500 Unsupported")
                        }
                    }
                }
            } catch (e: Exception) { message.completeExceptionally(e) }
        }
        override fun close() { listener.close(); thread.join(6000) }
    }
}
