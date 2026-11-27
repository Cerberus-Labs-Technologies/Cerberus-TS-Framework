package labs.cerberus.ts.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import labs.cerberus.ts.teamspeak.Client
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Optional

/** Each unit has its own persistent templates; resource defaults preserve operator overrides. */
class LocalizationUnit internal constructor(val unitId: String, private val service: LocalizationService, path: Path?) {
    private data class State(val defaultLanguage: String?, val langs: Map<String, Map<String, String>>)
    private val store = path?.let(::JsonStore)
    @Volatile private var state: State = store?.read()?.let(::parse) ?: State(null, emptyMap())
    fun context(locale: String = service.fallbackLocale.toLanguageTag()) = getContext(Locale.forLanguageTag(localeTag(locale)))
    fun getContext(locale: Locale): LocaleContext = LocaleContext(this, Locale.forLanguageTag(localeTag(locale.toLanguageTag())))
    fun getContext(client: Client): LocaleContext = getContext(service.localeOfClient(client))
    fun getContext(country: String): LocaleContext = getContext(service.localeForCountry(country))
    fun contextForUser(uniqueId: String): LocaleContext = getContext(service.localeOfUser(uniqueId))
    @Synchronized fun loadDefaultsFromFile(path: Path) = Files.newInputStream(path).use(::loadDefaults)
    fun loadDefaultsFromResource(loader: ClassLoader, resource: String) {
        require(resource.isNotBlank() && !resource.startsWith('/') && '\\' !in resource && resource.split('/').none { it in setOf(".", "..") })
        val input = loader.getResourceAsStream(resource) ?: error("Missing locale defaults resource: $resource")
        input.use(::loadDefaults)
    }
    fun loadDefaults(input: InputStream) = loadDefaults(ObjectMapper().readTree(input) ?: error("Empty locale defaults"))
    fun loadDefaults(defaults: Map<String, Any>) = loadDefaults(ObjectMapper().valueToTree<JsonNode>(defaults))
    @Synchronized private fun loadDefaults(root: JsonNode) {
        val defaults = parse(root)
        val current = state
        val merged = (defaults.langs.keys + current.langs.keys).associateWith { locale ->
            defaults.langs[locale].orEmpty() + current.langs[locale].orEmpty()
        }
        commit(State(current.defaultLanguage ?: defaults.defaultLanguage, merged))
    }
    @Synchronized fun set(locale: String, key: String, template: String?) {
        require(key.isNotBlank())
        val tag = localeTag(locale)
        val messages = if (template == null) state.langs[tag].orEmpty() - key else state.langs[tag].orEmpty() + (key to template)
        commit(state.copy(langs = state.langs + (tag to messages)))
    }
    @Synchronized fun reload() {
        val source = store ?: error("In-memory localization unit has no file")
        state = parse(source.read())
    }
    internal fun resolved(locale: Locale): Locale {
        val current = state
        return candidates(locale, current).firstOrNull { it in current.langs }?.let(Locale::forLanguageTag) ?: service.fallbackLocale
    }
    internal fun find(locale: Locale, key: String): String? {
        val current = state
        return candidates(locale, current).mapNotNull { current.langs[it]?.get(key) }.firstOrNull()
    }
    private fun candidates(locale: Locale, current: State): List<String> =
        listOfNotNull(locale.toLanguageTag(), locale.language, current.defaultLanguage,
            current.defaultLanguage?.let { Locale.forLanguageTag(it).language }, service.fallbackLocale.toLanguageTag(), service.fallbackLocale.language).distinct()
    private fun commit(updated: State) {
        store?.write(mapOf("langs" to updated.langs) + (updated.defaultLanguage?.let { mapOf("defaultLanguage" to it) } ?: emptyMap()))
        state = updated
    }
    private fun parse(root: JsonNode): State {
        require(root.isObject) { "Localization unit must be an object" }
        val default = root.get("defaultLanguage") ?: root.get("default")
        require(default == null || default.isTextual) { "Invalid default language" }
        val langs = root.get("langs") ?: return State(default?.asText()?.let(::localeTag), emptyMap())
        require(langs.isObject) { "Localization langs must be an object" }
        val parsed = linkedMapOf<String, Map<String, String>>()
        langs.properties().forEach { (locale, messages) ->
            val tag = localeTag(locale)
            require(tag !in parsed && messages.isObject) { "Invalid or duplicate locale" }
            parsed[tag] = messages.properties().associate { (key, value) ->
                require(key.isNotBlank() && value.isTextual) { "Message templates must be strings" }; key to value.asText()
            }
        }
        return State(default?.asText()?.let(::localeTag), parsed)
    }
}

/** Contexts read the current unit state, so new defaults/reloads do not leave cached stale messages. */
class LocaleContext internal constructor(private val unit: LocalizationUnit, private val requested: Locale) {
    val unitId get() = unit.unitId
    val locale: Locale get() = unit.resolved(requested)
    fun getMessage(key: String): String = unit.find(requested, key) ?: throw NoSuchElementException("Missing translation: $unitId/$key")
    /** Legacy convenience behavior: incomplete parameters return the untouched template. */
    fun getMessage(key: String, parameters: Map<String, *>): String {
        val template = getMessage(key)
        return try { formatLocaleMessage(template, parameters) } catch (_: MissingLocaleParameterException) { template }
    }
    @JvmOverloads fun text(key: String, parameters: Map<String, *> = emptyMap<String, Any>()): String = formatLocaleMessage(getMessage(key), parameters)
    fun uncheckedGetMessage(key: String): String? = unit.find(requested, key)
    fun uncheckedGetMessage(key: String, parameters: Map<String, *>): String? = uncheckedGetMessage(key)?.let { getMessage(key, parameters) }
    fun optMessage(key: String): Optional<String> = Optional.ofNullable(uncheckedGetMessage(key))
    fun optMessage(key: String, parameters: Map<String, *>): Optional<String> = Optional.ofNullable(uncheckedGetMessage(key, parameters))
}
