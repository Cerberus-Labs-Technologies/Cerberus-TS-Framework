package example.setup

import labs.cerberus.ts.configuration.YamlConfiguration
import labs.cerberus.ts.query.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** Reconciles only objects recorded in this installation's state. No permission reset or force deletion. */
class SetupEngine(private val query: (QueryCommand) -> QueryResponse, private val directory: Path) {
    data class Result(val changes: List<String>, val warnings: List<String>, val dryRun: Boolean)
    private data class Owned(val id: Int, var name: String, val permissions: MutableSet<String>)
    private val stateFile = directory.resolve("state.yml")

    @Synchronized fun reconcile(config: SetupConfig, dryRun: Boolean): Result {
        val lockPath = directory.resolve("setup.lock")
        if (dryRun && Files.notExists(lockPath)) return reconcileLocked(config, true)
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "Ein anderer Server-Setup-Prozess verwendet diesen Zustand bereits." }
            lock.use { reconcileLocked(config, dryRun) }
        }
    }

    private fun reconcileLocked(config: SetupConfig, dryRun: Boolean): Result {
        val changes = mutableListOf<String>(); val warnings = mutableListOf<String>()
        fun execute(name: String, parameters: Map<String, String> = emptyMap(), options: Set<String> = emptySet()): List<QueryRow> {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return query(QueryCommand(name, parameters, options)).checked().rows
        }
        fun read(name: String, vararg parameters: Pair<String, Any>) = execute(name, parameters.associate { it.first to it.second.toString() })
        fun optionalRows(name: String, parameters: Map<String, String>, options: Set<String> = emptySet()) = try {
            execute(name, parameters, options)
        } catch (e: QueryException) { if (e.error.id == 1281) emptyList() else throw e }
        fun mutate(label: String, name: String, parameters: Map<String, String>) {
            changes += label
            if (!dryRun) execute(name, parameters)
        }
        var virtualId = -1
        fun create(label: String, name: String, parameters: Map<String, String>, idField: String): Int {
            changes += label
            return if (dryRun) virtualId-- else execute(name, parameters).single().getValue(idField).toInt()
        }

        val info = read("serverinfo").single()
        val serverUid = info.getValue("virtualserver_unique_identifier")
        val state = YamlConfiguration.loadConfiguration(stateFile)
        require(state.getString("server-uid") in setOf(null, serverUid)) { "state.yml gehört zu einem anderen TeamSpeak-Server." }
        val token = state.getString("owner") ?: UUID.randomUUID().toString()
        fun load(path: String): MutableMap<String, Owned> {
            val values = state.get(path) ?: return linkedMapOf()
            require(values is Map<*, *>) { "Beschädigter Zustand: $path." }
            return values.entries.associateTo(linkedMapOf()) { (key, raw) ->
                require(key is String && raw is Map<*, *>) { "Beschädigter Zustand: $path." }
                val id = (raw["id"] as? Number)?.toInt() ?: error("Fehlende ID in $path.$key.")
                require(id > 0)
                key to Owned(id, raw["name"] as? String ?: error("Fehlender Name in $path.$key."),
                    (raw["permissions"] as? List<*>)?.map { it as String }?.toMutableSet() ?: linkedSetOf())
            }.also { require(it.values.map(Owned::id).distinct().size == it.size) { "Doppelte IDs in $path." } }
        }
        val ownedChannels = load("channels"); val ownedServerGroups = load("server-groups"); val ownedChannelGroups = load("channel-groups")
        fun save() {
            if (dryRun) return
            state.set("server-uid", serverUid); state.set("owner", token)
            fun store(path: String, entries: Map<String, Owned>) = state.set(path, entries.mapValues { (_, v) ->
                mapOf("id" to v.id, "name" to v.name, "permissions" to v.permissions.sorted())
            })
            store("channels", ownedChannels); store("server-groups", ownedServerGroups); store("channel-groups", ownedChannelGroups)
            state.save(stateFile)
            if (Files.getFileStore(stateFile).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-------"))
        }
        val channels = read("channellist").associateBy { it.getValue("cid").toInt() }.toMutableMap()
        val channelInfo = channels.keys.associateWith { read("channelinfo", "cid" to it).single() }.toMutableMap()
        val serverGroups = read("servergrouplist").associateBy { it.getValue("sgid").toInt() }.toMutableMap()
        val channelGroups = read("channelgrouplist").associateBy { it.getValue("cgid").toInt() }.toMutableMap()
        fun marker(key: String) = "[i]Cerberus setup: $token/$key[/i]"
        // Ownership is checked before ANY mutation, including server display settings.
        ownedChannels.entries.toList().forEach { (key, owned) ->
            if (owned.id !in channels) ownedChannels.remove(key)
            else require(channelInfo.getValue(owned.id)["channel_description"].orEmpty().contains(marker(key))) {
                "Channel ${owned.id}: Eigentumsmarkierung fehlt; Abgleich abgebrochen."
            }
        }
        fun validateGroups(owned: MutableMap<String, Owned>, current: Map<Int, QueryRow>, defaultFields: Set<String>) {
            val defaultGroupIds = info.filterKeys { it in defaultFields }.values.mapNotNull(String::toIntOrNull).toSet()
            owned.entries.toList().forEach { (key, entry) ->
                val row = current[entry.id]
                if (row == null) owned.remove(key)
                else require(row["type"] == "1" && row["name"] == entry.name && entry.id !in defaultGroupIds) {
                    "Gruppe ${entry.id}: Name, Typ oder Standardschutz geändert; Abgleich abgebrochen."
                }
            }
        }
        validateGroups(ownedServerGroups, serverGroups, setOf("virtualserver_default_server_group"))
        validateGroups(ownedChannelGroups, channelGroups, setOf("virtualserver_default_channel_group", "virtualserver_default_channel_admin_group"))
        config.channels.forEach { spec ->
            val parentId = spec.parent?.let { ownedChannels[it]?.id }
            if (spec.parent == null || parentId != null) require(channels.none { (id, row) ->
                id != ownedChannels[spec.key]?.id && row["channel_name"] == spec.name && row["pid"]?.toInt() == (parentId ?: 0)
            }) { "Channel-Name bereits belegt: ${spec.name}. Keine automatische Übernahme fremder Channels." }
        }
        fun validateSpecs(specs: List<GroupSpec>, owned: Map<String, Owned>, current: Map<Int, QueryRow>) {
            specs.forEach { spec ->
                require(current.none { (id, row) -> row["type"] == "1" && row["name"] == spec.name && owned[spec.key]?.id != id }) {
                    "Gruppenname bereits belegt: ${spec.name}."
                }
                if (spec.key !in owned && spec.template != null) require(current.values.count { it["type"] == "0" && it["name"] == spec.template } == 1) {
                    "Eindeutige Typ-0-Vorlage fehlt: ${spec.template}."
                }
            }
        }
        validateSpecs(config.serverGroups, ownedServerGroups, serverGroups)
        validateSpecs(config.channelGroups, ownedChannelGroups, channelGroups)
        val requestedPermissions = (config.channels.flatMap { it.permissions.keys } +
            config.serverGroups.flatMap { it.permissions.keys } + config.channelGroups.flatMap { it.permissions.keys }).toSet()
        if (requestedPermissions.isNotEmpty()) {
            val available = read("permissionlist").mapNotNull { it["permname"] }.toSet()
            require(available.containsAll(requestedPermissions)) { "Unbekannte TS-Permissions: ${requestedPermissions - available}." }
        }
        if (!dryRun) {
            Files.createDirectories(directory)
            val backupPath = directory.resolve("initial-inventory.yml")
            if (Files.notExists(backupPath)) {
                val backup = YamlConfiguration.loadConfiguration(backupPath)
                backup.set("server-uid", serverUid)
                backup.set("server", info.filterKeys { it in SetupConfig.serverFields })
                backup.set("channels", channelInfo.mapKeys { it.key.toString() })
                backup.set("server-groups", serverGroups.values.toList()); backup.set("channel-groups", channelGroups.values.toList())
                backup.save(backupPath)
                if (Files.getFileStore(backupPath).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(backupPath, PosixFilePermissions.fromString("rw-------"))
            }
            save() // Persist the owner before the first create, and journal each successful mutation below.
        }
        val serverChanges = config.server.filter { (key, value) -> info[key] != value }
        if (serverChanges.isNotEmpty()) mutate("Servername/Begrüßung aktualisieren", "serveredit", serverChanges)

        fun syncPermissions(kind: String, idField: String, owned: Owned, desired: Map<String, Int>, synthetic: Boolean = false) {
            val existing = if (synthetic || owned.id < 0) emptyMap() else optionalRows("${kind}permlist", mapOf(idField to owned.id.toString()), setOf("permsid"))
                .associateBy { it.getValue("permsid") }
            desired.forEach { (permission, value) ->
                val current = existing[permission]
                if (current?.get("permvalue") != value.toString() ||
                    (kind == "servergroup" && (current["permnegated"] != "0" || current["permskip"] != "0"))) {
                    val parameters = linkedMapOf(idField to owned.id.toString(), "permsid" to permission, "permvalue" to value.toString())
                    if (kind == "servergroup") { parameters["permnegated"] = "0"; parameters["permskip"] = "0" }
                    // Record intent first: an interrupted response must still permit later removal of this override.
                    owned.permissions += permission; save()
                    mutate("${owned.name}: $permission = $value", "${kind}addperm", parameters)
                }
                owned.permissions += permission
            }
            (owned.permissions - desired.keys).forEach { permission ->
                if (permission in existing) mutate("${owned.name}: $permission entfernen", "${kind}delperm",
                    mapOf(idField to owned.id.toString(), "permsid" to permission))
                owned.permissions -= permission; save()
            }
            save()
        }

        fun syncGroups(kind: String, idField: String, specs: List<GroupSpec>, owned: MutableMap<String, Owned>, current: MutableMap<Int, QueryRow>) {
            specs.forEach { spec ->
                var entry = owned[spec.key]
                var created = false
                if (entry == null) {
                    val template = spec.template?.let { name -> current.entries.single { it.value["type"] == "0" && it.value["name"] == name }.key }
                    val parameters = linkedMapOf("name" to spec.name, "type" to "1")
                    if (template != null) {
                        parameters[if (kind == "servergroup") "ssgid" else "scgid"] = template.toString()
                        parameters[if (kind == "servergroup") "tsgid" else "tcgid"] = "0"
                    }
                    val id = create("${if (kind == "servergroup") "Servergruppe" else "Channelgruppe"} erstellen: ${spec.name}",
                        kind + if (template == null) "add" else "copy", parameters, idField)
                    entry = Owned(id, spec.name, linkedSetOf()); owned[spec.key] = entry; save(); created = true
                    current[id] = mapOf(idField to id.toString(), "name" to spec.name, "type" to "1")
                }
                if (entry.name != spec.name) {
                    mutate("Gruppe umbenennen: ${entry.name} → ${spec.name}", "${kind}rename", mapOf(idField to entry.id.toString(), "name" to spec.name))
                    entry.name = spec.name; save()
                }
                syncPermissions(kind, idField, entry, spec.permissions, dryRun && created)
            }
        }
        syncGroups("servergroup", "sgid", config.serverGroups, ownedServerGroups, serverGroups)
        syncGroups("channelgroup", "cgid", config.channelGroups, ownedChannelGroups, channelGroups)

        val previousSibling = mutableMapOf<Int, Int>()
        config.channels.forEach { spec ->
            val parent = spec.parent?.let { ownedChannels.getValue(it).id } ?: 0
            var entry = ownedChannels[spec.key]
            val description = spec.properties["channel_description"].orEmpty() + "\n\n" + marker(spec.key)
            val properties = linkedMapOf("channel_name" to spec.name, "channel_flag_permanent" to "1",
                "channel_description" to description) + spec.properties.filterKeys { it != "channel_description" }
            if (entry == null) {
                val id = create("Channel erstellen: ${spec.name}", "channelcreate", properties + ("cpid" to parent.toString()), "cid")
                entry = Owned(id, spec.name, linkedSetOf()); ownedChannels[spec.key] = entry; save()
                channels[id] = mapOf("pid" to parent.toString(), "channel_name" to spec.name)
                channelInfo[id] = properties
                if (!dryRun) {
                    channels.clear(); channels.putAll(read("channellist").associateBy { it.getValue("cid").toInt() })
                }
            } else {
                val changed = properties.filter { (key, value) -> channelInfo.getValue(entry.id)[key] != value }
                if (changed.isNotEmpty()) mutate("Channel bearbeiten: ${spec.name}", "channeledit", mapOf("cid" to entry.id.toString()) + changed)
                entry.name = spec.name; save()
            }
            val order = previousSibling[parent] ?: channels.entries.lastOrNull { (id, row) ->
                id !in ownedChannels.values.map(Owned::id) && row["pid"]?.toInt() == parent
            }?.key ?: 0
            val current = channels[entry.id]
            if (current?.get("pid") != parent.toString() || current["channel_order"] != order.toString()) {
                mutate("Channel anordnen: ${spec.name}", "channelmove", mapOf("cid" to entry.id.toString(), "cpid" to parent.toString(), "order" to order.toString()))
                channels[entry.id] = current.orEmpty() + mapOf("pid" to parent.toString(), "channel_order" to order.toString())
                if (!dryRun) {
                    channels.clear(); channels.putAll(read("channellist").associateBy { it.getValue("cid").toInt() })
                }
            }
            previousSibling[parent] = entry.id
            syncPermissions("channel", "cid", entry, spec.permissions)
        }

        if (config.prune) {
            val obsolete = ownedChannels.filterKeys { key -> config.channels.none { it.key == key } }.toMutableMap()
            // Children first; occupied channels and parents with retained/unmanaged children are deferred.
            while (obsolete.isNotEmpty()) {
                val leaf = obsolete.entries.firstOrNull { (key, entry) ->
                    obsolete.none { (other, child) -> other != key && channels[child.id]?.get("pid") == entry.id.toString() }
                } ?: error("Zyklische bestehende Channel-Hierarchie.")
                val entry = leaf.value
                val fresh = if (dryRun) channels else read("channellist").associateBy { it.getValue("cid").toInt() }
                val blocked = fresh[entry.id]?.get("total_clients")?.toIntOrNull()?.let { it > 0 } == true ||
                    fresh.values.any { it["pid"] == entry.id.toString() } || channelInfo[entry.id]?.get("channel_flag_default") == "1"
                if (blocked) warnings += "Channel behalten (belegt, Standard oder Unterchannels): ${entry.name}"
                else {
                    mutate("Channel löschen: ${entry.name}", "channeldelete", mapOf("cid" to entry.id.toString(), "force" to "0"))
                    ownedChannels.remove(leaf.key); channels.remove(entry.id); save()
                }
                obsolete.remove(leaf.key)
            }
            fun pruneGroups(kind: String, idField: String, specs: List<GroupSpec>, owned: MutableMap<String, Owned>) {
                owned.filterKeys { key -> specs.none { it.key == key } }.forEach { (key, entry) ->
                    if (optionalRows("${kind}clientlist", mapOf(idField to entry.id.toString())).isNotEmpty())
                        warnings += "Gruppe behalten (noch zugewiesen): ${entry.name}"
                    else {
                        mutate("Gruppe löschen: ${entry.name}", "${kind}del", mapOf(idField to entry.id.toString(), "force" to "0"))
                        owned.remove(key); save()
                    }
                }
            }
            pruneGroups("servergroup", "sgid", config.serverGroups, ownedServerGroups)
            pruneGroups("channelgroup", "cgid", config.channelGroups, ownedChannelGroups)
        }
        save()
        return Result(changes, warnings, dryRun)
    }
}
