package labs.cerberus.ts.plugin

import labs.cerberus.ts.command.CommandContext
import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.command.CommandSpec
import labs.cerberus.ts.command.CriterionMatcher
import labs.cerberus.ts.command.ValueMatcher
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.service.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.jar.JarFile
import java.io.InputStream
import java.io.FilterInputStream

interface CerberusPlugin {
    fun onLoad(context: PluginContext) {}
    fun onEnable() {}
    fun onDisable() {}
}

class ResourceScope : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    @Synchronized fun <T : AutoCloseable> own(resource: T): T {
        if (closed) { resource.close(); error("Plugin scope is closed") }
        resources += resource
        return resource
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        resources.asReversed().forEach {
            try { it.close() } catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        resources.clear()
        failure?.let { throw it }
    }
}

class PluginContext internal constructor(val id: String, val services: Services, val dataDirectory: Path,
    private val scope: ResourceScope, val description: PluginDescriptor,
    internal val manager: PluginManager, private val resources: (String) -> InputStream?) {
    fun getResource(path: String): InputStream? {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it == "." })
        return resources(path)
    }
    fun <T : AutoCloseable> own(resource: T) = scope.own(resource)
    fun inject(target: Any) = services.inject(target, InjectionContext(id, ::getResource))
    fun listen(listener: Any): AutoCloseable {
        inject(listener)
        return own(services.get<EventBus>().register(listener))
    }
    inline fun <reified T : Any> service(): T = services.get()
    fun <T : Any> on(type: Class<T>, order: Int = 0, action: (T) -> Unit) =
        own(services.get<EventBus>().subscribe(type, order, listener = action))
    inline fun <reified T : Any> on(order: Int = 0, noinline action: (T) -> Unit) = on(T::class.java, order, action)
    fun command(name: String, description: String = "", permission: String? = null, action: (CommandContext) -> Unit) =
        own(services.get<CommandService>().register(name, description, permission) {
            if (manager.isEnabled(id)) action(it) else it.reply("Plugin is not enabled.")
        })
    fun command(spec: CommandSpec) = own(services.get<CommandService>().registerCommand(spec, available = { manager.isEnabled(id) }))
    fun <T : Any> matcher(type: Class<T>, matcher: ValueMatcher<T>) = own(services.get<CommandService>().matchers.register(type, matcher))
    fun <T : Any> matcher(matcher: CriterionMatcher<T>): AutoCloseable {
        inject(matcher)
        return own(services.get<CommandService>().matchers.registerMatcher(matcher))
    }
    fun task(delay: Duration = Duration.ZERO, interval: Duration? = null, action: () -> Unit) =
        own(services.get<TaskService>().schedule(delay, interval, action))
    fun task(task: Task): TaskHandle = own(services.get<TaskService>().scheduleTask(task))
    fun route(method: String, path: String, action: (RestRequest) -> RestResponse) =
        own(services.get<RestService>().route(method, path, action))
    fun controller(controller: Any): AutoCloseable {
        inject(controller)
        return own(services.get<RestService>().registerController(controller))
    }
    fun <T : Any> provide(type: Class<T>, instance: T) = own(services.register(type, instance))
}

class PluginManager(private val services: Services, private val dataDirectory: Path) : AutoCloseable {
    private data class Loaded(val descriptor: PluginDescriptor, val plugin: CerberusPlugin, val scope: ResourceScope,
        @Volatile var enabled: Boolean = false)
    private val loaded = java.util.concurrent.CopyOnWriteArrayList<Loaded>()
    private var classLoader: URLClassLoader? = null
    private var started = false
    fun descriptions(): List<PluginDescriptor> = loaded.map { it.descriptor }
    internal fun isEnabled(id: String): Boolean = loaded.firstOrNull { it.descriptor.id == id }?.enabled == true
    internal fun persistenceClassLoader(): ClassLoader = classLoader ?: CerberusPlugin::class.java.classLoader
    /** Scan bytecode first, loading only mapped types without running their static initializers. */
    internal fun persistenceClasses(): Set<Class<*>> {
        val loader = classLoader ?: return emptySet()
        if (loader.urLs.isEmpty()) return emptySet()
        return io.github.classgraph.ClassGraph().enableClasspathEntries(*loader.urLs)
            .enableClassInfo().enableAnnotationInfo().scan().use { scan ->
                setOf("jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass", "jakarta.persistence.Converter")
                    .flatMap { scan.getClassesWithAnnotation(it).map { info -> Class.forName(info.name, false, loader) } }.toSet()
            }
    }

    fun load(directory: Path) {
        check(!started); started = true
        Files.createDirectories(directory)
        val jars = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList() }
        val descriptors = jars.map { path ->
            JarFile(path.toFile()).use { jar ->
                val yaml = jar.getJarEntry("plugin.yml")
                val legacy = jar.getJarEntry("META-INF/cerberus.properties")
                require(yaml == null || legacy == null) { "Use plugin.yml or META-INF/cerberus.properties, not both: ${path.fileName}" }
                if (yaml != null) jar.getInputStream(yaml).use(PluginDescriptor::readYaml)
                else {
                    val entry = legacy ?: error("Missing plugin.yml: ${path.fileName}")
                    val properties = Properties()
                    jar.getInputStream(entry).use(properties::load)
                    PluginDescriptor.read(properties)
                }
            }
        }
        val ordered = dependencyOrder(descriptors)
        val sources = descriptors.zip(jars).associate { it.first.id to it.second }
        val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), CerberusPlugin::class.java.classLoader)
        classLoader = loader
        try {
            ordered.forEach { descriptor ->
                val plugin = Class.forName(descriptor.main, true, loader).asSubclass(CerberusPlugin::class.java)
                    .getDeclaredConstructor().newInstance()
                val scope = ResourceScope()
                val entry = Loaded(descriptor, plugin, scope)
                loaded += entry
                val path = dataDirectory.resolve(descriptor.id)
                Files.createDirectories(path)
                val context = PluginContext(descriptor.id, services, path, scope, descriptor, this) { name ->
                    val jar = JarFile(sources.getValue(descriptor.id).toFile())
                    val resource = jar.getJarEntry(name)
                    if (resource == null) { jar.close(); null }
                    else object : FilterInputStream(jar.getInputStream(resource)) {
                        override fun close() { try { super.close() } finally { jar.close() } }
                    }
                }
                context.inject(plugin)
                plugin.onLoad(context)
                scope.own(services.get<EventBus>().register(plugin, includeEventHandlers = false))
            }
        } catch (e: Throwable) {
            try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun enable() {
        try {
            loaded.forEach { entry ->
                if (!entry.enabled) {
                    entry.enabled = true
                    (entry.plugin as? JavaPlugin)?.updateEnabled(true)
                    entry.plugin.onEnable()
                }
            }
        } catch (e: Throwable) {
            try { close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun descriptors(): List<PluginDescriptor> = loaded.map { it.descriptor }
    fun getPlugin(name: String): CerberusPlugin? = loaded.find { it.descriptor.id == name.lowercase(java.util.Locale.ROOT) }?.plugin

    override fun close() {
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        loaded.asReversed().forEach { entry ->
            entry.enabled = false
            (entry.plugin as? JavaPlugin)?.updateEnabled(false)
            // onDisable also runs after a partially completed onLoad, so plugin-owned resources can be released.
            cleanup { entry.plugin.onDisable() }
            cleanup { entry.scope.close() }
        }
        loaded.clear()
        cleanup { classLoader?.close() }
        classLoader = null
        failure?.let { throw it }
    }

    companion object {
        fun dependencyOrder(descriptors: List<PluginDescriptor>): List<PluginDescriptor> {
            val byId = descriptors.associateBy { it.id }
            require(byId.size == descriptors.size) { "Duplicate plugin ID" }
            val parents = byId.mapValues { (_, value) -> value.dependencies.toMutableSet() }
            fun reaches(from: String, target: String, seen: Set<String> = emptySet()): Boolean =
                from == target || (from !in seen && parents[from].orEmpty().any { reaches(it, target, seen + from) })
            // Validate mandatory dependencies before optional ordering can influence traversal.
            byId.forEach { (id, descriptor) ->
                descriptor.dependencies.forEach { dependency ->
                    require(dependency in byId) { "Missing plugin dependency: $dependency" }
                    require(!reaches(dependency, id)) { "Circular plugin dependency: $id" }
                }
            }
            fun optional(child: String, parent: String) {
                if (child in byId && parent in byId && !reaches(parent, child)) parents.getValue(child).add(parent)
            }
            byId.keys.sorted().forEach { id ->
                byId.getValue(id).softDependencies.sorted().forEach { optional(id, it) }
                byId.getValue(id).loadBefore.sorted().forEach { optional(it, id) }
            }
            val result = mutableListOf<PluginDescriptor>()
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                if (id in visited) return
                require(visiting.add(id)) { "Circular plugin dependency: $id" }
                val descriptor = byId[id] ?: error("Missing plugin dependency: $id")
                parents.getValue(id).sorted().forEach(::visit)
                visiting.remove(id); visited.add(id); result += descriptor
            }
            byId.keys.sorted().forEach(::visit)
            return result
        }
    }
}
