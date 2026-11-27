package labs.cerberus.ts

import labs.cerberus.ts.query.*
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SshQueryTest {
    @TempDir lateinit var directory: Path

    private fun sshFixture(action: (SshServer, Path, CompletableFuture<String>) -> Unit) {
        val server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        val keys = SimpleGeneratorHostKeyProvider(directory.resolve("host.key"))
        keys.algorithm = "RSA"
        keys.keySize = 2048
        server.keyPairProvider = keys
        server.setPasswordAuthenticator { user, password, _ -> user == "query" && password == "test-secret" }
        val firstCommand = CompletableFuture<String>()
        server.setShellFactory { _ -> object : Command {
            private lateinit var input: InputStream
            private lateinit var output: OutputStream
            private lateinit var exit: ExitCallback
            private var worker: Thread? = null
            override fun setInputStream(value: InputStream) { input = value }
            override fun setOutputStream(value: OutputStream) { output = value }
            override fun setErrorStream(value: OutputStream) {}
            override fun setExitCallback(value: ExitCallback) { exit = value }
            override fun start(channel: ChannelSession, environment: Environment) {
                worker = Thread.ofPlatform().daemon().start {
                    try {
                        output.write("TS3\n\rWelcome to the TeamSpeak 3 ServerQuery interface\n\r".toByteArray()); output.flush()
                        val reader = input.bufferedReader()
                        val line = reader.readLine()
                        firstCommand.complete(line)
                        output.write("version=6.0.0-beta14 platform=Linux\n\rerror id=0 msg=ok\n\r".toByteArray()); output.flush()
                        while (reader.readLine() != null) { }
                    } catch (_: Exception) { } finally { exit.onExit(0) }
                }
            }
            override fun destroy(channel: ChannelSession) { worker?.interrupt() }
        } }
        server.start()
        try {
            val key = keys.loadKeys(null).first().public
            val knownHosts = directory.resolve("known_hosts")
            Files.writeString(knownHosts, "[127.0.0.1]:${server.port} ${PublicKeyEntry.toString(key)}\n")
            action(server, knownHosts, firstCommand)
        } finally { server.stop(true) }
    }

    @Test fun `SSH authenticates verifies host key opens shell without PTY and sends no query login`() = sshFixture { server, knownHosts, first ->
        val config = ConnectionConfig(host = "127.0.0.1", port = server.port, username = "query", password = "test-secret",
            knownHosts = knownHosts, minimumCommandInterval = Duration.ZERO)
        QueryConnections.open(config).use { connection ->
            assertEquals("6.0.0-beta14", connection.execute(command("version")).single()["version"])
            assertEquals("version", first.get(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `SSH refuses an untrusted host key`() = sshFixture { server, _, _ ->
        val unknown = directory.resolve("empty_known_hosts")
        Files.writeString(unknown, "")
        assertFailsWith<QueryDisconnectedException> {
            QueryConnections.open(ConnectionConfig(host = "127.0.0.1", port = server.port, username = "query", password = "test-secret", knownHosts = unknown))
        }
    }
}
