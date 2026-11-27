package labs.cerberus.ts.plugin

import labs.cerberus.ts.command.*
import labs.cerberus.ts.configuration.YamlConfiguration
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.event.Listener
import labs.cerberus.ts.service.Task
import labs.cerberus.ts.service.TaskHandle
import labs.cerberus.ts.service.TaskExecution
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import labs.cerberus.ts.web.WebInterfaceService
import labs.cerberus.ts.web.WebPage
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration
import java.util.logging.Logger

/** Familiar Paper-style entry point. This is a Cerberus TS API, independent of Bukkit classes. */
abstract class JavaPlugin : CerberusPlugin, CommandExecutor {
    private var attached: PluginContext? = null
    protected val context: PluginContext get() = attached ?: error("Plugin has not been initialized by Cerberus TS")
    val description: PluginDescriptor get() = context.description
    val name: String get() = description.name
    val dataFolder: File get() = context.dataDirectory.toFile()
    val logger: Logger by lazy { Logger.getLogger("CerberusTS.$name") }
    val server: PluginServer by lazy { PluginServer(this) }
    @Volatile var isEnabled: Boolean = false; private set
    private val commands = linkedMapOf<String, PluginCommand>()
    private var configuration: YamlConfiguration? = null
    val config: YamlConfiguration get() = configuration ?: loadConfig().also { configuration = it }

    final override fun onLoad(context: PluginContext) {
        check(attached == null) { "Plugin already initialized" }
        attached = context
        val service = context.service<CommandService>()
        description.commands.forEach { (name, definition) ->
            val command = PluginCommand(name, definition, this)
            commands[name] = command
            (listOf(name) + definition.aliases).distinct().forEach { label ->
                check(commands[label] == null || commands[label] === command) { "Duplicate plugin command alias: $label" }
                commands[label] = command
                context.own(service.register(label, definition.description, definition.permission, definition.permissionMessage) {
                    command.execute(it, service.prefix)
                })
            }
        }
        onLoad()
    }

    open fun onLoad() {}
    override fun onEnable() {}
    override fun onDisable() {}
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>) = false
    fun getCommand(name: String): PluginCommand? = commands[name.lowercase(java.util.Locale.ROOT)]
    fun getResource(path: String): InputStream? = context.getResource(path)
    fun saveDefaultConfig() {
        val file = context.dataDirectory.resolve("config.yml")
        if (Files.notExists(file)) {
            val resource = getResource("config.yml") ?: error("Plugin has no bundled config.yml")
            resource.use { Files.copy(it, file) }
        }
    }
    private fun loadConfig(): YamlConfiguration = YamlConfiguration.loadConfiguration(context.dataDirectory.resolve("config.yml")).also { config ->
        getResource("config.yml")?.use(config::loadDefaults)
    }
    fun reloadConfig() { configuration = loadConfig() }
    fun saveConfig() = config.save(context.dataDirectory.resolve("config.yml"))
    fun <T : Any> getService(type: Class<T>): T = context.services.get(type)
    fun <T : AutoCloseable> own(resource: T): T = context.own(resource)
    fun registerController(controller: Any): AutoCloseable = context.controller(controller)
    fun registerCommand(spec: CommandSpec): AutoCloseable = context.command(spec)
    fun <T : Any> registerMatcher(type: Class<T>, matcher: ValueMatcher<T>): AutoCloseable = context.matcher(type, matcher)
    fun <T : Any> registerMatcher(matcher: CriterionMatcher<T>): AutoCloseable = context.matcher(matcher)
    internal fun updateEnabled(value: Boolean) { isEnabled = value }
    internal fun pluginContext(): PluginContext = context
}

class PluginServer internal constructor(private val owner: JavaPlugin) {
    val pluginManager = PluginAccess(owner)
    val scheduler = PluginScheduler()
    val teamSpeak: TeamSpeakServer get() = owner.getService(TeamSpeakServer::class.java)
    val web = PluginWebAccess(owner)
}

class PluginWebAccess internal constructor(private val owner: JavaPlugin) {
    val isAvailable: Boolean get() = owner.pluginContext().services.find(WebInterfaceService::class.java) != null
    fun registerPage(page: WebPage, assets: Map<String, String>): AutoCloseable {
        check(owner.isEnabled) { "Register web pages in onEnable" }
        return owner.own(owner.getService(WebInterfaceService::class.java).register(owner.description.id, owner.name, page, assets, owner::getResource))
    }
    fun route(method: String, path: String, action: (labs.cerberus.ts.service.RestRequest) -> labs.cerberus.ts.service.RestResponse): AutoCloseable {
        require(path.startsWith('/') && !path.startsWith("//"))
        return owner.pluginContext().route(method, "/api/plugins/${owner.description.id}$path", action)
    }
    fun registerController(controller: Any): AutoCloseable {
        owner.pluginContext().inject(controller)
        return owner.own(owner.getService(labs.cerberus.ts.service.RestService::class.java).registerController(controller, "/api/plugins/${owner.description.id}"))
    }
}

class PluginAccess internal constructor(private val owner: JavaPlugin) {
    fun registerEvents(listener: Listener, plugin: JavaPlugin): AutoCloseable {
        require(plugin.pluginContext().manager === owner.pluginContext().manager) { "Plugin belongs to a different bot" }
        check(plugin.isEnabled) { "Register event listeners in onEnable" }
        plugin.pluginContext().inject(listener)
        return plugin.own(plugin.getService(EventBus::class.java).register(listener, active = { plugin.isEnabled }))
    }
    fun callEvent(event: Any) = owner.getService(EventBus::class.java).publish(event)
    fun getPlugin(name: String): CerberusPlugin? = owner.pluginContext().manager.getPlugin(name)
}

class PluginTask internal constructor(private val handle: TaskHandle) : AutoCloseable {
    val taskId get() = handle.id
    val state get() = handle.state
    val isCancelled get() = handle.isCancelled
    val isCurrentlyRunning get() = handle.isRunning
    val snapshot get() = handle.snapshot
    fun cancel() = handle.close()
    fun awaitTermination(timeout: Duration) = handle.awaitTermination(timeout)
    override fun close() = cancel()
}

/** 20 ticks/second notation. Regular tasks share a serialized worker; async variants use the worker pool. */
class PluginScheduler internal constructor() {
    fun runTask(plugin: JavaPlugin, action: Runnable): PluginTask = schedule(plugin, action, 0, null, TaskExecution.SERIAL)
    fun runTaskLater(plugin: JavaPlugin, action: Runnable, delay: Long): PluginTask = schedule(plugin, action, delay, null, TaskExecution.SERIAL)
    fun runTaskTimer(plugin: JavaPlugin, action: Runnable, delay: Long, period: Long): PluginTask {
        require(period > 0) { "Period must be positive" }
        return schedule(plugin, action, delay, period, TaskExecution.SERIAL)
    }
    fun runTaskAsynchronously(plugin: JavaPlugin, action: Runnable): PluginTask = schedule(plugin, action, 0, null, TaskExecution.ASYNC)
    fun runTaskLaterAsynchronously(plugin: JavaPlugin, action: Runnable, delay: Long): PluginTask = schedule(plugin, action, delay, null, TaskExecution.ASYNC)
    fun runTaskTimerAsynchronously(plugin: JavaPlugin, action: Runnable, delay: Long, period: Long): PluginTask {
        require(period > 0) { "Period must be positive" }
        return schedule(plugin, action, delay, period, TaskExecution.ASYNC)
    }
    private fun schedule(plugin: JavaPlugin, action: Runnable, delay: Long, period: Long?, execution: TaskExecution): PluginTask {
        check(plugin.isEnabled) { "Schedule tasks in onEnable" }
        require(delay >= 0)
        fun ticks(value: Long) = Duration.ofMillis(Math.multiplyExact(value, 50))
        val builder = Task.builder().name("${plugin.description.id}:task").execution(execution).runnable(Runnable {
            if (plugin.isEnabled) action.run()
        })
        if (period == null) builder.delay(ticks(delay)) else builder.interval(ticks(period)).initialDelay(ticks(delay))
        val handle = plugin.pluginContext().task(builder.build())
        return PluginTask(handle)
    }
}
