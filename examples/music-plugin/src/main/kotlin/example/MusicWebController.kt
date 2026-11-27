package example

import labs.cerberus.ts.music.MusicService
import labs.cerberus.ts.rest.*

/** The same bearer method/path scopes as other plugin APIs; no arbitrary Query commands. */
@RestController("")
class MusicWebController(private val plugin: MusicPlugin, private val music: MusicService) {
    data class Create(val id: String, val channelId: Int, val nickname: String = "", val channelPassword: String? = null)
    data class Action(val action: String, val source: String? = null, val volume: Int? = null,
        val channelId: Int? = null, val channelPassword: String? = null)
    data class Channel(val id: Int, val parentId: Int, val name: String)

    @RequestMapping(RequestMethod.GET, "/bots") fun bots() = music.list()
    @RequestMapping(RequestMethod.GET, "/channels") fun channels(): List<Channel> {
        val server = plugin.server.teamSpeak
        if (!server.isConnected) throw RestException(503, "Die Query-Verbindung zum TeamSpeak-Server fehlt.")
        return server.channels(refresh = true).map { Channel(it.id, it.parentId, it.name) }
    }
    @RequestMapping(RequestMethod.POST, "/bots") fun create(@RequestBody body: Create) = plugin.webWrite {
        if (music.list().any { it.id == body.id }) throw RestException(409, "Ein Bot mit dieser ID existiert bereits.")
        plugin.createBot(body.id, body.channelId, body.nickname, body.channelPassword)
    }
    @RequestMapping(RequestMethod.DELETE, "/bots/{id}") fun remove(@PathParam("id") id: String) = plugin.webWrite {
        requireBot(id)
        plugin.removeBot(id)
    }
    @RequestMapping(RequestMethod.POST, "/bots/{id}/actions")
    fun action(@PathParam("id") id: String, @RequestBody body: Action) = plugin.webWrite {
        val bot = requireBot(id)
        when (body.action) {
            "play" -> bot.play(body.source ?: throw RestException(400, "Musikquelle fehlt."))
            "enqueue" -> bot.enqueue(body.source ?: throw RestException(400, "Musikquelle fehlt."))
            "pause" -> bot.pause()
            "resume" -> bot.resume()
            "skip" -> bot.skip()
            "stop" -> bot.stop()
            "volume" -> bot.volume(body.volume ?: throw RestException(400, "Lautstärke fehlt."))
            "move" -> bot.move(body.channelId ?: throw RestException(400, "Channel fehlt."), body.channelPassword)
            else -> throw RestException(400, "Unbekannte Musik-Aktion.")
        }
        bot.status()
    }
    private fun requireBot(id: String) = music.list().firstOrNull { it.id == id }?.let { music.bot(id) }
        ?: throw RestException(404, "Dieser Musik-Bot existiert nicht mehr.")
}
