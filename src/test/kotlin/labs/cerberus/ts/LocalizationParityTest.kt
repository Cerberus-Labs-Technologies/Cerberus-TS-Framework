package labs.cerberus.ts

import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.configuration.ConfigurationService
import labs.cerberus.ts.event.*
import labs.cerberus.ts.plugin.*
import labs.cerberus.ts.query.*
import labs.cerberus.ts.service.*
import labs.cerberus.ts.teamspeak.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class LocaleFixturePlugin : JavaPlugin() {
    @LocaleUnit(defaultResource = "locale/messages.json") lateinit var messages: LocalizationUnit
    @TransportUnit(name = "alerts") lateinit var mail: MailTransport
    @Config(id = "settings", category = "nested", format = "yaml") lateinit var settingsFile: Path
    override fun onLoad() {
        check(messages.unitId == description.id); check(messages.context("de").getMessage("hello") == "Hallo")
        check(settingsFile.fileName.toString() == "settings.yml"); check(settingsFile.parent.fileName.toString() == "nested")
    }
}

class LocalizationParityTest {
    @TempDir lateinit var directory: Path
    private fun defaults() = mapOf("langs" to mapOf(
        "en" to mapOf("hello" to "Hello %[name] %[name]", "fallback" to "Fallback", "tail" to "%[value]"),
        "de" to mapOf("hello" to "Hallo %[name]")))

    @Test fun `units load original Jeak defaults preserve overrides isolate keys and refresh existing contexts`() {
        val service = LocalizationService(directory = directory)
        val unit = service.registerUnit("welcome")
        assertSame(unit, service.registerUnit("welcome"))
        unit.loadDefaultsFromFile(Path.of("legacy/src/test/resources/localization/testUnit.json"))
        assertEquals("Dies ist die deutsche Nachricht.", unit.context("de-DE").getMessage("test.message"))
        val context = unit.context("de-AT")
        unit.loadDefaults(defaults()); assertEquals(Locale.GERMAN, context.locale)
        assertEquals("Hallo Anna", context.text("hello", mapOf("name" to "Anna")))
        assertEquals("Fallback", context.getMessage("fallback"))
        unit.set("de", "hello", "Servus %[name]")
        unit.loadDefaults(defaults())
        assertEquals("Servus Anna", context.text("hello", mapOf("name" to "Anna")))
        val other = service.registerUnit("music"); other.loadDefaults(mapOf("langs" to mapOf("de" to mapOf("hello" to "Musik"))))
        assertEquals("Musik", other.context("de").getMessage("hello")); assertEquals("Servus %[name]", context.getMessage("hello"))
        val reloaded = LocalizationService(directory = directory).registerUnit("welcome")
        assertEquals("Servus %[name]", reloaded.context("de").getMessage("hello"))
        unit.set("de", "hello", null); assertEquals("Hello Anna Anna", context.text("hello", mapOf("name" to "Anna")))
        assertFails { service.registerUnit("../escape") }
    }

    @Test fun `raw lenient strict and optional messages preserve original parameter behavior without recursive interpolation`() {
        val unit = LocalizationService().registerUnit("messages"); unit.loadDefaults(defaults())
        val context = unit.context("fr")
        assertEquals(Locale.ENGLISH, context.locale)
        assertEquals("Hello %[name] %[name]", context.getMessage("hello"))
        assertEquals("Hello %[name] %[name]", context.getMessage("hello", emptyMap<String, String>()))
        assertFailsWith<MissingLocaleParameterException> { context.text("hello") }
        assertEquals("Hello $1\\%[name] $1\\%[name]", context.text("hello", mapOf("name" to "$1\\%[name]")))
        assertEquals("Ende", context.text("tail", mapOf("value" to "Ende")))
        assertTrue(context.optMessage("missing").isEmpty); assertNull(context.uncheckedGetMessage("missing", emptyMap<String, String>()))
        assertFailsWith<NoSuchElementException> { context.getMessage("missing") }
        unit.set("fr", "hello", "Salut")
        assertEquals(Locale.FRENCH, context.locale); assertEquals("Salut", context.getMessage("hello"))
    }

    @Test fun `language preferences country mappings and fallback persist and reset without retaining old contexts`() {
        val service = LocalizationService(directory = directory)
        service.setCountryLocale("DE", Locale.GERMAN)
        service.setCountryLocale("CH", Locale.forLanguageTag("de-CH"))
        val client = Client(1, 1, null, "alice", "Alice", false, mapOf("client_country" to "DE"))
        assertEquals(Locale.GERMAN, service.localeOfClient(client))
        service.setLocaleForClient("alice", Locale.forLanguageTag("fr-CA"))
        assertEquals("fr-CA", service.localeOfClient(client).toLanguageTag())
        assertEquals("fr-CA", LocalizationService(directory = directory).localeOfUser("alice").toLanguageTag())
        service.setLocaleForClient("alice", null); assertEquals(Locale.GERMAN, service.localeOfClient(client))
        assertEquals("de-CH", service.localeForCountry("ch").toLanguageTag())
        service.setFallbackLocale(Locale.forLanguageTag("en-GB"))
        assertEquals("en-GB", service.localeOfUser("offline").toLanguageTag()); assertEquals("en-GB", service.localeForCountry("??").toLanguageTag())
        val reloaded = LocalizationService(directory = directory)
        assertEquals("en-GB", reloaded.fallbackLocale.toLanguageTag()); assertEquals(Locale.GERMAN, reloaded.localeOfClient(client))
        assertFails { service.setLocaleForClient("alice", Locale.ROOT) }
        assertFails { service.setCountryLocale("invalid", Locale.ENGLISH) }
    }

    @Test fun `failed writes and malformed defaults do not change live localization state`() {
        val service = LocalizationService(directory = directory)
        val unit = service.registerUnit("test"); unit.loadDefaults(defaults())
        val file = directory.resolve("units/test.json")
        Files.delete(file); Files.createDirectory(file)
        assertFails { unit.set("de", "hello", "Changed") }
        assertEquals("Hallo %[name]", unit.context("de").getMessage("hello"))
        assertFails { unit.loadDefaults(mapOf("langs" to mapOf("de" to mapOf("hello" to 1)))) }
        assertFails { unit.loadDefaults(mapOf("langs" to mapOf("de_DE" to emptyMap<String, String>(), "de-DE" to emptyMap<String, String>()))) }
        assertFails { unit.context("bad tag!") }
        service.setLocaleForClient("user", Locale.GERMAN)
        val settings = directory.resolve("settings.json")
        Files.delete(settings); Files.createDirectory(settings)
        assertFails { service.setLocaleForClient("user", Locale.FRENCH) }
        assertEquals(Locale.GERMAN, service.localeOfUser("user"))
    }

    @Test fun `unit default language properties bundles and stale registration handles have independent lifecycles`() {
        val service = LocalizationService()
        val unit = service.registerUnit("test")
        unit.loadDefaults(mapOf("defaultLanguage" to "de", "langs" to mapOf("de" to mapOf("hello" to "Hallo"), "en" to mapOf("hello" to "Hello"))))
        assertEquals("Hallo", unit.context("fr").getMessage("hello"))
        unit.loadDefaults(mapOf("defaultLanguage" to "en", "langs" to emptyMap<String, Any>()))
        assertEquals("Hallo", unit.context("fr").getMessage("hello"))
        val properties = directory.resolve("en.properties"); Files.writeString(properties, "hello=Hello %[name]\n")
        val old = service.load("en", properties)
        assertEquals("Hello Anna", service.text("hello", parameters = mapOf("name" to "Anna")))
        old.close(); val replacement = service.load("en", properties); old.close()
        assertEquals("Hello Anna", service.text("hello", parameters = mapOf("name" to "Anna"))); replacement.close()
        assertFails { service.text("hello") }
    }

    @Test fun `plugin jar default resource injection resolves plugin id and named mail transport before onLoad`() {
        val jars = Files.createDirectories(directory.resolve("jars"))
        JarOutputStream(Files.newOutputStream(jars.resolve("locale.jar"))).use { jar ->
            fun resource(name: String, content: String) {
                jar.putNextEntry(JarEntry(name)); jar.write(content.toByteArray()); jar.closeEntry()
            }
            resource("plugin.yml", "name: LocaleFixture\nversion: '1'\nmain: labs.cerberus.ts.LocaleFixturePlugin\n")
            resource("locale/messages.json", """{"langs":{"de":{"hello":"Hallo"}}}""")
        }
        val mail = MailTransport {}
        val services = Services().apply {
            register(ConfigurationService::class.java, ConfigurationService(directory.resolve("configuration")))
            register(LocalizationService::class.java, LocalizationService(directory = directory.resolve("locale")))
            register(MailTransports::class.java, MailTransports().apply { register("alerts", mail) })
            register(EventBus::class.java, EventBus())
        }
        TeamSpeakServer(ConnectionConfig(password = "fixture"), EventBus()).use { server ->
            services.register(CommandService::class.java, CommandService(server, PermissionService(directory.resolve("permissions.json"))))
            PluginManager(services, directory.resolve("plugins-data")).use { manager ->
                manager.load(jars); manager.enable()
                val plugin = manager.getPlugin("localefixture") as LocaleFixturePlugin
                assertSame(mail, plugin.mail); assertEquals("localefixture", plugin.messages.unitId)
            }
        }
        class Invalid {
            @Inject lateinit var events: EventBus
            @LocaleUnit("broken") lateinit var invalid: String
            fun wasInjected() = ::events.isInitialized
        }
        val invalid = Invalid(); assertFails { services.inject(invalid) }; assertFalse(invalid.wasInjected())
        class MissingContext { @LocaleUnit lateinit var locale: LocalizationUnit }
        assertFails { services.inject(MissingContext()) }
    }

    @Test fun `locale commands persist self settings protect other users and remove both registrations`() {
        val messages = mutableListOf<String>()
        val query = object : QueryConnection {
            override var isOpen = true
            override val supportsNotifications = true
            override fun execute(command: QueryCommand): QueryResponse {
                val rows = when (command.name) {
                    "version" -> listOf(mapOf("version" to "6.0.0-beta14"))
                    "whoami" -> listOf(mapOf("client_id" to "99", "virtualserver_id" to "1"))
                    "clientinfo" -> listOf(mapOf("client_unique_identifier" to "alice", "client_country" to "DE", "client_nickname" to "Alice", "client_servergroups" to "6"))
                    "sendtextmessage" -> { messages += command.parameters.getValue("msg"); emptyList() }
                    else -> emptyList()
                }
                return QueryResponse(rows, QueryError(0, "ok"))
            }
            override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
            override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
            override fun close() { isOpen = false }
        }
        TeamSpeakServer(ConnectionConfig(password = "fixture"), EventBus()) { query }.use { server ->
            server.connect()
            val permissions = PermissionService(directory.resolve("permissions.json"))
            val commands = CommandService(server, permissions)
            val service = LocalizationService(directory = directory.resolve("locale")); service.setCountryLocale("DE", Locale.GERMAN)
            val registration = service.installCommands(commands, server)
            fun chat(command: String) { messages.clear(); commands.handle(TextMessage(1, "alice", "Alice", 1, "!$command")) }
            chat("locale"); assertEquals(listOf("de"), messages)
            chat("locale fr"); assertEquals(Locale.FRENCH, service.localeOfUser("alice"))
            chat("locale en bob"); assertEquals(listOf("Permission denied."), messages); assertEquals(Locale.ENGLISH, service.localeOfUser("bob"))
            permissions.grantUser("alice", "cerberus.locale.manage", 1)
            chat("locale-arg --locale=de --user=bob"); assertEquals(Locale.GERMAN, service.localeOfUser("bob"))
            chat("locale reset"); assertEquals(Locale.ENGLISH, service.localeOfUser("alice"))
            val replies = mutableListOf<String>(); commands.console("locale-arg --locale=fr --user=offline", replies::add)
            assertEquals(Locale.FRENCH, LocalizationService(directory = directory.resolve("locale")).localeOfUser("offline"))
            registration.close(); replies.clear(); commands.console("locale", replies::add)
            assertTrue(replies.single().startsWith("Unknown command"))
        }
    }
}
