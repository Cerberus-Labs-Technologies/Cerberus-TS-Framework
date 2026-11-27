package example

import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.event.Listener
import labs.cerberus.ts.event.EventHandler
import labs.cerberus.ts.event.StateChanged
import labs.cerberus.ts.service.RestResponse

class WelcomePlugin : JavaPlugin(), Listener {
    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        getCommand("hello")!!.setExecutor { sender, _, _, args ->
            sender.sendMessage("${config.getString("greeting", "Hallo")} ${args.firstOrNull() ?: "Welt"}!")
            true
        }
        context.route("GET", "/welcome") { RestResponse(body = mapOf("message" to "Hallo!")) }
        if (server.web.isAvailable) {
            server.web.registerPage(labs.cerberus.ts.web.WebPage("settings", "Begrüßung", "Begrüßungstext des Welcome-Plugins bearbeiten.", "index.js", "style.css"),
                mapOf("index.js" to "web/index.js", "style.css" to "web/style.css"))
            server.web.registerController(WelcomeSettingsController(this))
        }
        val clients = server.teamSpeak.clients(refresh = true).filterNot { it.isQueryClient }
        clients.forEach { client ->
            server.teamSpeak.privateMessage(client.id, "hallo ${client.nickname}")
        }
        logger.info("${clients.size} verbundene Benutzer begrüßt.")
    }

    @EventHandler
    fun onStateChanged(event: StateChanged) {
        println("[welcome] ${event.current}")
    }
}

data class WelcomeSettings(val greeting: String)

@labs.cerberus.ts.rest.RestController("/settings")
class WelcomeSettingsController(private val plugin: WelcomePlugin) {
    @labs.cerberus.ts.rest.RequestMapping(labs.cerberus.ts.rest.RequestMethod.GET, "")
    fun read() = WelcomeSettings(plugin.config.getString("greeting", "Hallo")!!)
    @labs.cerberus.ts.rest.RequestMapping(labs.cerberus.ts.rest.RequestMethod.PUT, "")
    @Synchronized fun write(@labs.cerberus.ts.rest.RequestBody settings: WelcomeSettings): WelcomeSettings {
        if (settings.greeting.isBlank() || settings.greeting.length > 100 || settings.greeting.any { it == '\r' || it == '\n' || it == '\u0000' })
            throw labs.cerberus.ts.rest.RestException(400, "Der Begrüßungstext muss 1 bis 100 Zeichen ohne Zeilenumbrüche enthalten.")
        val path = plugin.dataFolder.toPath().resolve("config.yml")
        val updated = labs.cerberus.ts.configuration.YamlConfiguration.loadConfiguration(path)
        updated.set("greeting", settings.greeting); updated.save(path); plugin.reloadConfig()
        return read()
    }
}
