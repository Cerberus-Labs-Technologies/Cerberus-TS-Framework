package labs.cerberus.ts.query

import java.nio.file.Path
import java.time.Duration

enum class QueryTransport(val defaultPort: Int) { SSH(10022), RAW(10011), HTTP(10080), HTTPS(10443) }
enum class ServerGeneration { TS3, TS6 }

/** Deliberately not a data class: generated toString()/copy() must not expose credentials. */
class ConnectionConfig(
    val host: String = "localhost",
    val transport: QueryTransport = QueryTransport.SSH,
    val generation: ServerGeneration = ServerGeneration.TS6,
    val port: Int = transport.defaultPort,
    val username: String = "serveradmin",
    val password: String? = null,
    val apiKey: String? = null,
    val privateKey: Path? = null,
    val knownHosts: Path = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"),
    val serverId: Int = 1,
    val voicePort: Int? = null,
    val nickname: String = "Cerberus TS",
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val commandTimeout: Duration = Duration.ofSeconds(10),
    val minimumCommandInterval: Duration = Duration.ofMillis(350),
    val guest: Boolean = false
) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/?#@" }) { "Invalid host" }
        require(port in 1..65535 && serverId > 0 && (voicePort == null || voicePort in 1..65535))
        require(!connectTimeout.isNegative && !connectTimeout.isZero && connectTimeout.toMillis() <= Int.MAX_VALUE)
        require(!commandTimeout.isNegative && !commandTimeout.isZero)
        require(!minimumCommandInterval.isNegative)
        require(nickname.isNotBlank())
        require(generation != ServerGeneration.TS6 || transport != QueryTransport.RAW) {
            "TeamSpeak 6 has no raw ServerQuery interface; use SSH or WebQuery"
        }
        if (!guest) when (transport) {
            QueryTransport.SSH -> require(password != null || privateKey != null) { "SSH credentials required" }
            QueryTransport.RAW -> require(password != null) { "Raw query password required" }
            else -> require(!apiKey.isNullOrBlank()) { "WebQuery API key required" }
        }
        require(!guest || generation == ServerGeneration.TS6) { "Explicit guest mode requires TS6" }
        require(!guest || username == "guest") { "Guest mode requires username=guest" }
    }

    override fun toString() = "ConnectionConfig($transport, $host:$port, serverId=$serverId)"
}
