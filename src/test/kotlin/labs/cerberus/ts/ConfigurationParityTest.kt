package labs.cerberus.ts

import com.fasterxml.jackson.databind.ObjectMapper
import labs.cerberus.ts.configuration.*
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConfigurationParityTest {
    @TempDir lateinit var directory: Path
    @Test fun `categorized config injection provides paths files and shared configuration with valid json output`() {
        class Target {
            @Config lateinit var main: YamlConfiguration
            @Config lateinit var path: Path
            @Config lateinit var file: File
            @Config(id = "mail", category = "services/mail", format = "yaml") lateinit var mail: YamlConfiguration
        }
        val provider = ConfigurationService(directory)
        val services = Services().apply { register(ConfigurationService::class.java, provider) }
        val context = InjectionContext("community") { null }
        val target = Target(); services.inject(target, context)
        assertEquals(directory.resolve("community/community.json"), target.path)
        assertEquals(target.path.toFile(), target.file)
        target.main.set("greeting", "Grüße aus Kotlin"); target.main.set("limit", 42); target.main.save()
        assertEquals("Grüße aus Kotlin", ObjectMapper().readTree(Files.readString(target.path))["greeting"].asText())
        val second = Target(); services.inject(second, context); assertSame(target.main, second.main)
        target.mail.loadDefaults("enabled: true\nname: default\n".byteInputStream())
        target.mail.set("name", "custom"); target.mail.save()
        assertTrue(target.mail.getBoolean("enabled")); assertEquals("custom", target.mail.getString("name"))
        assertTrue(Files.exists(directory.resolve("community/services/mail/mail.yml")))
        assertEquals(42, ConfigurationService(directory).configuration("community", "community").getInt("limit"))
        assertFails { provider.path("../escape") }; assertFails { provider.path("file", category = "../escape") }
        assertFails { provider.path("file", owner = "/outside") }; assertFails { provider.path("file", format = "unsupported") }
    }

    @Test fun `reload applies external edits but malformed json never replaces valid live values`() {
        val provider = ConfigurationService(directory)
        val configuration = provider.configuration("settings")
        val file = provider.path("settings")
        configuration.set("greeting", "Original"); configuration.save()
        Files.writeString(file, """{"greeting":"Edited","nested":{"limit":7}}""")
        configuration.reload(); assertEquals("Edited", configuration.getString("greeting")); assertEquals(7, configuration.getInt("nested.limit"))
        for (invalid in listOf("{truncated", "greeting: YAML", "{} {}", "[]", "{\"greeting\":\"one\",\"greeting\":\"two\"}")) {
            Files.writeString(file, invalid); assertFails { configuration.reload() }
            assertEquals("Edited", configuration.getString("greeting")); assertEquals(invalid, Files.readString(file))
        }
    }

    @Test fun `context free names and qualifier validation do not partially assign target fields`() {
        class Standalone { @Config lateinit var configFile: Path }
        val provider = ConfigurationService(directory)
        val services = Services().apply { register(ConfigurationService::class.java, provider) }
        val standalone = Standalone(); services.inject(standalone)
        assertTrue(standalone.configFile.startsWith(directory)); assertTrue(standalone.configFile.fileName.toString().endsWith(".json"))
        class Invalid {
            @Config(id = "good") lateinit var good: Path
            @Config @LocaleUnit("bad") lateinit var bad: Path
            fun assigned() = ::good.isInitialized
        }
        val invalid = Invalid(); assertFails { services.inject(invalid) }; assertFalse(invalid.assigned())
        class WrongType { @Config lateinit var bad: String }
        assertFails { services.inject(WrongType()) }
    }
}
