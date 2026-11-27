package labs.cerberus.ts.teamspeak

import labs.cerberus.ts.query.*

data class TeamSpeakUser(val databaseId: Int, val uniqueId: String, val nickname: String, val properties: QueryRow)

/** Resolves online clients separately from persistent server-database users. Reads are always fresh. */
class UserService(private val server: TeamSpeakServer, private val maxResults: Int = 100) {
    init { require(maxResults > 0) }
    fun findByDatabaseId(id: Int): TeamSpeakUser? {
        require(id > 0)
        val rows = rows(command("clientdbinfo", "cldbid" to id))
        check(rows.size <= 1) { "Unexpected multiple database users" }
        return rows.singleOrNull()?.let { TeamSpeakUser(id, it.getValue("client_unique_identifier"), it["client_nickname"].orEmpty(), it.toMap()) }
    }
    fun findByUniqueId(uid: String): List<TeamSpeakUser> {
        require(uid.isNotBlank())
        return search(uid, true).filter { it.uniqueId == uid }
    }
    fun findByNickname(pattern: String): List<TeamSpeakUser> { require(pattern.isNotBlank()); return search(pattern, false) }
    fun fromClient(client: Client): TeamSpeakUser? = client.databaseId?.let(::findByDatabaseId)?.takeIf {
        client.uniqueId == null || it.uniqueId == client.uniqueId
    }
    private fun search(pattern: String, uid: Boolean): List<TeamSpeakUser> {
        val results = rows(QueryCommand("clientdbfind", mapOf("pattern" to pattern), if (uid) setOf("uid") else emptySet()))
        require(results.size <= maxResults) { "Too many users matched; refine the search" }
        return results.map { it.getValue("cldbid").toInt() }.distinct().mapNotNull(::findByDatabaseId)
    }
    private fun rows(command: QueryCommand): List<QueryRow> = try { server.execute(command).checked().rows }
    catch (error: QueryException) { if (error.error.id == 1281) emptyList() else throw error }
}
