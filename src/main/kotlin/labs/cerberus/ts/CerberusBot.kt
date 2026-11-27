package labs.cerberus.ts

import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.*
import labs.cerberus.ts.plugin.PluginManager
import labs.cerberus.ts.plugin.ResourceScope
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.*
import labs.cerberus.ts.voice.VoiceService
import labs.cerberus.ts.voice.Ts3jVoiceProvider
import labs.cerberus.ts.music.MusicService
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CerberusBot @JvmOverloads constructor(val config: BotConfig,
    voiceProvider: labs.cerberus.ts.voice.VoiceProvider = Ts3jVoiceProvider(),
    connector: (ConnectionConfig) -> QueryConnection = QueryConnections::open) : AutoCloseable {
    constructor(config: BotConfig, connector: (ConnectionConfig) -> QueryConnection) : this(config, Ts3jVoiceProvider(), connector)

    val services = Services()
    val database = DatabaseService()
    val events = EventBus(database)
    val server = TeamSpeakServer(config.connection, events, connector)
    val permissions = PermissionService(config.dataDirectory.resolve("permissions.json"))
    val profiles = ProfileService(config.dataDirectory.resolve("profiles.json"), events)
    val localization = LocalizationService(directory = config.dataDirectory.resolve("localization"))
    val tokens = TokenService(config.dataDirectory.resolve("tokens.json"))
    val commands = CommandService(server, permissions, config.commandPrefix, localization)
    val music = MusicService(config.dataDirectory.resolve("music"), config.music, voiceProvider)
    val mailTransports = MailTransports()
    val notifications = NotificationService(server, profiles, mailTransports)
    private val tasks = TaskService()
    private val rest = RestService(tokens, database)
    val web = if (config.webEnabled) labs.cerberus.ts.web.WebInterfaceService(rest) else null
    private val resources = ResourceScope()
    private val plugins = PluginManager(services, config.dataDirectory.resolve("plugins"))
    private val running = AtomicBoolean()
    private val started = AtomicBoolean()
    private val stopped = CountDownLatch(1)
    private var supervisor: Thread? = null
    @Volatile var state: BotState = BotState.NEW; private set
    @Volatile var failed: Boolean = false; private set

    init {
        services.register(CerberusBot::class.java, this)
        services.register(Services::class.java, services)
        services.register(labs.cerberus.ts.configuration.ConfigurationService::class.java,
            labs.cerberus.ts.configuration.ConfigurationService(config.dataDirectory.resolve("configuration")))
        services.register(EventBus::class.java, events)
        services.register(TeamSpeakServer::class.java, server)
        services.register(CommandService::class.java, commands)
        services.register(labs.cerberus.ts.command.MatcherRegistry::class.java, commands.matchers)
        services.register(UserService::class.java, commands.users)
        services.register(PermissionService::class.java, permissions)
        services.register(ProfileService::class.java, profiles)
        services.register(TokenService::class.java, tokens)
        services.register(TaskService::class.java, tasks)
        services.register(RestService::class.java, rest)
        web?.let { services.register(labs.cerberus.ts.web.WebInterfaceService::class.java, it) }
        services.register(DatabaseService::class.java, database)
        services.register(LocalizationService::class.java, localization)
        services.register(MailTransports::class.java, mailTransports)
        services.register(NotificationService::class.java, notifications)
        services.register(VoiceService::class.java, VoiceService().apply { register(Ts3jVoiceProvider()) })
        services.register(MusicService::class.java, music)
    }

    @Synchronized fun start() {
        check(started.compareAndSet(false, true)) { "Create a new bot instance to restart" }
        running.set(true)
        transition(BotState.LOADING)
        try {
            config.adminUniqueId?.let { permissions.grantUser(it, "*", 1) }
            resources.own(commands.installBuiltins())
            resources.own(localization.installCommands(commands, server))
            resources.own(events.subscribe<TextMessage> { commands.handle(it) })
            resources.own(events.subscribe<ConnectionLost> { if (running.get()) transition(BotState.DISCONNECTED) })
            resources.own(rest.route("GET", "/health") {
                RestResponse(if (server.isConnected) 200 else 503, mapOf("state" to state.name, "connected" to server.isConnected))
            })
            if (web != null) {
                resources.own(labs.cerberus.ts.web.WebControlApi(this, rest) { plugins.descriptions() })
                resources.own(commands.register("web-token", "Create a web access token (local console only)", "cerberus.web.admin") { command ->
                    require(command.clientId == null) { "Web-Zugriffstokens werden nur in der lokalen Konsole erstellt." }
                    val scopes = setOf("GET", "POST", "PUT", "PATCH", "DELETE").flatMap { verb -> listOf("$verb /api/web/*", "$verb /api/plugins/*") }.toSet()
                    command.reply(tokens.generate(scopes, java.time.Duration.ofHours(24)))
                })
            }
            if (config.restPort != null) rest.start(InetSocketAddress(config.restHost, config.restPort))
            else if (config.webEnabled && config.webPort != null) rest.start(InetSocketAddress(config.webHost, config.webPort))
            mailTransports.load(config.dataDirectory.resolve("mail"))
            plugins.load(config.pluginDirectory)
            val databaseDirectory = config.dataDirectory.resolve("database")
            if (java.nio.file.Files.isDirectory(databaseDirectory)) {
                database.load(databaseDirectory, plugins.persistenceClasses(), plugins.persistenceClassLoader())
            }
            supervisor = Thread.ofPlatform().daemon().name("cerberus-supervisor").start(::supervise)
        } catch (e: Throwable) { failed = true; close(); throw e }
    }

    private fun supervise() {
        var failures = 0
        var pluginsEnabled = false
        var lastKeepAlive = System.nanoTime()
        try {
            while (running.get()) {
                if (!server.isConnected) {
                    transition(BotState.CONNECTING)
                    try {
                        server.connect()
                        if (!running.get()) { server.close(); return }
                        if (!pluginsEnabled) {
                            try { synchronized(this) { if (running.get()) { plugins.enable(); pluginsEnabled = true } } }
                            catch (e: Throwable) { failed = true; close(); return }
                        }
                        transition(BotState.CONNECTED)
                        failures = 0
                        lastKeepAlive = System.nanoTime()
                    } catch (e: Exception) {
                        server.close()
                        failures++
                        transition(BotState.DISCONNECTED)
                        System.getLogger(javaClass.name).log(System.Logger.Level.WARNING,
                            "Query connection failed (attempt $failures): ${e.javaClass.simpleName}")
                        if (config.reconnectAttempts >= 0 && failures > config.reconnectAttempts) {
                            failed = true; close(); return
                        }
                        TimeUnit.NANOSECONDS.sleep(config.reconnectDelay.toNanos())
                    }
                } else if (System.nanoTime() - lastKeepAlive >= config.keepAlive.toNanos()) {
                    try { server.execute(command("version")) } catch (_: Exception) { server.close(); transition(BotState.DISCONNECTED) }
                    lastKeepAlive = System.nanoTime()
                }
                Thread.sleep(100)
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (e: Throwable) { failed = true; close() }
    }

    @Synchronized private fun transition(next: BotState) {
        if (state == BotState.STOPPED || (state == BotState.STOPPING && next != BotState.STOPPED)) return
        if (state != next) { val previous = state; state = next; events.publish(StateChanged(previous, next)) }
    }

    fun awaitShutdown() = stopped.await()

    @Synchronized override fun close() {
        if (state == BotState.STOPPING || state == BotState.STOPPED) return
        running.set(false)
        transition(BotState.STOPPING)
        if (Thread.currentThread() != supervisor) supervisor?.interrupt()
        listOfNotNull<AutoCloseable>(plugins, resources, web, notifications, mailTransports, music, tasks, rest, database, server).forEach { resource ->
            try { resource.close() } catch (e: Exception) {
                System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Shutdown failed: ${e.javaClass.simpleName}")
            }
        }
        transition(BotState.STOPPED)
        stopped.countDown()
    }
}
