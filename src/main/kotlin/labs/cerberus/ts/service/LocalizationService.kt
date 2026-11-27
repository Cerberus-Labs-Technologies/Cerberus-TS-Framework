package labs.cerberus.ts.service

import java.util.Locale
import java.util.Properties
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import labs.cerberus.ts.teamspeak.Client

class LocalizationService @JvmOverloads constructor(fallback: String = "en", private val directory: Path? = null) {
    private data class Settings(val fallbackLocale: String, val customLangs: Map<String, String>, val countryLocales: Map<String, String>)
    private val store = directory?.resolve("settings.json")?.let(::JsonStore)
    @Volatile private var settings: Settings = readSettings(localeTag(fallback))
    private val bundles = ConcurrentHashMap<String, Map<String, String>>()
    private val units = ConcurrentHashMap<String, LocalizationUnit>()
    val fallbackLocale: Locale get() = Locale.forLanguageTag(settings.fallbackLocale)
    fun register(locale: String, messages: Map<String, String>): AutoCloseable {
        val tag = localeTag(locale)
        val snapshot = messages.toMap()
        check(bundles.putIfAbsent(tag, snapshot) == null) { "Locale already registered" }
        val active = AtomicBoolean(true)
        return AutoCloseable { if (active.compareAndSet(true, false)) bundles.remove(tag, snapshot) }
    }
    fun load(locale: String, file: Path): AutoCloseable {
        val properties = Properties()
        Files.newBufferedReader(file).use(properties::load)
        return register(locale, properties.stringPropertyNames().associateWith(properties::getProperty))
    }
    fun text(key: String, locale: String = settings.fallbackLocale, parameters: Map<String, Any> = emptyMap()): String {
        val parsed = Locale.forLanguageTag(localeTag(locale))
        val template = sequenceOf(parsed.toLanguageTag(), parsed.language, settings.fallbackLocale, fallbackLocale.language).distinct()
            .mapNotNull { bundles[it]?.get(key) }.firstOrNull() ?: throw NoSuchElementException("Missing translation: $key")
        return formatLocaleMessage(template, parameters)
    }
    fun registerUnit(id: String): LocalizationUnit {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "Invalid localization unit ID" }
        return units.computeIfAbsent(id) { LocalizationUnit(it, this, directory?.resolve("units")?.resolve("$it.json")) }
    }
    fun localeOfClient(client: Client): Locale = localeOfIdentity(client.uniqueId, client.properties["client_country"])
    fun localeOfUser(uniqueId: String): Locale = localeOfIdentity(uniqueId, null)
    fun localeOfIdentity(uniqueId: String?, country: String? = null): Locale =
        uniqueId?.let { settings.customLangs[it] }?.let(Locale::forLanguageTag) ?: country?.let(::localeForCountry) ?: fallbackLocale
    /** Ambiguous multilingual countries use the fallback unless countryLocales explicitly maps them. */
    fun localeForCountry(country: String): Locale {
        val code = country.uppercase(Locale.ROOT)
        settings.countryLocales[code]?.let { return Locale.forLanguageTag(it) }
        if (code !in Locale.getISOCountries()) return fallbackLocale
        val languages = Locale.getAvailableLocales().filter { it.country == code && it.language.isNotEmpty() }.map { it.language }.distinct()
        val language = languages.singleOrNull() ?: return fallbackLocale
        return Locale.Builder().setLanguage(language).setRegion(code).build()
    }
    @Synchronized fun setLocaleForClient(uniqueId: String, locale: Locale?) {
        require(uniqueId.isNotBlank())
        val updated = if (locale == null) settings.customLangs - uniqueId else settings.customLangs + (uniqueId to localeTag(locale.toLanguageTag()))
        commit(settings.copy(customLangs = updated))
    }
    @Synchronized fun setFallbackLocale(locale: Locale) = commit(settings.copy(fallbackLocale = localeTag(locale.toLanguageTag())))
    @Synchronized fun setCountryLocale(country: String, locale: Locale?) {
        val code = country.uppercase(Locale.ROOT)
        require(code in Locale.getISOCountries()) { "Unknown country code" }
        commit(settings.copy(countryLocales = if (locale == null) settings.countryLocales - code else
            settings.countryLocales + (code to localeTag(locale.toLanguageTag()))))
    }
    private fun commit(updated: Settings) {
        store?.write(mapOf("fallbackLocale" to updated.fallbackLocale, "customLangs" to updated.customLangs, "countryLocales" to updated.countryLocales))
        settings = updated
    }
    private fun readSettings(fallback: String): Settings {
        val root = store?.read() ?: return Settings(fallback, emptyMap(), emptyMap())
        fun languages(key: String): Map<String, String> {
            if (!root.has(key)) return emptyMap()
            require(root[key].isObject) { "Invalid locale settings: $key" }
            return root[key].properties().associate { (id, value) ->
                require(id.isNotBlank() && value.isTextual) { "Invalid locale preference" }
                id to localeTag(value.asText())
            }
        }
        val configuredFallback = if (root.has("fallbackLocale")) {
            require(root["fallbackLocale"].isTextual); localeTag(root["fallbackLocale"].asText())
        } else fallback
        val countries = languages("countryLocales")
        require(countries.keys.all { it == it.uppercase(Locale.ROOT) && it in Locale.getISOCountries() }) { "Invalid country locale mapping" }
        return Settings(configuredFallback, languages("customLangs"), countries)
    }
}

internal fun localeTag(value: String): String = try {
    Locale.Builder().setLanguageTag(value.replace('_', '-')).build().also {
        require(it.language.isNotBlank() && it.language != "und") { "A locale needs a language" }
    }.toLanguageTag()
} catch (error: java.util.IllformedLocaleException) { throw IllegalArgumentException("Invalid locale tag", error) }

class MissingLocaleParameterException(val parameter: String) : IllegalArgumentException("Missing translation parameter: $parameter")
private val localeParameter = Regex("%\\[([a-zA-Z.0-9_-]+)]")
internal fun formatLocaleMessage(template: String, parameters: Map<String, *>): String = localeParameter.replace(template) {
    parameters[it.groupValues[1]]?.toString() ?: throw MissingLocaleParameterException(it.groupValues[1])
}
