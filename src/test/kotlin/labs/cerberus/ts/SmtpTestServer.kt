package labs.cerberus.ts

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Real loopback SMTP, including STARTTLS, implicit TLS and AUTH PLAIN. Never contacts a mail provider. */
internal class SmtpTestServer(private val tls: Tls = Tls.PLAIN, private val context: SSLContext? = null,
    private val authenticate: Boolean = false, private val stallAfterMail: Boolean = false) : AutoCloseable {
    enum class Tls { PLAIN, STARTTLS, IMPLICIT }
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val active = AtomicReference<Socket>()
    val port get() = listener.localPort
    val message = CompletableFuture<MimeMessage>()
    val credentials = CompletableFuture<Pair<String, String>>()
    val envelope = CompletableFuture<String>()
    val handshakes = CompletableFuture<String>()
    val connectionEnded = CompletableFuture<Unit>()
    @Volatile var authBeforeTls = false; private set
    private val thread = Thread.ofPlatform().daemon().name("test-configured-smtp").start {
        try {
            listener.accept().use { raw ->
                raw.soTimeout = 6000; active.set(raw)
                var socket = raw
                var input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var output = socket.getOutputStream()
                var encrypted = false
                fun upgrade() {
                    val secure = checkNotNull(context).socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket
                    secure.useClientMode = false; secure.soTimeout = 6000; secure.startHandshake()
                    socket = secure; input = secure.inputStream.bufferedReader(Charsets.ISO_8859_1); output = secure.outputStream
                    encrypted = true; handshakes.complete(secure.session.protocol)
                }
                fun reply(value: String) { output.write((value + "\r\n").toByteArray(Charsets.US_ASCII)); output.flush() }
                if (tls == Tls.IMPLICIT) upgrade()
                reply("220 localhost integration SMTP")
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        line.startsWith("EHLO ") -> {
                            reply("250-localhost")
                            if (tls == Tls.STARTTLS && !encrypted) reply("250-STARTTLS")
                            if (authenticate) reply("250-AUTH PLAIN")
                            reply("250 8BITMIME")
                        }
                        line == "STARTTLS" && tls == Tls.STARTTLS && !encrypted -> { reply("220 Begin TLS"); upgrade() }
                        line.startsWith("AUTH PLAIN") -> {
                            authBeforeTls = !encrypted
                            val encoded = line.removePrefix("AUTH PLAIN").trim().ifEmpty { reply("334 "); input.readLine() }
                            val values = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8).split('\u0000')
                            check(values.size == 3)
                            credentials.complete(values[1] to values[2]); reply("235 Authentication successful")
                        }
                        line.startsWith("MAIL FROM:") -> {
                            envelope.complete(line.substringAfter('<').substringBefore('>'))
                            if (stallAfterMail) { while (input.readLine() != null) {} ; break }
                            reply("250 OK")
                        }
                        line.startsWith("RCPT TO:") || line == "RSET" -> reply("250 OK")
                        line == "DATA" -> {
                            reply("354 End with dot")
                            val content = StringBuilder()
                            while (true) {
                                val part = input.readLine() ?: error("Truncated SMTP DATA")
                                if (part == ".") break
                                content.append(if (part.startsWith("..")) part.drop(1) else part).append("\r\n")
                            }
                            message.complete(MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(content.toString().toByteArray(Charsets.ISO_8859_1))))
                            reply("250 Accepted")
                        }
                        line == "QUIT" -> { reply("221 Bye"); break }
                        else -> reply("500 Unsupported")
                    }
                }
                if (socket !== raw) socket.close()
            }
        } catch (error: Exception) { message.completeExceptionally(error) }
        finally { connectionEnded.complete(Unit) }
    }
    override fun close() {
        listener.close(); active.get()?.close(); thread.join(2000)
        check(!thread.isAlive) { "SMTP fixture failed to terminate" }
    }
    companion object {
        fun context(directory: Path, hostname: String = "localhost"): SSLContext {
            Files.createDirectories(directory)
            val store = directory.resolve("server.p12")
            val password = "fixture-only"
            val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password, "-keypass", password,
                "-dname", "CN=$hostname", "-ext", if (hostname == "localhost") "SAN=dns:localhost,ip:127.0.0.1" else "SAN=dns:$hostname",
                "-validity", "2", "-noprompt").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Fixture keytool timed out") }
            check(process.exitValue() == 0) { "Cannot create test TLS certificate" }
            val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, password.toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password.toCharArray()) }
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        }
    }
}
