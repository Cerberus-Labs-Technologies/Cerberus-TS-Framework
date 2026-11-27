package example.setup

import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.query.QueryException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerSetupPlugin : JavaPlugin() {
    private val busy = AtomicBoolean()
    @Volatile private var status = "Noch kein Abgleich."
    override fun onEnable() {
        saveDefaultConfig()
        val initial = SetupConfig.read(config)
        val worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("cerberus-server-setup").factory())
        own(AutoCloseable { worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS) })
        val engine = SetupEngine(server.teamSpeak::execute, dataFolder.toPath())
        fun submit(dryRun: Boolean, reply: (String) -> Unit) {
            if (!busy.compareAndSet(false, true)) { reply("Ein Serverabgleich läuft bereits."); return }
            reply(if (dryRun) "Serveraufbau wird geprüft …" else "Serveraufbau wird abgeglichen …")
            worker.submit {
                try {
                    reloadConfig()
                    val result = engine.reconcile(SetupConfig.read(config), dryRun)
                    result.changes.forEach { logger.info((if (dryRun) "[Plan] " else "") + it) }
                    result.warnings.forEach { logger.warning(it) }
                    status = "${if (dryRun) "Plan" else "Abgleich"}: ${result.changes.size} Änderungen, ${result.warnings.size} zurückgestellte Löschungen."
                    reply(status)
                    if (dryRun) result.changes.forEach(reply)
                    result.warnings.forEach(reply)
                } catch (e: Exception) {
                    status = if (e is QueryException) "Abgleich fehlgeschlagen: TS-Fehler ${e.error.id} (${e.error.message})." else
                        "Abgleich fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
                    logger.warning(status); reply(status)
                } finally { busy.set(false) }
            }
        }
        getCommand("setup")!!.setExecutor { sender, _, _, args ->
            when (args.singleOrNull()?.lowercase()) {
                "status" -> sender.sendMessage(status + if (busy.get()) " Abgleich läuft." else "")
                "plan" -> submit(true, sender::sendMessage)
                "apply" -> submit(false, sender::sendMessage)
                else -> sender.sendMessage("Verwendung: !setup status|plan|apply")
            }
            true
        }
        if (initial.applyOnStart) submit(false, logger::info)
    }
}
