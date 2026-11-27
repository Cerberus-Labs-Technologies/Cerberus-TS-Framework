package labs.cerberus.ts

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        println("Cerberus TS — Cerberus Labs\nUsage: cerberus-ts [--config <file>] [--check-config] [--console]\nSecrets: CERBERUS_PASSWORD, CERBERUS_API_KEY. Default config: config/cerberus.properties")
        return
    }
    val configIndex = args.indexOf("--config")
    val file = if (configIndex >= 0) args.getOrNull(configIndex + 1) ?: error("--config requires a file") else "config/cerberus.properties"
    val config = try { BotConfig.load(Path.of(file)) } catch (e: Exception) {
        System.err.println("Invalid Cerberus TS configuration: ${e.javaClass.simpleName}. See config/cerberus.example.properties.")
        exitProcess(2)
    }
    if ("--check-config" in args) { println("Configuration valid: ${config.connection}"); return }
    val bot = CerberusBot(config)
    val lifecycle = bot.events.subscribe<labs.cerberus.ts.event.StateChanged> {
        println("[cerberus] ${it.current}")
    }
    val shutdown = Thread(bot::close, "cerberus-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        bot.start()
        if ("--console" in args) Thread.ofPlatform().daemon().name("cerberus-console").start {
            generateSequence(::readlnOrNull).forEach { input ->
                if (input == "stop") bot.close() else bot.commands.console(input, ::println)
            }
        }
        bot.awaitShutdown()
    } catch (e: Exception) {
        System.err.println("Cerberus TS failed: ${e.javaClass.simpleName}")
        bot.close()
        exitProcess(1)
    } finally {
        lifecycle.close()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
    if (bot.failed) exitProcess(1)
}
