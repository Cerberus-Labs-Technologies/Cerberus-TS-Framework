# Cerberus TS

**Entwicklung und Wartung: Cerberus Labs.**

TeamSpeak-Bot- und Plugin-Framework in Kotlin/JVM. Voraussetzung: JDK 21; zum Bauen des Webinterfaces zusätzlich Node.js 22.15+ und npm.
Der aktive Build enthält ausschließlich Kotlin; der unveränderte Java-Quellstand liegt unter [legacy](legacy/README.md).

Die Query- und Framework-Kernfunktionen sind neu implementiert und automatisiert geprüft.
**Die Migration hat noch keine vollständige Funktionsparität mit Jeak 1:** Alte Plugins und Konfigurationsdateien sind nicht direkt kompatibel. SSH-Anmeldung, Serverabfragen, Beispiel-Plugin und Voice-Audio wurden gegen einen echten TS6-beta14-Server geprüft; weitere Grenzen stehen in [Validierung](docs/validation.md).

## Bauen und starten

```sh
./gradlew clean build :examples:welcome-plugin:build
cp config/cerberus.example.properties config/cerberus.properties
# Serverdaten und verifizierte known_hosts-Datei konfigurieren.
export CERBERUS_PASSWORD='...'
./gradlew run --args='--check-config'
./gradlew run --args='--console'
```

`./gradlew installDist` erstellt `build/install/cerberus-ts` mit Startskripten und Laufzeitabhängigkeiten.
Für den Betrieb im **Pelican-Panel** stehen ein importierbares Egg und ein Container-Image mit Musik-Werkzeugen bereit: [Pelican-Anleitung](docs/pelican.md).

ZIP/TAR: `./gradlew distZip distTar`. `--config <datei>` wählt eine andere Konfiguration.
In der optionalen lokalen Konsole beendet `stop` den Bot; diese Konsole besitzt Administratorrechte.

Jeder Konfigurationsschlüssel lässt sich über `CERBERUS_` + Großbuchstaben mit `_` statt `.` überschreiben,
z. B. `CERBERUS_API_KEY`, `CERBERUS_KNOWN_HOSTS`, `CERBERUS_SERVER_ID`, `CERBERUS_ADMIN_UID`.
Passwort und API-Key gehören nicht in eine Connection-URI. Die Beispielkonfiguration enthält keine Standardzugangsdaten.

## TeamSpeak-Verbindungen

| Transport | TS3 | TS6 | Standardport | Push-Events |
| --- | --- | --- | --- | --- |
| SSH | ja | ja | 10022 | ja |
| Raw TCP | ja, explizit konfigurieren | nicht verfügbar | 10011 | ja |
| WebQuery HTTP | ab Server 3.12 | ja | 10080 | nein |
| WebQuery HTTPS | ab Server 3.12 | ja | 10443 | nein |

SSH ist der Standard. Es nutzt eine Shell ohne PTY und überprüft den Host-Key gegen `known_hosts`.
Bei einem unbekannten oder abweichenden Schlüssel bricht die Verbindung ab. Den Schlüssel über einen vertrauenswürdigen Kanal mit dem Betreiber abgleichen und dann in `known_hosts` eintragen.
HTTPS nutzt den Truststore der JVM; es gibt keinen automatischen Rückfall auf Raw TCP.

Für TS6 SSH serverseitig `TSSERVER_QUERY_SSH_ENABLED=1` setzen.
WebQuery benötigt die entsprechende HTTP-/HTTPS-Konfiguration und einen API-Key mit passenden Rechten.
Siehe [offizielle TS6-Konfiguration](https://github.com/teamspeak/teamspeak6-server/blob/main/CONFIG.md) und [Protokollabgleich](docs/protocols.md).

## Funktionsumfang

- Serialisierte Query-Requests mit Zeitlimits, Rate-Limit, vollständigem Escaping, mehrzeiligen Antworten und separat zugestellten Events.
- Verbindungsüberwachung, Keepalive und erneute Initialisierung nach Verbindungsverlust. `reconnect.attempts=0` deaktiviert Wiederholungen; `-1` erlaubt unbegrenzt viele Versuche.
- Plugin-JARs mit Abhängigkeitsreihenfolge, Service-Injection, Lebenszyklus und automatischer Ressourcenfreigabe.
- Event-Bus mit Prioritäten und abbrechbaren Events; Chat-/Konsolenbefehle mit Quotes, typisierten Parametern/Optionen, optionalen/alternativen Kriterien, eigenen Matchern und Mindest-Rechten. Siehe [Befehle](docs/commands.md).
- Persistente Framework-Rechte, Gruppen, Vererbung und Verknüpfung mit TeamSpeak-Servergruppen; UID-basierte Profile mit Zusammenführen und Eigenschaften.
- Client-/Channel-Abfragen und Cache, Nachrichten, Pokes, Verschieben/Kicken sowie generische Query-API für weitere Server-/Gruppen-/Rechtebefehle.
- JSON-Batchaufrufe und Serverauswahl über ID oder Voice-Port bei WebQuery; separate Byte-Streams für Upload/Download über SSH/Raw.
- Scheduler, Lokalisierung, konfigurierbare Hibernate-/Hikari-PersistenceUnits mit verschachtelten JDBC-/ORM-Transaktionen und Transaktionsannotation; siehe [Datenbanken](docs/database.md).
- Benannte Tasks mit Status und Neuplanung sowie serialisierte/asynchrone Plugin-Scheduler; siehe [Scheduler](docs/scheduler.md).
- Config-/Locale-/Transport-Injection, kategorisierte JSON-/YAML-Dateien, persistente Übersetzungs-Units und UID-Sprachpräferenzen; siehe [Konfiguration und Lokalisierung](docs/configuration.md).
- UID-basierte Benachrichtigungen mit Dringlichkeits-/Lebensdauer-Filtern sowie MailBuilder, MIME-Anhängen und benannten asynchronen SMTP-Transporten mit TLS und automatisch geladenen JSON-/YAML-Units; siehe [SMTP](docs/mail.md).
- Optionaler REST-Server mit Bearer-Tokens, Ablaufzeit und Method-/Pfad-Scopes. Token-Dateien speichern SHA-256-Hashes.
- [Webinterface](docs/web.md) mit echten Botdaten, Token-Anmeldung und durch Plugins ergänzbaren Seiten, Assets und APIs.
- Mehrere Musik-Bots mit eigenen Voice-Identitäten, Dateien, HTTP-/Radio-Streams, YouTube, Queue, Pause und Lautstärke; siehe [Musik-Bots](docs/music.md).
- Optionales [Server-Setup-Plugin](docs/server-setup.md): Community-Aufbau mit Channels, Server-/Channelgruppen, YAML-Abgleich beim Start und geschütztem Entfernen eigener Objekte.

`!help`, `!whoami`, `!whereami` benötigen kein Framework-Recht.
`!grant`, `!group-create`, `!group-link` benötigen `cerberus.permissions.manage`.
Mit `CERBERUS_ADMIN_UID` lässt sich eine vertrauenswürdige UID beim Start als Framework-Administrator einrichten.
Ein TeamSpeak-Serveradmin erhält diese Rechte nicht automatisch.

## Plugins

Das vollständige Beispiel liegt unter [examples/welcome-plugin](examples/welcome-plugin).
Die gebaute Datei `examples/welcome-plugin/build/libs/welcome-plugin.jar` in `plugins/` kopieren und den Bot neu starten.

```kotlin
class HelloPlugin : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        getCommand("hello")!!.setExecutor { sender, _, _, args ->
            sender.sendMessage("Hallo ${args.firstOrNull() ?: "Welt"}!")
            true
        }
    }

    @EventHandler
    fun onStateChanged(event: StateChanged) {
        logger.info("Bot-Status: ${event.current}")
    }
}
```

Wie bei klassischen Paper-Plugins liegt `plugin.yml` direkt im JAR:

```yaml
name: Hello
version: '2.0.0'
main: example.HelloPlugin
api-version: '2.0'
commands:
  hello:
    description: Begrüßung
    usage: '<command> [name]'
    aliases: [hi]
```

Die Plugin-Syntax orientiert sich an Paper: `JavaPlugin`, `onLoad()`, `onEnable()`, `onDisable()`,
`Listener`, `@EventHandler`, `CommandExecutor`, `config.yml` und Scheduler.
Die Klassen kommen aus `labs.cerberus.ts`, nicht aus Bukkit. Es handelt sich um eine eigenständige TeamSpeak-API.
`onEnable` läuft nach der ersten Serververbindung,
`onDisable` beim Herunterfahren bzw. nach teilweise fehlgeschlagenem Laden.
Für Wiederverbindungen auf `StateChanged` reagieren. Commands, Listener und Tasks werden beim Stoppen automatisch entfernt.
Eigene Ressourcen mit `own(...)` zuordnen. Die bisherige `CerberusPlugin`-/`PluginContext`-API bleibt verfügbar.
Plugins laufen mit den Rechten des Bot-Prozesses.
Die vollständige Anleitung mit Projektstruktur und Imports steht in [docs/plugins.md](docs/plugins.md).

## REST

`rest.port` aktiviert den Server, standardmäßig an `127.0.0.1`. Ein vorgeschalteter HTTPS-Proxy kann externen Zugriff übernehmen.
`route()`-Routen einschließlich `/health` benötigen ein Bearer-Token. Explizite öffentliche Routen stellen beispielsweise die Web-Anmeldeseite und ihre Assets bereit; Verwaltungs-APIs bleiben geschützt. Ein Plugin kann ein Token erzeugen:

```kotlin
val token = getService(TokenService::class.java).generate(
    setOf("GET /health", "GET /welcome"), Duration.ofHours(1)
)
```

Den zurückgegebenen Wert sicher an den vorgesehenen Nutzer weitergeben.
Scopes wie `GET /my-plugin/*` gelten für Unterpfade; GET erlaubt kein POST. `revoke(token)` widerruft ein Token.

Details: [Architektur](docs/architecture.md), [Migration und offene Punkte](docs/migration.md), [Prüfungen](docs/validation.md).
Lizenz: [MIT](LICENSE). Historische Herkunft und erhaltene Urheberangaben: [NOTICE](NOTICE).
