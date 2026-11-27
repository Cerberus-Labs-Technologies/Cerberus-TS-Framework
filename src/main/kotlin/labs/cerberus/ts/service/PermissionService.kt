package labs.cerberus.ts.service

import java.nio.file.Path

data class PermissionGroup(val id: String, val permissions: Map<String, Int>, val parents: Set<String>, val serverGroup: Int?)
data class PermissionSubject(val id: String, val permissions: Map<String, Int>, val groups: Set<String>)

/** Framework permissions are independent of TeamSpeak's own permission engine. */
class PermissionService(path: Path) {
    private data class Subject(val permissions: MutableMap<String, Int> = linkedMapOf(),
        val parents: MutableSet<String> = linkedSetOf(), var serverGroup: Int? = null)
    private val store = JsonStore(path)
    private val users = linkedMapOf<String, Subject>()
    private val groups = linkedMapOf<String, Subject>()

    init {
        val root = store.read()
        fun load(key: String, target: MutableMap<String, Subject>) {
            root.path(key).properties().forEach { (id, value) ->
                target[id] = Subject(value.path("permissions").properties().associate { it.key to it.value.intValue() }.toMutableMap(),
                    value.path("parents").map { it.asText() }.toMutableSet(), value.get("serverGroup")?.takeUnless { it.isNull }?.intValue())
            }
        }
        load("users", users); load("groups", groups)
        (users.values + groups.values).forEach { subject -> require(subject.parents.all(groups::containsKey)) { "Missing permission group" } }
        groups.keys.forEach { validateGraph(it, emptySet()) }
    }

    @Synchronized fun createGroup(id: String) {
        require(id.matches(Regex("[a-z][a-z0-9_.-]*")))
        require(id !in groups) { "Group already exists" }
        groups[id] = Subject(); save()
    }

    @Synchronized fun grantUser(uniqueId: String, permission: String, value: Int) {
        validatePermission(permission)
        users.getOrPut(uniqueId) { Subject() }.permissions[permission] = value; save()
    }
    @Synchronized fun grantGroup(id: String, permission: String, value: Int) {
        validatePermission(permission)
        groups.getValue(id).permissions[permission] = value; save()
    }
    @Synchronized fun addMember(uniqueId: String, group: String) {
        require(group in groups)
        users.getOrPut(uniqueId) { Subject() }.parents.add(group); save()
    }
    @Synchronized fun removeMember(uniqueId: String, group: String) {
        users[uniqueId]?.parents?.remove(group); save()
    }
    @Synchronized fun inherit(group: String, parent: String) {
        require(parent in groups)
        val subject = groups.getValue(group)
        if (!subject.parents.add(parent)) return
        try { validateGraph(group, emptySet()) }
        catch (e: Exception) { subject.parents.remove(parent); throw e }
        save()
    }
    @Synchronized fun linkServerGroup(group: String, serverGroup: Int?) {
        require(serverGroup == null || serverGroup > 0)
        groups.getValue(group).serverGroup = serverGroup; save()
    }
    @Synchronized fun removeUserPermission(uniqueId: String, permission: String) {
        users[uniqueId]?.permissions?.remove(permission); save()
    }
    @Synchronized fun removeGroupPermission(group: String, permission: String) {
        groups.getValue(group).permissions.remove(permission); save()
    }
    @Synchronized fun findGroup(id: String): PermissionGroup? = groups[id]?.let {
        PermissionGroup(id, java.util.Map.copyOf(it.permissions), java.util.Set.copyOf(it.parents), it.serverGroup)
    }
    @Synchronized fun findSubject(id: String): PermissionSubject? = users[id]?.let {
        PermissionSubject(id, java.util.Map.copyOf(it.permissions), java.util.Set.copyOf(it.parents))
    }
    @Synchronized fun groups(): List<PermissionGroup> = groups.keys.mapNotNull(::findGroup)

    @Synchronized fun value(uniqueId: String, permission: String, serverGroups: Set<Int> = emptySet()): Int? {
        validatePermission(permission)
        val user = users[uniqueId]
        match(user?.permissions.orEmpty(), permission)?.let { return it }
        val memberships = user?.parents.orEmpty() + groups.filterValues { it.serverGroup in serverGroups }.keys
        val inherited = memberships.mapNotNull { groupValue(it, permission, emptySet()) }
        return inherited.filter { it <= 0 }.minOrNull() ?: inherited.maxOrNull()
    }
    fun allows(uniqueId: String, permission: String, serverGroups: Set<Int> = emptySet(), minimum: Int = 1): Boolean =
        (value(uniqueId, permission, serverGroups) ?: 0) >= minimum

    private fun groupValue(id: String, permission: String, seen: Set<String>): Int? {
        check(id !in seen) { "Circular permission inheritance" }
        val subject = groups.getValue(id)
        match(subject.permissions, permission)?.let { return it }
        val values = subject.parents.mapNotNull { groupValue(it, permission, seen + id) }
        return values.filter { it <= 0 }.minOrNull() ?: values.maxOrNull()
    }
    private fun match(values: Map<String, Int>, permission: String): Int? {
        values[permission]?.let { return it }
        var prefix = permission
        while ('.' in prefix) {
            prefix = prefix.substringBeforeLast('.')
            values["$prefix.*"]?.let { return it }
        }
        return values["*"]
    }
    private fun validateGraph(id: String, seen: Set<String>) {
        require(id !in seen) { "Circular permission inheritance" }
        groups.getValue(id).parents.forEach { validateGraph(it, seen + id) }
    }
    private fun validatePermission(permission: String) {
        require(permission.matches(Regex("(?:[a-zA-Z0-9_-]+:)?(?:[a-zA-Z0-9_-]+\\.)*(?:[a-zA-Z0-9_-]+|\\*)"))) { "Invalid permission" }
    }
    private fun save() {
        fun serialize(values: Map<String, Subject>) = values.mapValues { (_, subject) ->
            mapOf("permissions" to subject.permissions, "parents" to subject.parents, "serverGroup" to subject.serverGroup)
        }
        store.write(mapOf("users" to serialize(users), "groups" to serialize(groups)))
    }
}
