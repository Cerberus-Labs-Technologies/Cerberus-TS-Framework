package labs.cerberus.ts

import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ProfileParityTest {
    @TempDir lateinit var directory: Path
    @Test fun `service identities options and lifecycle events survive reload with immutable snapshots`() {
        val path = directory.resolve("profiles.json"); val events = EventBus(); val history = mutableListOf<Any>()
        events.subscribe<ProfileCreated> { assertNotNull(ProfileService(path).find(it.profile.id)); history += it }
        events.subscribe<ProfileChanged> { history += it }
        events.subscribe<ProfileDeleted> { history += it }
        val profiles = ProfileService(path, events); val first = profiles.getOrCreate("same-id")
        val minecraft = profiles.getOrCreate("minecraft", "same-id")
        assertNotEquals(first.id, minecraft.id)
        profiles.link(first.id, "matrix", "@alice:example.test")
        assertFails { profiles.link(minecraft.id, "matrix", "@alice:example.test") }
        profiles.set(first.id, "notifications:email", "alice@example.test")
        profiles.set(first.id, "language", "de"); profiles.removeOption(first.id, "language")
        val reloaded = ProfileService(path)
        assertEquals(first.id, reloaded.find("matrix", "@alice:example.test")?.id)
        assertEquals(setOf("same-id"), reloaded.find(first.id)?.identities)
        assertNull(reloaded.find(first.id)?.option("language")); assertEquals("fallback", reloaded.find(first.id)?.option("missing", "fallback"))
        assertTrue(first.properties.isEmpty()); assertEquals(2, history.filterIsInstance<ProfileCreated>().size)
        assertEquals(4, history.filterIsInstance<ProfileChanged>().size)
        profiles.unlink(first.id, "matrix", "@alice:example.test")
        assertFails { profiles.unlink(first.id, UserIdentity.TEAMSPEAK, "same-id") }
        assertTrue(profiles.delete(first.id)); assertFalse(profiles.delete(first.id))
        assertEquals(first.id, history.filterIsInstance<ProfileDeleted>().single().id)
        assertNull(ProfileService(path).find("same-id"))
        assertNotNull(ProfileService(path).find("minecraft", "same-id"))
    }
    @Test fun `legacy merge overwrites options and publishes merge before deletion with full source snapshot`() {
        val path = directory.resolve("profiles.json"); val events = EventBus(); val seen = mutableListOf<String>()
        val profiles = ProfileService(path, events)
        val target = profiles.getOrCreate("one"); val source = profiles.getOrCreate("two")
        profiles.set(target.id, "language", "de"); profiles.set(source.id, "language", "en")
        profiles.link(source.id, "minecraft", UUID.randomUUID().toString())
        events.subscribe<ProfileMerged> {
            seen += "merge"; assertEquals("en", it.profile.option("language")); assertEquals(source.id, it.source.id)
            assertNull(ProfileService(path).find(source.id)); assertEquals(3, it.profile.linkedIdentities.size)
        }
        events.subscribe<ProfileDeleted> { seen += "delete"; assertEquals(source.id, it.id) }
        profiles.mergeProfiles(target.id, source.id)
        assertEquals(listOf("merge", "delete"), seen)
        assertEquals(target.id, ProfileService(path).find("two")?.id)
        assertEquals("en", ProfileService(path).find(target.id)?.option("language"))
    }
    @Test fun `pre-extension Cerberus profiles load and failed writes publish no event or in-memory mutation`() {
        val path = directory.resolve("profiles.json"); val id = UUID.randomUUID()
        Files.writeString(path, """{"profiles":[{"id":"$id","identities":["alice"],"properties":{"language":"de"}}]}""")
        val events = EventBus(); var changes = 0; events.subscribe<ProfileChanged> { changes++ }
        val profiles = ProfileService(path, events)
        assertEquals(id, profiles.find(UserIdentity.TEAMSPEAK, "alice")?.id)
        Files.delete(path); Files.createDirectory(path)
        assertFails { profiles.set(id, "language", "en") }
        assertEquals("de", profiles.find(id)?.option("language")); assertEquals(0, changes)
    }
    @Test fun `duplicate cross-service identities and malformed profile properties are refused without overwriting data`() {
        val path = directory.resolve("profiles.json")
        val profile = """{"id":"%s","identities":[],"linkedIdentities":[{"serviceId":"minecraft","identity":"alice"}],"properties":{}}"""
        val text = "{\"profiles\":[${profile.format(UUID.randomUUID())},${profile.format(UUID.randomUUID())}]}"
        Files.writeString(path, text); assertFails { ProfileService(path) }; assertEquals(text, Files.readString(path))
        Files.writeString(path, """{"profiles":[{"id":"${UUID.randomUUID()}","identities":["alice"],"properties":{"x":42}}]}""")
        assertFails { ProfileService(path) }
    }
}
