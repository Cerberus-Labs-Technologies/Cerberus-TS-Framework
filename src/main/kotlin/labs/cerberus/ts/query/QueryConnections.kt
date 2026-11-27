package labs.cerberus.ts.query

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

object QueryConnections {
    fun open(config: ConnectionConfig): QueryConnection = when (config.transport) {
        QueryTransport.SSH -> ssh(config)
        QueryTransport.RAW -> raw(config)
        QueryTransport.HTTP, QueryTransport.HTTPS -> WebQueryConnection(config)
    }

    private fun raw(config: ConnectionConfig): QueryConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(config.host, config.port), config.connectTimeout.toMillis().toInt())
            socket.tcpNoDelay = true
            val connection = StreamQueryConnection(socket.getInputStream(), socket.getOutputStream(),
                config.commandTimeout, config.minimumCommandInterval, socket::close)
            try {
                connection.execute(command("login", "client_login_name" to config.username,
                    "client_login_password" to requireNotNull(config.password)))
                return connection
            } catch (e: Exception) { connection.close(); throw e }
        } catch (e: Exception) { socket.close(); throw e }
    }

    private fun ssh(config: ConnectionConfig): QueryConnection {
        require(Files.isRegularFile(config.knownHosts)) { "SSH known_hosts file missing; verify and add the server host key first" }
        val jsch = JSch()
        jsch.setKnownHosts(config.knownHosts.toString())
        config.privateKey?.let { jsch.addIdentity(it.toString(), config.password?.toByteArray(Charsets.UTF_8)) }
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (config.guest) "none" else "publickey,password,keyboard-interactive")
        config.password?.let { session.setPassword(it.toByteArray(Charsets.UTF_8)) }
        session.serverAliveInterval = 30_000
        session.serverAliveCountMax = 3
        try {
            session.connect(config.connectTimeout.toMillis().toInt())
            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(false)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(config.connectTimeout.toMillis().toInt())
            return StreamQueryConnection(input, output, config.commandTimeout, config.minimumCommandInterval) {
                channel.disconnect()
                session.disconnect()
            }
        } catch (e: Exception) {
            session.disconnect()
            throw QueryDisconnectedException("SSH query connection failed", e)
        }
    }
}
