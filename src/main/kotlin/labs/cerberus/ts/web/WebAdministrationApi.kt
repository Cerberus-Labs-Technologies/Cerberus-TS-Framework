package labs.cerberus.ts.web

import labs.cerberus.ts.query.*
import labs.cerberus.ts.rest.*
import labs.cerberus.ts.service.RestService
import labs.cerberus.ts.teamspeak.TeamSpeakServer
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/** Explicit administration operations. Authorization remains bound to bearer method/path scopes and Query rights. */
class WebAdministrationApi(private val server: TeamSpeakServer, rest: RestService) : AutoCloseable {
    private val registration = rest.registerController(Controller())
    private val writes = ReentrantLock()
    data class ChannelInput(val name: String? = null, val topic: String? = null, val description: String? = null,
        val lifetime: String? = null, val maxClients: Int? = null, val codec: Int? = null, val codecQuality: Int? = null,
        val neededTalkPower: Int? = null, val newPassword: String? = null, val parentId: Int = 0, val order: Int = 0,
        val expectedRevision: String? = null)
    data class MoveInput(val parentId: Int, val order: Int = 0)
    data class GroupInput(val name: String, val copyFrom: Int? = null, val expectedName: String? = null)
    data class DeleteInput(val expectedName: String, val force: Boolean = false)
    data class PermissionInput(val value: Int, val negated: Boolean = false, val skip: Boolean = false)
    data class MemberInput(val databaseId: Int, val channelId: Int? = null)
    private data class Kind(val command: String, val id: String)
    private fun groupKind(kind: String) = when (kind) {
        "server" -> Kind("servergroup", "sgid")
        "channel" -> Kind("channelgroup", "cgid")
        else -> throw RestException(404, "Unbekannter Gruppentyp.")
    }
    private fun permissionKind(kind: String) = when (kind) {
        "channel" -> Kind("channel", "cid")
        "server-group" -> groupKind("server")
        "channel-group" -> groupKind("channel")
        else -> throw RestException(404, "Unbekannter Rechtetyp.")
    }
    private fun positive(id: Int) { if (id <= 0) throw RestException(400, "Eine positive ID ist erforderlich.") }
    private fun name(value: String, max: Int) {
        if (value.isBlank() || value.length > max || value.any(Char::isISOControl))
            throw RestException(400, "Name: 1 bis $max Zeichen ohne Steuerzeichen.")
    }
    private fun text(value: String, max: Int, multiline: Boolean = false) {
        if (value.toByteArray(Charsets.UTF_8).size > max || value.any { it.isISOControl() && !(multiline && it in "\n\r\t") })
            throw RestException(400, "Text ist zu lang oder enthält ungültige Steuerzeichen.")
    }
    private fun <T> query(action: () -> T): T {
        if (!server.isConnected) throw RestException(503, "Die TeamSpeak-Verbindung ist unterbrochen.")
        try { return action() }
        catch (e: QueryException) {
            if (e.error.id in 0xA00..0xAFF) throw RestException(403, "Dem Query-Bot fehlen TeamSpeak-Rechte (Code ${e.error.id}).")
            throw RestException(409, "TeamSpeak hat den Vorgang abgelehnt (Code ${e.error.id}: ${e.error.message.take(120)}).")
        } catch (_: QueryDisconnectedException) { throw RestException(503, "Die TeamSpeak-Verbindung ist unterbrochen.") }
    }
    private fun <T> write(action: () -> T): T {
        if (!writes.tryLock()) throw RestException(409, "Eine Verwaltungsaktion läuft noch. Bitte kurz warten.")
        try { return query(action) } finally { server.invalidate(); writes.unlock() }
    }
    private fun run(name: String, params: Map<String, String> = emptyMap(), options: Set<String> = emptySet()) =
        server.execute(QueryCommand(name, params, options, scope = if (name == "permissionlist") QueryScope.INSTANCE else QueryScope.VIRTUAL_SERVER)).checked()
    private fun rows(name: String, params: Map<String, String> = emptyMap(), options: Set<String> = emptySet()): List<QueryRow> =
        try { run(name, params, options).rows } catch (e: QueryException) { if (e.error.id == 1281) emptyList() else throw e }
    private fun channel(id: Int): QueryRow {
        positive(id)
        val found = server.channels(refresh = true).find { it.id == id } ?: throw RestException(404, "Dieser Channel existiert nicht mehr.")
        return run("channelinfo", mapOf("cid" to "$id")).single() + mapOf("pid" to "${found.parentId}", "channel_order" to found.properties["channel_order"].orEmpty())
    }
    private fun group(kind: Kind, id: Int, editable: Boolean = false): QueryRow {
        positive(id)
        val row = run("${kind.command}list").rows.find { it[kind.id] == "$id" }
            ?: throw RestException(404, "Diese Gruppe existiert nicht mehr.")
        if (editable && row["type"] != "1") throw RestException(403, "Vorlagen- und Query-Gruppen sind hier schreibgeschützt.")
        return row
    }
    private fun target(kind: Kind, id: Int, editable: Boolean = false) {
        if (kind.command == "channel") channel(id) else group(kind, id, editable)
    }
    private val channelFields = setOf("channel_name", "channel_topic", "channel_description", "channel_flag_permanent",
        "channel_flag_semi_permanent", "channel_flag_password", "channel_maxclients", "channel_flag_maxclients_unlimited",
        "channel_codec", "channel_codec_quality", "channel_needed_talk_power")
    private fun revision(row: QueryRow) = MessageDigest.getInstance("SHA-256").digest(channelFields.sorted()
        .joinToString("\u0000") { "$it=${row[it].orEmpty()}" }.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun properties(body: ChannelInput): Map<String, String> = buildMap {
        body.name?.let { name(it, 40); put("channel_name", it) }
        body.topic?.let { text(it, 255); put("channel_topic", it) }
        body.description?.let { text(it, 8192, true); put("channel_description", it) }
        body.newPassword?.let { text(it, 128); put("channel_password", it) }
        body.lifetime?.let {
            if (it !in setOf("permanent", "semi", "temporary")) throw RestException(400, "Ungültiger Channel-Typ.")
            put("channel_flag_permanent", if (it == "permanent") "1" else "0")
            put("channel_flag_semi_permanent", if (it == "semi") "1" else "0")
        }
        body.maxClients?.let {
            if (it < -1) throw RestException(400, "Client-Limit muss -1 (unbegrenzt) oder größer sein.")
            put("channel_flag_maxclients_unlimited", if (it == -1) "1" else "0")
            put("channel_maxclients", if (it == -1) "0" else "$it")
        }
        body.codec?.let { if (it !in 0..5) throw RestException(400, "Ungültiger Codec."); put("channel_codec", "$it") }
        body.codecQuality?.let { if (it !in 0..10) throw RestException(400, "Codec-Qualität: 0 bis 10."); put("channel_codec_quality", "$it") }
        body.neededTalkPower?.let { if (it < 0) throw RestException(400, "Talk-Power darf nicht negativ sein."); put("channel_needed_talk_power", "$it") }
    }
    private fun placement(id: Int?, parent: Int, order: Int) {
        if (parent < 0 || order < 0 || (id != null && (parent == id || order == id))) throw RestException(400, "Ungültiger Parent oder Vorgänger.")
        val channels = server.channels(refresh = true).associateBy { it.id }
        if (parent != 0 && parent !in channels) throw RestException(400, "Der Parent-Channel existiert nicht.")
        if (order != 0 && channels[order]?.parentId != parent) throw RestException(400, "Der Vorgänger muss im selben Parent liegen.")
        val visited = mutableSetOf<Int>(); var next = parent
        while (next != 0) {
            if (next == id || !visited.add(next)) throw RestException(400, "Ein Channel darf nicht unter seinen eigenen Unterchannels liegen.")
            next = channels[next]?.parentId ?: 0
        }
    }
    @RestController("/api/web")
    inner class Controller {
        @RequestMapping(RequestMethod.GET, "/channels/{id}")
        fun channelInfo(@PathParam("id") id: Int) = query {
            val row = channel(id)
            mapOf("id" to id, "parentId" to row["pid"]?.toIntOrNull(), "order" to row["channel_order"]?.toIntOrNull(), "name" to row["channel_name"].orEmpty(), "topic" to row["channel_topic"].orEmpty(),
                "description" to row["channel_description"].orEmpty(), "hasPassword" to (row["channel_flag_password"] == "1"),
                "lifetime" to if (row["channel_flag_permanent"] == "1") "permanent" else if (row["channel_flag_semi_permanent"] == "1") "semi" else "temporary",
                "maxClients" to if (row["channel_flag_maxclients_unlimited"] == "1") -1 else row["channel_maxclients"]?.toIntOrNull(),
                "codec" to row["channel_codec"]?.toIntOrNull(), "codecQuality" to row["channel_codec_quality"]?.toIntOrNull(),
                "neededTalkPower" to row["channel_needed_talk_power"]?.toIntOrNull(), "revision" to revision(row))
        }
        @RequestMapping(RequestMethod.POST, "/channels")
        fun createChannel(@RequestBody body: ChannelInput) = write {
            if (body.name == null) throw RestException(400, "Channel-Name fehlt.")
            val props = properties(body)
            placement(null, body.parentId, body.order)
            val result = run("channelcreate", mapOf("channel_flag_permanent" to "1", "cpid" to "${body.parentId}", "channel_order" to "${body.order}") + props).single()
            mapOf("id" to result.getValue("cid").toInt())
        }
        @RequestMapping(RequestMethod.PUT, "/channels/{id}")
        fun updateChannel(@PathParam("id") id: Int, @RequestBody body: ChannelInput) = write {
            val props = properties(body)
            if (props.isEmpty()) throw RestException(400, "Keine Channel-Änderung angegeben.")
            val current = channel(id)
            if (body.expectedRevision != null && body.expectedRevision != revision(current)) throw RestException(409, "Der Channel wurde inzwischen verändert. Details neu laden und Änderungen prüfen.")
            run("channeledit", mapOf("cid" to "$id") + props)
            mapOf("id" to id)
        }
        @RequestMapping(RequestMethod.POST, "/channels/{id}/move")
        fun moveChannel(@PathParam("id") id: Int, @RequestBody body: MoveInput) = write {
            channel(id); placement(id, body.parentId, body.order)
            run("channelmove", mapOf("cid" to "$id", "cpid" to "${body.parentId}", "order" to "${body.order}")); Unit
        }
        @RequestMapping(RequestMethod.DELETE, "/channels/{id}")
        fun deleteChannel(@PathParam("id") id: Int, @RequestBody body: DeleteInput) = write {
            if (body.force) throw RestException(400, "Channels werden nur ohne Zwang gelöscht.")
            if (channel(id)["channel_name"] != body.expectedName) throw RestException(409, "Der Channel-Name hat sich verändert. Details neu laden.")
            if (server.channels(refresh = true).any { it.parentId == id }) throw RestException(409, "Der Channel hat noch Unterchannels.")
            if (server.clients(refresh = true).any { it.channelId == id }) throw RestException(409, "Der Channel ist noch belegt.")
            run("channeldelete", mapOf("cid" to "$id", "force" to "0")); Unit
        }
        @RequestMapping(RequestMethod.GET, "/groups/{kind}")
        fun groups(@PathParam("kind") kind: String) = query {
            val type = groupKind(kind)
            run("${type.command}list").rows.map { mapOf("id" to it.getValue(type.id).toInt(), "name" to it["name"].orEmpty(), "type" to it["type"]?.toIntOrNull()) }
        }
        @RequestMapping(RequestMethod.POST, "/groups/{kind}")
        fun createGroup(@PathParam("kind") kind: String, @RequestBody body: GroupInput) = write {
            val type = groupKind(kind); name(body.name, 30)
            val result = if (body.copyFrom == null) run("${type.command}add", mapOf("name" to body.name, "type" to "1")) else {
                val source = group(type, body.copyFrom)
                if (source["type"] !in setOf("0", "1")) throw RestException(400, "Query-Gruppen können hier nicht kopiert werden.")
                run("${type.command}copy", mapOf("s${type.id}" to "${body.copyFrom}", "t${type.id}" to "0", "name" to body.name, "type" to "1"))
            }
            mapOf("id" to result.single().getValue(type.id).toInt())
        }
        @RequestMapping(RequestMethod.PUT, "/groups/{kind}/{id}")
        fun renameGroup(@PathParam("kind") kind: String, @PathParam("id") id: Int, @RequestBody body: GroupInput) = write {
            val type = groupKind(kind); name(body.name, 30)
            val current = group(type, id, true)
            if (body.expectedName != null && current["name"] != body.expectedName) throw RestException(409, "Die Gruppe wurde inzwischen umbenannt. Details neu laden.")
            run("${type.command}rename", mapOf(type.id to "$id", "name" to body.name)); Unit
        }
        @RequestMapping(RequestMethod.DELETE, "/groups/{kind}/{id}")
        fun deleteGroup(@PathParam("kind") kind: String, @PathParam("id") id: Int, @RequestBody body: DeleteInput) = write {
            val type = groupKind(kind)
            if (group(type, id, true)["name"] != body.expectedName) throw RestException(409, "Die Gruppe wurde inzwischen umbenannt. Details neu laden.")
            run("${type.command}del", mapOf(type.id to "$id", "force" to if (body.force) "1" else "0")); Unit
        }
        @RequestMapping(RequestMethod.GET, "/permission-catalog")
        fun permissionCatalog() = query { run("permissionlist").rows.map {
            mapOf("id" to it.getValue("permid").toInt(), "name" to it["permname"].orEmpty(), "description" to it["permdesc"].orEmpty())
        } }
        @RequestMapping(RequestMethod.GET, "/permissions/{kind}/{id}")
        fun permissions(@PathParam("kind") kind: String, @PathParam("id") id: Int) = query {
            val type = permissionKind(kind); target(type, id)
            rows("${type.command}permlist", mapOf(type.id to "$id")).map { mapOf("id" to it.getValue("permid").toInt(),
                "value" to it.getValue("permvalue").toInt(), "negated" to (it["permnegated"] == "1"), "skip" to (it["permskip"] == "1")) }
        }
        @RequestMapping(RequestMethod.PUT, "/permissions/{kind}/{id}/{permissionId}")
        fun setPermission(@PathParam("kind") kind: String, @PathParam("id") id: Int, @PathParam("permissionId") permissionId: Int, @RequestBody body: PermissionInput) = write {
            val type = permissionKind(kind); positive(permissionId); target(type, id, true)
            if (type.command != "servergroup" && (body.negated || body.skip)) throw RestException(400, "Negated/Skip gibt es nur für Servergruppen.")
            val params = mutableMapOf(type.id to "$id", "permid" to "$permissionId", "permvalue" to "${body.value}")
            if (type.command == "servergroup") { params["permnegated"] = if (body.negated) "1" else "0"; params["permskip"] = if (body.skip) "1" else "0" }
            run("${type.command}addperm", params); Unit
        }
        @RequestMapping(RequestMethod.DELETE, "/permissions/{kind}/{id}/{permissionId}")
        fun deletePermission(@PathParam("kind") kind: String, @PathParam("id") id: Int, @PathParam("permissionId") permissionId: Int) = write {
            val type = permissionKind(kind); positive(permissionId); target(type, id, true)
            run("${type.command}delperm", mapOf(type.id to "$id", "permid" to "$permissionId")); Unit
        }
        @RequestMapping(RequestMethod.GET, "/groups/{kind}/{id}/members")
        fun members(@PathParam("kind") kind: String, @PathParam("id") id: Int) = query {
            val type = groupKind(kind); group(type, id)
            rows("${type.command}clientlist", mapOf(type.id to "$id"), if (kind == "server") setOf("names") else emptySet()).map {
                mapOf("databaseId" to it.getValue("cldbid").toInt(), "nickname" to it["client_nickname"], "channelId" to it["cid"]?.toIntOrNull())
            }
        }
        @RequestMapping(RequestMethod.POST, "/groups/{kind}/{id}/members")
        fun addMember(@PathParam("kind") kind: String, @PathParam("id") id: Int, @RequestBody body: MemberInput) = write {
            val type = groupKind(kind); positive(body.databaseId); group(type, id, true)
            if (kind == "server") run("servergroupaddclient", mapOf("sgid" to "$id", "cldbid" to "${body.databaseId}")) else {
                val cid = body.channelId ?: throw RestException(400, "Channel fehlt."); channel(cid)
                run("setclientchannelgroup", mapOf("cgid" to "$id", "cldbid" to "${body.databaseId}", "cid" to "$cid"))
            }; Unit
        }
        @RequestMapping(RequestMethod.DELETE, "/groups/server/{id}/members/{databaseId}")
        fun removeMember(@PathParam("id") id: Int, @PathParam("databaseId") databaseId: Int) = write {
            positive(databaseId); group(groupKind("server"), id, true)
            run("servergroupdelclient", mapOf("sgid" to "$id", "cldbid" to "$databaseId")); Unit
        }
        @RequestMapping(RequestMethod.DELETE, "/groups/channel/{id}/members/{databaseId}/{channelId}")
        fun resetChannelMember(@PathParam("id") id: Int, @PathParam("databaseId") databaseId: Int, @PathParam("channelId") channelId: Int) = write {
            positive(databaseId); channel(channelId); group(groupKind("channel"), id, true)
            val current = rows("channelgroupclientlist", mapOf("cldbid" to "$databaseId", "cid" to "$channelId"))
            if (current.none { it["cgid"] == "$id" }) throw RestException(409, "Die Channelgruppen-Zuweisung hat sich inzwischen verändert.")
            val defaultId = run("serverinfo").single()["virtualserver_default_channel_group"]?.toIntOrNull()
                ?.takeIf { it > 0 } ?: throw RestException(502, "Die Standard-Channelgruppe konnte nicht ermittelt werden.")
            run("setclientchannelgroup", mapOf("cgid" to "$defaultId", "cldbid" to "$databaseId", "cid" to "$channelId")); Unit
        }
    }
    override fun close() = registration.close()
}
