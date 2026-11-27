package example

import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.music.MusicService
import labs.cerberus.ts.music.MusicStatus
import java.util.concurrent.*
import labs.cerberus.ts.rest.RestException
import labs.cerberus.ts.web.WebPage
import java.util.concurrent.atomic.AtomicBoolean

/** Paper-style optional plugin. Network and media work stays off the Query event dispatcher. */
class MusicPlugin : JavaPlugin() {
    private val busy = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val ownership = Any()
    private val ownedVideoBots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
                                val status = createBot(id, integer(2), args.drop(3).joinToString(" "))
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
                            "remove" -> { removeBot(argument(1)); sender.sendMessage("Musik-Bot getrennt.") }
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
        if (server.web.isAvailable) {
            server.web.registerController(MusicWebController(this, music))
            server.web.registerPage(WebPage("control", "Musik-Bots", "Musik abspielen, Bots verbinden und Warteschlangen verwalten.", "index.js", "style.css", 50),
                mapOf("index.js" to "web/index.js", "style.css" to "web/style.css", "video.js" to "web/video.js"))
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
    internal fun createBot(id: String, channelId: Int, nickname: String, channelPassword: String? = null): MusicStatus {
        val name = nickname.ifBlank { "Cerberus Music $id".take(30) }
        require(name.length in 3..30 && name.none { it.isISOControl() }) { "Name: 3 bis 30 Zeichen ohne Steuerzeichen" }
        val voice = server.teamSpeak
        if (!voice.isConnected) throw RestException(503, "Die Query-Verbindung zum TeamSpeak-Server fehlt.")
        val status = getService(MusicService::class.java).create(id, voice.config.host,
            voice.serverInfo().getValue("virtualserver_port").toInt(), channelId, name,
            System.getenv("CERBERUS_VOICE_PASSWORD"), channelPassword)
        synchronized(ownership) {
            if (stopping.get()) {
                getService(MusicService::class.java).remove(id)
                throw RestException(503, "Das Musik-Plugin wird beendet.")
            }
            ownedBots += id
        }
        return status
    }
    internal fun createVideoBot(id: String, channelId: Int, nickname: String, channelPassword: String? = null): labs.cerberus.ts.music.VideoStatus {
        val voice = server.teamSpeak
        if (!voice.isConnected) throw RestException(503, "Die Query-Verbindung zum TeamSpeak-Server fehlt.")
        val video = getService(MusicService::class.java).video
        val status = video.create(id, voice.config.host, voice.serverInfo().getValue("virtualserver_port").toInt(), channelId,
            nickname.ifBlank { "Cerberus Video $id".take(30) }, System.getenv("CERBERUS_VOICE_PASSWORD"), channelPassword)
        synchronized(ownership) {
            if (stopping.get()) { video.remove(id); throw RestException(503, "Das Musik-Plugin wird beendet.") }
            ownedVideoBots += id
        }
        return status
    }
    internal fun removeVideoBot(id: String) { getService(MusicService::class.java).video.remove(id); ownedVideoBots -= id }
    internal fun removeBot(id: String) {
        getService(MusicService::class.java).remove(id)
        ownedBots -= id
    }
    /** Web writes share the command worker, so competing operations are rejected rather than queued. */
    internal fun <T> webWrite(action: () -> T): T {
        if (stopping.get()) throw RestException(503, "Das Musik-Plugin wird beendet.")
        if (!busy.compareAndSet(false, true)) throw RestException(409, "Ein Musik-Befehl läuft noch. Bitte kurz warten.")
        val future = try { worker.submit(Callable { try { action() } finally { busy.set(false) } }) }
        catch (_: RejectedExecutionException) { busy.set(false); throw RestException(503, "Das Musik-Plugin wird beendet.") }
        try { return future.get() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw RestException(503, "Anfrage unterbrochen. Status vor erneutem Senden prüfen.") }
        catch (_: CancellationException) { throw RestException(503, "Das Musik-Plugin wird beendet.") }
        catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is RestException -> throw cause
                is IllegalArgumentException -> throw RestException(400, cause.message ?: "Ungültige Musik-Eingabe.")
                is IllegalStateException -> throw RestException(409, cause.message ?: "Musik-Aktion derzeit nicht möglich.")
                else -> { logger.warning("Musik-Webaktion fehlgeschlagen: ${cause?.javaClass?.simpleName}"); throw RestException(502, "Die Musik-Aktion ist fehlgeschlagen. Verbindung und Serverrechte prüfen.") }
            }
        }
    }
    override fun onDisable() {
        stopping.set(true)
        worker.shutdownNow().forEach { (it as? Future<*>)?.cancel(false) }
        try { worker.awaitTermination(15, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val ids = synchronized(ownership) { ownedBots.toList().also { ownedBots.clear() } }
        ids.forEach { id -> runCatching { getService(MusicService::class.java).remove(id) } }
        val videoIds = synchronized(ownership) { ownedVideoBots.toList().also { ownedVideoBots.clear() } }
        videoIds.forEach { id -> runCatching { getService(MusicService::class.java).video.remove(id) } }
    }
}
