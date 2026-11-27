package example

import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.music.MusicService
import labs.cerberus.ts.music.MusicStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Paper-style optional plugin. Network and media work stays off the Query event dispatcher. */
class MusicPlugin : JavaPlugin() {
    private val busy = AtomicBoolean()
    private val ownedBots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("cerberus-music-commands").factory())
    override fun onEnable() {
        val music = getService(MusicService::class.java)
        getCommand("music")!!.setExecutor { sender, _, _, args ->
            if (args.isEmpty()) return@setExecutor false
            if (!busy.compareAndSet(false, true)) {
                sender.sendMessage("Ein Musik-Befehl läuft noch. Bitte kurz warten.")
                return@setExecutor true
            }
            try {
                worker.execute {
                    try {
                        fun argument(index: Int) = args.getOrNull(index) ?: throw IllegalArgumentException("Argument fehlt. Siehe !music help")
                        fun integer(index: Int) = argument(index).toIntOrNull() ?: throw IllegalArgumentException("Zahl erwartet")
                        fun source() = args.drop(2).joinToString(" ").also { require(it.isNotBlank()) { "Musikquelle fehlt" } }
                        when (argument(0).lowercase()) {
                            "help" -> sender.sendMessage("!music create <id> <channel-id> [name] | play/add <id> <Datei/URL> | pause/resume/skip/stop/status/queue/remove <id> | volume <id> <0-100> | move <id> <channel-id> | list")
                            "create" -> {
                                val id = argument(1)
                                val voice = server.teamSpeak
                                val info = voice.serverInfo()
                                val name = args.drop(3).joinToString(" ").ifBlank { "Cerberus Music $id".take(30) }
                                val status = music.create(id, voice.config.host, info.getValue("virtualserver_port").toInt(), integer(2), name,
                                    System.getenv("CERBERUS_VOICE_PASSWORD"))
                                ownedBots += id
                                sender.sendMessage("Musik-Bot $id verbunden (Client ${status.clientId}).")
                            }
                            "list" -> sender.sendMessage(music.list().joinToString("\n", transform = ::describe).ifBlank { "Keine Musik-Bots verbunden." })
                            "play" -> { music.bot(argument(1)).play(source()); sender.sendMessage("Wiedergabe wird geladen.") }
                            "add" -> { music.bot(argument(1)).enqueue(source()); sender.sendMessage("Zur Warteschlange hinzugefügt.") }
                            "pause" -> { music.bot(argument(1)).pause(); sender.sendMessage("Pausiert.") }
                            "resume" -> { music.bot(argument(1)).resume(); sender.sendMessage("Wiedergabe fortgesetzt.") }
                            "skip" -> { music.bot(argument(1)).skip(); sender.sendMessage("Titel übersprungen.") }
                            "stop" -> { music.bot(argument(1)).stop(); sender.sendMessage("Wiedergabe gestoppt und Warteschlange geleert.") }
                            "volume" -> { music.bot(argument(1)).volume(integer(2)); sender.sendMessage("Lautstärke: ${integer(2)} %.") }
                            "move" -> { music.bot(argument(1)).move(integer(2)); sender.sendMessage("Musik-Bot verschoben.") }
                            "status", "queue" -> sender.sendMessage(describe(music.bot(argument(1)).status()))
                            "remove" -> { music.remove(argument(1)); ownedBots -= argument(1); sender.sendMessage("Musik-Bot getrennt.") }
                            else -> sender.sendMessage("Unbekannter Musik-Befehl. Siehe !music help")
                        }
                    } catch (e: Exception) {
                        val detail = if (e is IllegalArgumentException || e is IllegalStateException) e.message else e.javaClass.simpleName
                        runCatching { sender.sendMessage("Musik-Befehl fehlgeschlagen: $detail") }
                    } finally { busy.set(false) }
                }
            } catch (e: Exception) { busy.set(false); throw e }
            true
        }
        logger.info("Musik-Befehle bereit: !music help")
    }
    private fun describe(status: MusicStatus) = buildString {
        append("${status.id}: ${if (status.connected) "verbunden" else "getrennt"}, ${status.volume} %, ")
        append(if (status.paused) "pausiert" else status.current?.let { "spielt $it" } ?: "bereit")
        append("; Warteschlange: ${status.queued.joinToString().ifBlank { "leer" }}")
        status.metadata?.let { info -> append("; Aktuell: ${listOfNotNull(info.artist, info.title).joinToString(" – ")}"); info.station?.let { append(" ($it)") } }
        status.error?.let { append("; Fehler: $it") }
        status.presentationError?.let { append("; Beschreibung/Avatar: $it") }
    }
    override fun onDisable() {
        worker.shutdownNow()
        worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
        ownedBots.forEach { id -> runCatching { getService(MusicService::class.java).remove(id) } }
        ownedBots.clear()
    }
}
