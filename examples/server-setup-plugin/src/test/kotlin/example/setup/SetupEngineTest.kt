package example.setup

import labs.cerberus.ts.configuration.YamlConfiguration
import labs.cerberus.ts.query.*
import labs.cerberus.ts.command.CommandService
import labs.cerberus.ts.event.EventBus
import labs.cerberus.ts.plugin.PluginManager
import labs.cerberus.ts.service.Services
import labs.cerberus.ts.service.PermissionService
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import kotlin.test.*

class SetupEngineTest {
    @TempDir lateinit var directory: Path
    private fun config(prune: Boolean = true) = SetupConfig(true, prune, mapOf("virtualserver_name" to "Community"), listOf(
        ChannelSpec("area", "Community", null, emptyMap(), emptyMap()),
        ChannelSpec("talk", "Talk", "area", mapOf("channel_topic" to "Hallo"), mapOf("i_channel_needed_join_power" to 25))
    ), listOf(GroupSpec("member", "CL | Mitglied", "Normal", mapOf("i_group_sort_id" to 30))),
        listOf(GroupSpec("operator", "CL | Operator", "Operator", mapOf("i_group_sort_id" to 20))))

    @Test fun `restart reconciles without duplicates or unnecessary writes and leaves native objects unchanged`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory)
        val nativeChannels = ts.channels.toMap(); val nativeGroups = ts.serverGroups.toMap()
        val first = engine.reconcile(config(), false)
        assertTrue(first.changes.isNotEmpty()); assertTrue(first.warnings.isEmpty())
        val ids = ts.channels.keys.toSet(); val groups = ts.serverGroups.keys.toSet()
        ts.writes.clear()
        val second = SetupEngine(ts::execute, directory).reconcile(config(), false)
        assertTrue(second.changes.isEmpty(), second.changes.toString()); assertTrue(ts.writes.isEmpty())
        assertEquals(ids, ts.channels.keys); assertEquals(groups, ts.serverGroups.keys)
        nativeChannels.forEach { (id, row) -> assertEquals(row, ts.channels[id]) }
        nativeGroups.forEach { (id, row) -> assertEquals(row, ts.serverGroups[id]) }
        assertTrue(Files.exists(directory.resolve("initial-inventory.yml")))
        assertTrue(ts.writes.none { it.name == "permreset" })
    }

    @Test fun `rename reparent permission changes and removals retain stable ids`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); val before = config()
        engine.reconcile(before, false)
        val talk = ts.channels.entries.single { it.value["channel_name"] == "Talk" }.key
        val member = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        val updated = before.copy(channels = before.channels.map { if (it.key == "talk") it.copy(name = "Plaudern", parent = null,
            properties = mapOf("channel_topic" to "Neu"), permissions = emptyMap()) else it },
            serverGroups = listOf(before.serverGroups.single().copy(name = "CL | Stammgast", permissions = mapOf("i_group_sort_id" to 35))))
        ts.writes.clear(); engine.reconcile(updated, false)
        assertEquals("Plaudern", ts.channels[talk]?.get("channel_name")); assertEquals("0", ts.channels[talk]?.get("pid"))
        assertEquals("Neu", ts.channels[talk]?.get("channel_topic")); assertTrue(ts.permissions.getValue("channel" to talk).isEmpty())
        assertEquals("CL | Stammgast", ts.serverGroups[member]?.get("name"))
        assertEquals("35", ts.permissions.getValue("servergroup" to member)["i_group_sort_id"]?.get("permvalue"))
        assertTrue(ts.writes.none { it.name.endsWith("copy") || it.name.endsWith("create") })
        ts.writes.clear(); assertTrue(engine.reconcile(updated, false).changes.isEmpty())
    }

    @Test fun `prune deletes owned leaf channels and both group kinds without force`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); val before = config()
        engine.reconcile(before, false); ts.writes.clear()
        engine.reconcile(before.copy(channels = emptyList(), serverGroups = emptyList(), channelGroups = emptyList()), false)
        val deletions = ts.writes.filter { it.name.endsWith("delete") || it.name.endsWith("del") }
        assertEquals(listOf("channeldelete", "channeldelete", "servergroupdel", "channelgroupdel"), deletions.map { it.name })
        assertTrue(deletions.all { it.parameters["force"] == "0" })
        assertEquals(setOf(1, 2), ts.channels.keys)
        assertEquals(8, ts.serverGroups.size); assertEquals(8, ts.channelGroups.size)
    }

    @Test fun `occupied channels parents with unmanaged children and assigned groups are deferred`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); val before = config()
        engine.reconcile(before, false)
        val talk = ts.channels.entries.single { it.value["channel_name"] == "Talk" }.key
        ts.channels[talk] = ts.channels.getValue(talk) + ("total_clients" to "1")
        val member = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        val operator = ts.channelGroups.entries.single { it.value["name"] == "CL | Operator" }.key
        ts.assigned += "servergroup" to member; ts.assigned += "channelgroup" to operator
        ts.writes.clear()
        val result = engine.reconcile(before.copy(channels = emptyList(), serverGroups = emptyList(), channelGroups = emptyList()), false)
        assertEquals(4, result.warnings.size)
        assertTrue(ts.writes.isEmpty()); assertTrue(talk in ts.channels)
        ts.channels[talk] = ts.channels.getValue(talk) + ("total_clients" to "0")
        ts.channels[999] = mapOf("cid" to "999", "pid" to talk.toString(), "channel_name" to "Von Hand", "total_clients" to "0")
        assertEquals(4, engine.reconcile(before.copy(channels = emptyList(), serverGroups = emptyList(), channelGroups = emptyList()), false).warnings.size)
        assertTrue(999 in ts.channels)
    }

    @Test fun `plan never mutates server or state including initial setup and pruning`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory)
        assertTrue(engine.reconcile(config(), true).changes.isNotEmpty())
        assertTrue(ts.writes.isEmpty()); assertFalse(Files.exists(directory.resolve("state.yml")))
        engine.reconcile(config(), false)
        val original = Files.readString(directory.resolve("state.yml")); ts.writes.clear()
        assertTrue(engine.reconcile(config().copy(channels = emptyList(), serverGroups = emptyList()), true).changes.isNotEmpty())
        assertTrue(ts.writes.isEmpty()); assertEquals(original, Files.readString(directory.resolve("state.yml")))
    }

    @Test fun `state is bound to server uid and refuses id reuse before any mutation`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); engine.reconcile(config(), false)
        ts.writes.clear(); ts.info["virtualserver_unique_identifier"] = "different"
        assertFailsWith<IllegalArgumentException> { engine.reconcile(config(), false) }; assertTrue(ts.writes.isEmpty())
        ts.info["virtualserver_unique_identifier"] = "fixture"
        val owned = ts.channels.entries.single { it.value["channel_name"] == "Talk" }.key
        ts.channels[owned] = ts.channels.getValue(owned) + ("channel_description" to "Anderer Channel mit wiederverwendeter ID")
        assertFailsWith<IllegalArgumentException> { engine.reconcile(config(), false) }; assertTrue(ts.writes.isEmpty())
    }

    @Test fun `renamed or protected groups are never silently adopted`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); engine.reconcile(config(), false)
        val id = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        ts.serverGroups[id] = ts.serverGroups.getValue(id) + ("name" to "Fremd")
        ts.writes.clear(); assertFailsWith<IllegalArgumentException> { engine.reconcile(config(), false) }; assertTrue(ts.writes.isEmpty())
    }

    @Test fun `name collisions and unsupported permissions fail before server name changes`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory)
        assertFailsWith<IllegalArgumentException> { engine.reconcile(config().copy(channels = listOf(ChannelSpec("lobby", "Lobby", null, emptyMap(), emptyMap()))), false) }
        assertTrue(ts.writes.isEmpty())
        assertFailsWith<IllegalArgumentException> { engine.reconcile(config().copy(serverGroups = listOf(GroupSpec("bad", "Bad", "Normal", mapOf("b_unknown_future_permission" to 1)))), false) }
        assertTrue(ts.writes.isEmpty())
    }

    @Test fun `missing objects are recreated while template and query groups are never changed`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); engine.reconcile(config(), false)
        val id = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        ts.serverGroups.remove(id); ts.writes.clear(); engine.reconcile(config(), false)
        assertTrue(ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key != id)
        assertTrue(ts.writes.filter { it.name == "servergroupcopy" }.all { it.parameters["tsgid"] == "0" && it.parameters["type"] == "1" })
        assertTrue(ts.writes.filter { it.name == "servergroupaddperm" }.all { it.parameters["sgid"]!!.toInt() > 100 })
    }

    @Test fun `partial apply journals created objects and retries without duplicate groups`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory)
        ts.failOnce = "servergroupaddperm"
        assertFailsWith<QueryException> { engine.reconcile(config(), false) }
        val id = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        val saved = YamlConfiguration.loadConfiguration(directory.resolve("state.yml"))
        assertEquals(id, saved.getInt("server-groups.member.id"))
        engine.reconcile(config(), false)
        assertEquals(id, ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key)
    }

    @Test fun `bundled layout validates and invalid hierarchy is rejected before connecting`() {
        val file = directory.resolve("config.yml")
        javaClass.getResourceAsStream("/config.yml")!!.use { Files.copy(it, file) }
        val yaml = YamlConfiguration.loadConfiguration(file); val bundled = SetupConfig.read(yaml)
        assertEquals(14, bundled.channels.size); assertEquals(4, bundled.serverGroups.size); assertEquals(3, bundled.channelGroups.size)
        yaml.set("channels.community.parent", "lounge")
        assertFailsWith<IllegalArgumentException> { SetupConfig.read(yaml) }
        yaml.set("channels.community.parent", null); yaml.set("channels.duo.properties.channel_maxclients", -1)
        assertFailsWith<IllegalArgumentException> { SetupConfig.read(yaml) }
    }

    @Test fun `prune false retains removed objects`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); engine.reconcile(config(), false)
        ts.writes.clear(); engine.reconcile(config(false).copy(channels = emptyList(), serverGroups = emptyList(), channelGroups = emptyList()), false)
        assertTrue(ts.writes.isEmpty()); assertEquals(4, ts.channels.size)
    }

    @Test fun `concurrent setup process is rejected before queries or state changes`() {
        FileChannel.open(directory.resolve("setup.lock"), CREATE, WRITE).use { file ->
            file.lock().use {
                val engine = SetupEngine({ error("A locked engine must not query the server") }, directory)
                assertFailsWith<IllegalStateException> { engine.reconcile(config(), false) }
                assertFalse(Files.exists(directory.resolve("state.yml")))
            }
        }
    }

    @Test fun `managed group promoted to default is protected before all mutations`() {
        val ts = Fixture(); val engine = SetupEngine(ts::execute, directory); engine.reconcile(config(), false)
        val id = ts.serverGroups.entries.single { it.value["name"] == "CL | Mitglied" }.key
        ts.info["virtualserver_default_server_group"] = id.toString(); ts.writes.clear()
        assertFailsWith<IllegalArgumentException> { engine.reconcile(config(), false) }
        assertTrue(ts.writes.isEmpty()); assertTrue(id in ts.serverGroups)
    }

    @Test fun `built Paper plugin applies on enable and unregisters command on disable`() {
        val ts = Fixture()
        // The bundled config requests more permissions than the small engine fixture.
        javaClass.getResourceAsStream("/config.yml")!!.use { Files.copy(it, directory.resolve("desired.yml")) }
        val requested = SetupConfig.read(YamlConfiguration.loadConfiguration(directory.resolve("desired.yml")))
        ts.availablePermissions += requested.channels.flatMap { it.permissions.keys } + requested.serverGroups.flatMap { it.permissions.keys } + requested.channelGroups.flatMap { it.permissions.keys }
        val events = EventBus()
        val server = TeamSpeakServer(ConnectionConfig(password = "fixture"), events) {
            object : QueryConnection {
                override var isOpen = true
                override val supportsNotifications = false
                override fun execute(command: QueryCommand): QueryResponse = when (command.name) {
                    "version" -> QueryResponse(listOf(mapOf("version" to "6.0.0-beta14")), QueryError(0, "ok"))
                    "whoami" -> QueryResponse(listOf(mapOf("client_id" to "1", "virtualserver_id" to "1")), QueryError(0, "ok"))
                    else -> ts.execute(command)
                }
                override fun onNotification(listener: (QueryNotification) -> Unit) = AutoCloseable {}
                override fun onDisconnect(listener: (Throwable?) -> Unit) = AutoCloseable {}
                override fun close() { isOpen = false }
            }
        }
        val services = Services(); val commands = CommandService(server, PermissionService(directory.resolve("permissions.json")))
        services.register(EventBus::class.java, events)
        services.register(CommandService::class.java, commands); services.register(TeamSpeakServer::class.java, server)
        val plugins = Files.createDirectory(directory.resolve("plugins"))
        Files.copy(Path.of(System.getProperty("cerberus.setup.jar")), plugins.resolve("setup.jar"))
        server.use {
            server.connect()
            PluginManager(services, directory.resolve("data")).use { manager ->
                manager.load(plugins); manager.enable()
                val deadline = System.nanoTime() + 5_000_000_000L
                var status = ""
                do {
                    commands.console("einrichtung status") { status = it }
                    if (status.startsWith("Abgleich:") && !status.endsWith("Abgleich läuft.")) break
                    Thread.sleep(10)
                } while (System.nanoTime() < deadline)
                assertTrue(status.startsWith("Abgleich:"), status)
                assertTrue(Files.exists(directory.resolve("data/server-setup/state.yml")))
                assertEquals(16, ts.channels.size)
            }
            val replies = mutableListOf<String>(); commands.console("setup status", replies::add)
            assertTrue(replies.single().startsWith("Unknown command"))
        }
    }

    /** Stateful query fixture models order links, template permissions, occupants and assignments. */
    private class Fixture {
        val writes = mutableListOf<QueryCommand>()
        val info = mutableMapOf("virtualserver_unique_identifier" to "fixture", "virtualserver_name" to "Original",
            "virtualserver_default_server_group" to "8", "virtualserver_default_channel_group" to "8", "virtualserver_default_channel_admin_group" to "5")
        val channels = linkedMapOf(1 to mapOf("cid" to "1", "pid" to "0", "channel_name" to "Default", "channel_order" to "0", "channel_description" to "Native", "channel_flag_default" to "1", "total_clients" to "1"),
            2 to mapOf("cid" to "2", "pid" to "0", "channel_name" to "Lobby", "channel_order" to "1", "channel_description" to "Native", "total_clients" to "0"))
        val serverGroups = (1..8).associateWithTo(linkedMapOf()) { id -> mapOf("sgid" to "$id", "name" to listOf("Guest Server Query", "Admin Server Query", "Server Admin", "Normal", "Guest", "Server Admin", "Normal", "Guest")[id - 1], "type" to if (id <= 2) "2" else if (id <= 5) "0" else "1") }
        val channelGroups = (1..8).associateWithTo(linkedMapOf()) { id -> mapOf("cgid" to "$id", "name" to listOf("Channel Admin", "Operator", "Voice", "Guest")[(id - 1) % 4], "type" to if (id <= 4) "0" else "1") }
        val permissions = mutableMapOf<Pair<String, Int>, MutableMap<String, QueryRow>>()
        val assigned = mutableSetOf<Pair<String, Int>>()
        val availablePermissions = mutableSetOf("i_group_sort_id", "i_channel_needed_join_power")
        var failOnce: String? = null
        private var next = 100
        fun execute(command: QueryCommand): QueryResponse {
            val name = command.name; val p = command.parameters
            fun answer(rows: List<QueryRow> = emptyList()) = QueryResponse(rows, QueryError(0, "ok"))
            when (name) {
                "serverinfo" -> return answer(listOf(info.toMap()))
                "channellist" -> return answer(channels.values.toList())
                "channelinfo" -> return answer(listOf(channels.getValue(p.getValue("cid").toInt())))
                "servergrouplist" -> return answer(serverGroups.values.toList())
                "channelgrouplist" -> return answer(channelGroups.values.toList())
                "permissionlist" -> return answer(availablePermissions.map { mapOf("permname" to it) })
            }
            val kind = when { name.startsWith("servergroup") -> "servergroup"; name.startsWith("channelgroup") -> "channelgroup"; else -> "channel" }
            val idField = when(kind) { "servergroup" -> "sgid"; "channelgroup" -> "cgid"; else -> "cid" }
            val id = p[idField]?.toInt() ?: 0; val key = kind to id
            if (name.endsWith("permlist")) return permissions[key]?.values?.takeIf { it.isNotEmpty() }?.let { answer(it.toList()) }
                ?: throw QueryException(QueryError(1281, "database empty result set"))
            if (name.endsWith("clientlist")) return if (key in assigned) answer(listOf(mapOf("cldbid" to "42")))
                else throw QueryException(QueryError(1281, "database empty result set"))
            writes += command
            if (name == failOnce) { failOnce = null; throw QueryException(QueryError(2568, "insufficient client permissions")) }
            when {
                name == "serveredit" -> info.putAll(p)
                name == "channelcreate" -> {
                    val newId = ++next; val parent = p.getValue("cpid")
                    channels.entries.toList().filter { it.value["pid"] == parent && it.value["channel_order"] == "0" }.forEach { (cid, row) -> channels[cid] = row + ("channel_order" to newId.toString()) }
                    channels[newId] = p.filterKeys { it != "cpid" } + mapOf("cid" to "$newId", "pid" to parent, "channel_order" to "0", "total_clients" to "0")
                    return answer(listOf(mapOf("cid" to "$newId")))
                }
                name == "channeledit" -> channels[id] = channels.getValue(id) + p
                name == "channelmove" -> {
                    val old = channels.getValue(id)
                    check(old["pid"] != p["cpid"] || old["channel_order"] != p["order"]) { "TS6 rejects redundant channelmove with error 770" }
                    channels.entries.toList().filter { it.value["pid"] == old["pid"] && it.value["channel_order"] == id.toString() }.forEach { (cid, row) -> channels[cid] = row + ("channel_order" to old.getValue("channel_order")) }
                    channels.entries.toList().filter { it.key != id && it.value["pid"] == p["cpid"] && it.value["channel_order"] == p["order"] }.forEach { (cid, row) -> channels[cid] = row + ("channel_order" to "$id") }
                    channels[id] = old + mapOf("pid" to p.getValue("cpid"), "channel_order" to p.getValue("order"))
                }
                name == "channeldelete" -> { assertEquals("0", p["force"]); channels.remove(id) }
                name.endsWith("copy") || name.endsWith("add") -> {
                    val groups = if (kind == "servergroup") serverGroups else channelGroups
                    val newId = ++next; groups[newId] = mapOf(idField to "$newId", "name" to p.getValue("name"), "type" to p.getValue("type"))
                    return answer(listOf(mapOf(idField to "$newId")))
                }
                name.endsWith("rename") -> {
                    val groups = if (kind == "servergroup") serverGroups else channelGroups
                    groups[id] = groups.getValue(id) + ("name" to p.getValue("name"))
                }
                name.endsWith("addperm") -> permissions.getOrPut(key) { linkedMapOf() }[p.getValue("permsid")] = p + mapOf("permnegated" to "0", "permskip" to "0")
                name.endsWith("delperm") -> permissions[key]?.remove(p.getValue("permsid"))
                name.endsWith("del") -> { assertEquals("0", p["force"]); (if (kind == "servergroup") serverGroups else channelGroups).remove(id) }
                else -> error("Unexpected mutation: $name")
            }
            return answer()
        }
    }
}
