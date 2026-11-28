package labs.cerberus.ts

import labs.cerberus.ts.query.*
import labs.cerberus.ts.music.MusicConfig
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.Properties

class BotConfig(val connection: ConnectionConfig, val dataDirectory: Path = Path.of("data"),
    val pluginDirectory: Path = Path.of("plugins"), val commandPrefix: String = "!",
    val reconnectAttempts: Int = 10, val reconnectDelay: Duration = Duration.ofSeconds(10),
    val keepAlive: Duration = Duration.ofSeconds(60), val restPort: Int? = null, val restHost: String = "127.0.0.1",
    val adminUniqueId: String? = null, val music: MusicConfig = MusicConfig(),
    val webEnabled: Boolean = true, val webPort: Int? = null, val webHost: String = "127.0.0.1") {
    init {
        require(reconnectAttempts >= -1 && !reconnectDelay.isNegative && !reconnectDelay.isZero)
        require(!keepAlive.isNegative && !keepAlive.isZero)
        require(restPort == null || restPort in 0..65535)
        require(webPort == null || webPort in 0..65535)
        require(commandPrefix.isNotBlank())
    }
    companion object {
        fun load(path: Path, environment: Map<String, String> = System.getenv()): BotConfig {
            val properties = Properties()
            if (Files.exists(path)) Files.newBufferedReader(path).use(properties::load)
            fun value(key: String, default: String? = null): String? =
                environment["CERBERUS_" + key.uppercase().replace('.', '_')] ?: properties.getProperty(key) ?: default
            fun required(key: String, default: String) = requireNotNull(value(key, default))
            fun int(key: String, default: Int) = required(key, "$default").toInt()
            fun millis(key: String, default: Long) = Duration.ofMillis(required(key, "$default").toLong())
            val transport = QueryTransport.valueOf(required("transport", "SSH").uppercase())
            val guest = required("guest", "false").toBooleanStrict()
            val config = ConnectionConfig(
                host = required("host", "localhost"), transport = transport,
                generation = ServerGeneration.valueOf(required("generation", "TS6").uppercase()),
                port = int("port", transport.defaultPort), username = required("username", if (guest) "guest" else "serveradmin"),
                password = value("password"), apiKey = value("api.key"), privateKey = value("private.key")?.let(Path::of),
                knownHosts = Path.of(required("known.hosts", Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString())),
                serverId = int("server.id", 1), voicePort = value("voice.port")?.toInt(), nickname = required("nickname", "Cerberus TS"),
                connectTimeout = millis("connect.timeout.ms", 10000), commandTimeout = millis("command.timeout.ms", 10000),
                minimumCommandInterval = millis("command.interval.ms", 350), guest = guest)
            return BotConfig(config, Path.of(required("data.directory", "data")), Path.of(required("plugin.directory", "plugins")),
                required("command.prefix", "!"), int("reconnect.attempts", 10), millis("reconnect.delay.ms", 10000),
                millis("keepalive.ms", 60000), value("rest.port")?.toInt(), required("rest.host", "127.0.0.1"), value("admin.uid"),
                MusicConfig(required("music.ffmpeg", "ffmpeg"), required("music.ytdlp", "yt-dlp"),
                    required("music.js.runtime", "node"), int("music.max.bots", 4), int("music.max.queue", 100),
                    value("music.cookies.file")?.takeIf(String::isNotBlank)?.let(Path::of)),
                required("web.enabled", "true").toBooleanStrict(), int("web.port", 24875), required("web.host", "127.0.0.1"))
        }
    }
}
