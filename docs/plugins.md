# Plugins schreiben wie bei Paper

Cerberus TS verwendet die vertraute klassische Paper-Struktur: Hauptklasse, `plugin.yml`, Listener, Executors und YAML-Konfiguration.
Die Imports stammen aus `labs.cerberus.ts`. Es gibt keine Bukkit-Abhängigkeit und keine Binärkompatibilität zu Minecraft-Plugins.
Vorbild sind die offiziellen Paper-Dokumentationen zu [Lebenszyklus](https://docs.papermc.io/paper/dev/how-do-plugins-work/),
[plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/) und [Listenern](https://docs.papermc.io/paper/dev/event-listeners/).

## Projektstruktur

```text
src/main/kotlin/example/WelcomePlugin.kt
src/main/resources/plugin.yml
src/main/resources/config.yml
```

Das ausführbare Beispiel befindet sich in `examples/welcome-plugin`. Im Repository genügt `compileOnly(project(":"))`.
Für ein eigenständiges Plugin-Projekt kann die gebaute `build/libs/cerberus-ts-2.0.0-SNAPSHOT.jar` nach `libs/` kopiert werden:

```kotlin
plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies { compileOnly(files("libs/cerberus-ts-2.0.0-SNAPSHOT.jar")) }
```

Cerberus TS selbst bringt Kotlin und die Framework-Abhängigkeiten zur Laufzeit mit. Andere Plugin-Bibliotheken gegebenenfalls schattieren.
Mit `./gradlew build` entsteht das Plugin-JAR; es wird in Cerberus TS’ `plugins/`-Verzeichnis kopiert.

## Hauptklasse und Listener

```kotlin
package example

import labs.cerberus.ts.plugin.JavaPlugin
import labs.cerberus.ts.event.EventHandler
import labs.cerberus.ts.event.Listener
import labs.cerberus.ts.event.StateChanged

class WelcomePlugin : JavaPlugin(), Listener {
    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        getCommand("hello")!!.setExecutor { sender, _, _, args ->
            val greeting = config.getString("greeting", "Hallo")
            sender.sendMessage("$greeting ${args.firstOrNull() ?: "Welt"}!")
            true
        }
        logger.info("Plugin aktiviert")
    }

    @EventHandler
    fun onStateChanged(event: StateChanged) {
        logger.info("Bot-Status: ${event.current}")
    }

    override fun onDisable() {
        logger.info("Plugin deaktiviert")
    }
}
```

`onLoad()` läuft ohne Parameter. Dienste, Metadaten, `dataFolder` und deklarierte Commands sind dann verfügbar;
die Serververbindung wird erst danach hergestellt. Listener und Tasks in `onEnable()` registrieren.
Im Konstruktor noch keine Framework-Zugriffe ausführen.
`onEnable()` läuft einmal nach der ersten erfolgreichen Verbindung. Wiederverbindungen melden `StateChanged`.
Das ausführbare Beispiel begrüßt in `onEnable()` alle bereits verbundenen Benutzer privat mit `hallo <Name>`.
Es fragt die aktuelle Clientliste ab und überspringt Query-Clients. Später beitretende Benutzer werden dadurch nicht automatisch begrüßt.
`onDisable()` läuft auch beim Aufräumen nach teilweise fehlgeschlagenem Laden; eigene Initialisierung kann deshalb unvollständig sein.

`isEnabled`, `description`, `name`, `logger`, `dataFolder` und `server` sind Eigenschaften der Basisklasse.
TeamSpeak-Abfragen laufen über `server.teamSpeak`, weitere Dienste über `getService(SomeService::class.java)`.
`own(AutoCloseable)` weist eigene Ressourcen dem Plugin zu. Registrierte Commands, Listener und Tasks werden automatisch entfernt.

## plugin.yml

```yaml
name: welcome
version: '2.0.0'
main: example.WelcomePlugin
api-version: '2.0'
author: Anna
description: Mein TeamSpeak-Plugin
commands:
  hello:
    description: Begrüßung
    usage: '<command> [name]'
    aliases: [hi]
```

`name`, `version`, `main` sind erforderlich. `api-version` ist Cerberus TS’ API-Version; unterstützt wird `2` bzw. `2.0`.
Plugin-Namen werden für Abhängigkeiten und Datenverzeichnisse ohne Beachtung der Groß-/Kleinschreibung verarbeitet.
Commands und Aliase verwenden Kleinbuchstaben. Zusätzliche Metadaten: `authors`, `website`.

`depend: [BasePlugin]` verlangt ein anderes Plugin. `softdepend: [OptionalPlugin]` lädt vorhandene optionale Plugins zuerst;
`loadbefore: [OtherPlugin]` bevorzugt die umgekehrte Reihenfolge. Harte Zyklen sind Fehler; optionale Kanten, die Zyklen erzeugen würden, werden ignoriert.

Bei einem geschützten Command ergänzen:

```yaml
    permission: welcome.hello
    permission-message: 'Dafür fehlt dir die Berechtigung.'
```

Cerberus TS’ vorhandener Rechte-Dienst überprüft diese Permission auch bei Aliasaufrufen. `sender.hasPermission("welcome.hello")`
ermöglicht weitere Prüfungen im Handler. Es gibt kein Minecraft-`op`; Konsole und explizite Cerberus TS-Rechte sind maßgeblich.
Nicht implementierte Paper-Felder wie `permissions`, `libraries`, `load` oder `provides` werden mit einer Fehlermeldung abgewiesen.
Dadurch können nicht unterstützte Berechtigungsregeln nicht unbemerkt ignoriert werden.

## CommandExecutor

Statt Lambda kann eine eigene Klasse verwendet werden:

```kotlin
class HelloCommand : CommandExecutor {
    override fun onCommand(
        sender: CommandSender, command: Command, label: String, args: Array<String>
    ): Boolean {
        if (args.size != 1) return false
        sender.sendMessage("Hallo ${args[0]}!")
        return true
    }
}
```

Registrierung: `getCommand("hello")!!.setExecutor(HelloCommand())`.
Alternativ `onCommand(...)` direkt in der Hauptklasse überschreiben; diese ist der Standardexecutor.
`false` zeigt die YAML-`usage` an. `<command>` wird durch das konfigurierte Präfix und den tatsächlich verwendeten Alias ersetzt,
z. B. `!hi`. `label` ist der aufgerufene Name, `command.name` der Hauptname.
`args` enthält auch Argumente wie `--loud` und `-12`; Quotes werden aufgelöst.
TeamSpeak-Nutzer sind `ClientCommandSender` mit `clientId` und `uniqueId`, die Konsole ist `ConsoleCommandSender`.

## Prioritäten und abgebrochene Events

```kotlin
@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
fun onEvent(event: MyCancellableEvent) { /* ... */ }
```

Reihenfolge: `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`, `MONITOR`.
Ohne `ignoreCancelled = true` wird der Handler auch bei bereits abgebrochenen Events aufgerufen.
`MONITOR` dient wie bei Paper der Beobachtung. Eigene Events können `Cancellable` implementieren und `cancelled` setzen.
Ein Event lässt sich mit `server.pluginManager.callEvent(event)` veröffentlichen.
Query-Benachrichtigungen beschreiben bereits geschehene Serveraktionen; deren Empfang macht Aktionen nicht nachträglich abbrechbar.

## config.yml und Tasks

```yaml
greeting: Hallo
```

`saveDefaultConfig()` kopiert die gebündelte Datei einmal in das Plugin-Datenverzeichnis unter `data/plugins/<id>/`.
Bestehende Betreiberwerte werden dabei erhalten. `config.getString`, `getInt`, `getBoolean`, `getStringList` und `set`
arbeiten mit Pfaden wie `messages.greeting`. `saveConfig()` speichert, `reloadConfig()` lädt neu;
fehlende Werte fallen auf die gebündelte Konfiguration zurück.

```kotlin
val task = server.scheduler.runTaskTimer(this, Runnable {
    logger.info("Alle fünf Sekunden")
}, 20L, 100L)
task.cancel()
```

`runTask`, `runTaskLater` und `runTaskTimer` verwenden zur vertrauten Schreibweise 20 Ticks pro Sekunde.
Cerberus TS hat keinen Minecraft-Tick-Loop: Tasks laufen auf dem Hintergrund-Scheduler; Events können auf Query- bzw. Verbindungsthreads eintreffen.
Gemeinsam veränderliche Daten entsprechend synchronisieren. Alle zugeordneten Tasks werden beim Deaktivieren abgebrochen.

Die Low-Level-API `CerberusPlugin`/`PluginContext` ist ebenfalls verfügbar. Pro JAR entweder `plugin.yml` oder
`META-INF/cerberus.properties` verwenden. Es gibt keine Abhängigkeit zu `org.bukkit` oder `io.papermc.paper`.
