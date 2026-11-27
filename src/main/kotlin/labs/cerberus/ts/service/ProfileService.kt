package labs.cerberus.ts.service

import labs.cerberus.ts.event.EventBus
import java.nio.file.Path
import java.util.UUID

/** The legacy service identifier is retained for stored TS3 and TS6 identities. */
data class UserIdentity(val serviceId: String, val identity: String) {
    init { require(serviceId.isNotBlank() && identity.isNotBlank()) }
    companion object { const val TEAMSPEAK = "teamspeak3" }
}

data class UserProfile(val id: UUID, val identities: Set<String>, val properties: Map<String, String>,
    val linkedIdentities: Set<UserIdentity> = identities.map { UserIdentity(UserIdentity.TEAMSPEAK, it) }.toSet()) {
    fun identities(serviceId: String): Set<String> = linkedIdentities.filter { it.serviceId == serviceId }.map { it.identity }.toSet()
    fun option(key: String, default: String? = null): String? = properties[key] ?: default
}
data class ProfileCreated(val profile: UserProfile)
data class ProfileChanged(val previous: UserProfile, val profile: UserProfile)
data class ProfileMerged(val profile: UserProfile, val source: UserProfile)
data class ProfileDeleted(val profile: UserProfile) { val id get() = profile.id }
enum class ProfileMergePolicy { DESTINATION_WINS, SOURCE_WINS }

/** Persists the complete mutation before publishing events; failed writes leave memory unchanged. */
class ProfileService @JvmOverloads constructor(path: Path, private val events: EventBus? = null) {
    private val store = JsonStore(path)
    private val profiles = linkedMapOf<UUID, UserProfile>()
    init {
        val root = store.read()
        require(!root.has("profiles") || root["profiles"].isArray) { "profiles must be an array" }
        root.path("profiles").forEach { node ->
            val id = UUID.fromString(node.path("id").asText())
            require(id !in profiles) { "Duplicate profile ID" }
            val linked = if (node.has("linkedIdentities")) {
                require(node["linkedIdentities"].isArray) { "linkedIdentities must be an array" }
                node["linkedIdentities"].map {
                    require(it.path("serviceId").isTextual && it.path("identity").isTextual) { "Invalid linked identity" }
                    UserIdentity(it["serviceId"].asText(), it["identity"].asText())
                }.toSet()
            } else {
                require(node.path("identities").isArray) { "identities must be an array" }
                node["identities"].map {
                    require(it.isTextual) { "Invalid TeamSpeak identity" }; UserIdentity(UserIdentity.TEAMSPEAK, it.asText())
                }.toSet()
            }
            require(linked.isNotEmpty()) { "Profile must have at least one identity" }
            require(node.path("properties").isObject) { "Profile properties must be an object" }
            val properties = node["properties"].properties().associate { (key, value) ->
                require(value.isTextual) { "Profile property must be text" }; key to value.asText()
            }
            profiles[id] = snapshot(id, linked, properties)
        }
        val identities = profiles.values.flatMap { it.linkedIdentities }
        require(identities.size == identities.toSet().size) { "Duplicate profile identity" }
    }

    @Synchronized fun all(): List<UserProfile> = profiles.values.toList()
    @Synchronized fun find(id: UUID): UserProfile? = profiles[id]
    @Synchronized fun find(identity: String): UserProfile? = find(UserIdentity.TEAMSPEAK, identity)
    @Synchronized fun find(serviceId: String, identity: String): UserProfile? =
        profiles.values.find { UserIdentity(serviceId, identity) in it.linkedIdentities }
    @Synchronized fun getOrCreate(identity: String): UserProfile = getOrCreate(UserIdentity.TEAMSPEAK, identity)
    @Synchronized fun getOrCreate(serviceId: String, identity: String): UserProfile {
        val linked = UserIdentity(serviceId, identity)
        return find(serviceId, identity) ?: snapshot(UUID.randomUUID(), setOf(linked), emptyMap()).also { profile ->
            commit(profiles + (profile.id to profile)); events?.publish(ProfileCreated(profile))
        }
    }
    @Synchronized fun set(id: UUID, key: String, value: String?) {
        require(key.isNotBlank())
        val profile = profiles.getValue(id)
        val properties = if (value == null) profile.properties - key else profile.properties + (key to value)
        change(profile, snapshot(id, profile.linkedIdentities, properties))
    }
    fun removeOption(id: UUID, key: String) = set(id, key, null)
    @Synchronized fun link(id: UUID, identity: String) = link(id, UserIdentity.TEAMSPEAK, identity)
    @Synchronized fun link(id: UUID, serviceId: String, identity: String) {
        val linked = UserIdentity(serviceId, identity)
        require((find(serviceId, identity)?.id ?: id) == id) { "Identity already linked to another profile" }
        val profile = profiles.getValue(id)
        change(profile, snapshot(id, profile.linkedIdentities + linked, profile.properties))
    }
    @Synchronized fun unlink(id: UUID, serviceId: String, identity: String) {
        val profile = profiles.getValue(id)
        val linked = profile.linkedIdentities - UserIdentity(serviceId, identity)
        require(linked.isNotEmpty()) { "Cannot unlink the last profile identity" }
        change(profile, snapshot(id, linked, profile.properties))
    }
    @Synchronized fun merge(into: UUID, other: UUID) = merge(into, other, ProfileMergePolicy.DESTINATION_WINS)
    @Synchronized fun merge(into: UUID, other: UUID, policy: ProfileMergePolicy) {
        require(into != other)
        val target = profiles.getValue(into); val source = profiles.getValue(other)
        val properties = if (policy == ProfileMergePolicy.SOURCE_WINS) target.properties + source.properties else source.properties + target.properties
        val merged = snapshot(into, target.linkedIdentities + source.linkedIdentities, properties)
        commit((profiles - other) + (into to merged))
        events?.publish(ProfileMerged(merged, source)); events?.publish(ProfileDeleted(source))
    }
    /** Jeak's merge operation overwrites destination options with source options. */
    fun mergeProfiles(into: UUID, other: UUID) = merge(into, other, ProfileMergePolicy.SOURCE_WINS)
    @Synchronized fun delete(id: UUID): Boolean {
        val profile = profiles[id] ?: return false
        commit(profiles - id); events?.publish(ProfileDeleted(profile)); return true
    }
    private fun change(previous: UserProfile, updated: UserProfile) {
        if (previous == updated) return
        commit(profiles + (updated.id to updated)); events?.publish(ProfileChanged(previous, updated))
    }
    private fun commit(updated: Map<UUID, UserProfile>) {
        store.write(mapOf("profiles" to updated.values.toList()))
        profiles.clear(); profiles.putAll(updated)
    }
    private fun snapshot(id: UUID, identities: Set<UserIdentity>, properties: Map<String, String>) = UserProfile(id,
        java.util.Set.copyOf(identities.filter { it.serviceId == UserIdentity.TEAMSPEAK }.map { it.identity }),
        java.util.Map.copyOf(properties), java.util.Set.copyOf(identities))
}
