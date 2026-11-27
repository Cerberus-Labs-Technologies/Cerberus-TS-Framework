package labs.cerberus.ts.web

import labs.cerberus.ts.CerberusBot
import labs.cerberus.ts.plugin.PluginDescriptor
import labs.cerberus.ts.plugin.ResourceScope
import labs.cerberus.ts.rest.*
import labs.cerberus.ts.service.RestService

/** Shared administration data. Plugin-specific actions use each plugin's own /api/plugins namespace. */
class WebControlApi(private val bot: CerberusBot, rest: RestService, private val plugins: () -> List<PluginDescriptor>) : AutoCloseable {
    private val scope = ResourceScope()
    init { scope.own(rest.registerController(Controller())) }
    @RestController("/api/web")
    inner class Controller {
        @RequestMapping(RequestMethod.GET, "/status")
        fun status(): Map<String, Any?> {
            val connected = bot.server.isConnected
            val info = if (connected) bot.server.serverInfo() else emptyMap()
            return mapOf("state" to bot.state.name, "connected" to connected, "serverId" to bot.server.selectedServerId,
                "serverName" to info["virtualserver_name"], "version" to bot.server.version["version"],
                "maxClients" to info["virtualserver_maxclients"]?.toIntOrNull(),
                "onlineClients" to info["virtualserver_clientsonline"]?.toIntOrNull(), "queryClients" to info["virtualserver_queryclientsonline"]?.toIntOrNull())
        }
        @RequestMapping(RequestMethod.GET, "/clients")
        fun clients() = connected { bot.server.clients(refresh = true).filterNot { it.isQueryClient }.map {
            mapOf("id" to it.id, "channelId" to it.channelId, "databaseId" to it.databaseId, "uniqueId" to it.uniqueId,
                "nickname" to it.nickname, "serverGroups" to it.properties["client_servergroups"].orEmpty().split(',').mapNotNull(String::toIntOrNull))
        } }
        @RequestMapping(RequestMethod.GET, "/channels")
        fun channels() = connected { bot.server.channels(refresh = true).map {
            mapOf("id" to it.id, "parentId" to it.parentId, "name" to it.name, "order" to it.properties["channel_order"]?.toIntOrNull(),
                "clients" to it.properties["total_clients"]?.toIntOrNull())
        } }
        @RequestMapping(RequestMethod.GET, "/music") fun music() = bot.music.list()
        @RequestMapping(RequestMethod.GET, "/plugins") fun plugins() = this@WebControlApi.plugins.invoke().map {
            mapOf("id" to it.id, "name" to it.name, "version" to it.version, "description" to it.description, "authors" to it.authors)
        }
        private fun <T> connected(action: () -> T): T {
            if (!bot.server.isConnected) throw RestException(503, "TeamSpeak-Verbindung ist unterbrochen")
            return action()
        }
    }
    override fun close() = scope.close()
}
