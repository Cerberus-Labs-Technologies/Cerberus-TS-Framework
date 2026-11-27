package labs.cerberus.ts.service

import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.io.InputStream
import labs.cerberus.ts.configuration.ConfigurationService
import labs.cerberus.ts.configuration.YamlConfiguration
import java.nio.file.Path
import java.io.File

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class LocaleUnit(val value: String = "", val defaultResource: String = "")

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class TransportUnit(val name: String = "default")

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Config(val id: String = "", val category: String = "", val format: String = "json")

class InjectionContext(val id: String, val resource: (String) -> InputStream?)

class Services {
    private val values = ConcurrentHashMap<Class<*>, Any>()
    fun <T : Any> register(type: Class<T>, instance: T): AutoCloseable {
        require(type.isInstance(instance))
        check(values.putIfAbsent(type, instance) == null) { "Service already registered: ${type.name}" }
        return AutoCloseable { values.remove(type, instance) }
    }
    fun <T : Any> get(type: Class<T>): T = type.cast(values[type] ?: error("Missing service: ${type.name}"))
    fun <T : Any> find(type: Class<T>): T? = values[type]?.let(type::cast)
    inline fun <reified T : Any> get(): T = get(T::class.java)
    @JvmOverloads fun inject(target: Any, context: InjectionContext? = null) {
        val assignments = generateSequence(target.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { it.isAnnotationPresent(Inject::class.java) || it.isAnnotationPresent(LocaleUnit::class.java) || it.isAnnotationPresent(TransportUnit::class.java) || it.isAnnotationPresent(Config::class.java) }
            .map { field ->
                require(!Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)) { "Injectable field must be mutable" }
                check(field.trySetAccessible()) { "Injectable field inaccessible" }
                val locale = field.getAnnotation(LocaleUnit::class.java)
                val transport = field.getAnnotation(TransportUnit::class.java)
                val config = field.getAnnotation(Config::class.java)
                require(listOfNotNull(locale, transport, config).size <= 1) { "Conflicting injection markers" }
                val value: Any = when {
                    config != null -> {
                        require(field.type in setOf(Path::class.java, File::class.java, YamlConfiguration::class.java)) { "@Config needs Path, File or YamlConfiguration" }
                        val id = config.id.ifEmpty { context?.id ?: (field.declaringClass.name + "." + field.name)
                            .replace(Regex("(?i)config|loader"), "").replace(Regex("[^A-Za-z0-9_.-]"), "-") }
                        val provider = get<ConfigurationService>()
                        when (field.type) {
                            Path::class.java -> provider.path(id, context?.id, config.category, config.format)
                            File::class.java -> provider.path(id, context?.id, config.category, config.format).toFile()
                            else -> provider.configuration(id, context?.id, config.category, config.format)
                        }
                    }
                    locale != null -> {
                        require(field.type.isAssignableFrom(LocalizationUnit::class.java)) { "@LocaleUnit needs a LocalizationUnit field" }
                        val unit = get<LocalizationService>().registerUnit(locale.value.ifBlank { context?.id ?: error("Default locale unit needs a plugin injection context") })
                        if (locale.defaultResource.isNotEmpty()) {
                            if (context == null) unit.loadDefaultsFromResource(target.javaClass.classLoader, locale.defaultResource)
                            else (context.resource(locale.defaultResource) ?: error("Missing locale resource: ${locale.defaultResource}")).use(unit::loadDefaults)
                        }
                        unit
                    }
                    transport != null -> get<MailTransports>().require(transport.name)
                    else -> get(field.type)
                }
                require(field.type.isInstance(value)) { "Injected value has wrong type: ${field.name}" }
                field to value
            }.toList()
        assignments.forEach { (field, value) -> field.set(target, value) }
    }
}
