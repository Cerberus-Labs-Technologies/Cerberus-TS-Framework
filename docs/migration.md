# Migration von Jeak 1

Cerberus TS ist eine neue Kotlin-API. Alte Plugin-JARs müssen angepasst und neu kompiliert werden.
Ein automatischer Import der Confort-Konfiguration und des gesamten Persistenzformats ist nicht enthalten.
Vor der Migration den bisherigen Bot stoppen und Konfiguration, Profile, Rechte und Datenbanken sichern.

| Jeak 1 | Cerberus TS |
| --- | --- |
| `@JeakBotPlugin`, ClassGraph | `JavaPlugin`, `plugin.yml` |
| `IBot`, ServiceManager | `CerberusBot`, `PluginContext`, `Services` |
| `@Inject` | `@field:Inject` auf mutable Felder oder `context.service<T>()` |
| Lifecycle-Listener | `onLoad`, `onEnable`, `onDisable`, `StateChanged` |
| Listener-Registrierungen | `Listener`, `@EventHandler`, `server.pluginManager.registerEvents` |
| QueryBuilder, Promises | `QueryCommand`, `command`, `server.execute` mit Zeitlimit |
| Textprotokoll für alle Transporte | Wire-Escaping für Streams, URL-/JSON-Encoding für WebQuery |
| ANTLR, Matcher-Graph | Kotlin-Tokenizer, unveränderliche Command-Specs mit optionalen/alternativen Kriterien, typisierte Optionen, eigene Matcher und automatische Plugin-Freigabe |
| TaskBuilder | `Task.builder()`, `TaskService` mit Status/Neuplanung, `context.task(task)` und Paper-artige synchrone/asynchrone Scheduler-Methoden |
| Confort-Dateien | Properties/Umgebungsvariablen für den Bot; `config.yml`/`saveDefaultConfig` für Plugins |
| Hibernate-/Hikari-PersistenceUnits | Konfigurierte Hibernate-/Hikari-Units, `DatabaseService.withEntityManager` und `.transaction`; Entities auf `jakarta.persistence` umstellen |
| TeamSpeak-Datenbankzugriff | Query-Abfragen (`clientdbinfo`, `permoverview` usw.) |
| Annotierte Spark-Controller | `@RestController`, `@RequestMapping`, typisierte Parameter/JSON und `ResponseEntity`; alternativ `context.route` |
| Klartext-REST-Tokens | Neue gehashte Tokens mit Methoden-/Pfad-Scopes und Ablaufzeit |
| LocaleUnits | `LocalizationService.registerUnit`, `@LocaleUnit`, persistente `langs`-Vorlagen/Kontexte, UID-Präferenzen; globale UTF-8-Properties/Maps bleiben verfügbar |
| TransportUnit/MailBuilder | `@TransportUnit`, `MailMessage.builder()`, MIME-Anhänge, CC/BCC, Header und benannte `MailTransports`; alte SMTP-JSON-Dateien aus `conf/mail` nach `<data.directory>/mail` kopieren, automatische Registrierung vor Plugin-Injection; siehe [SMTP](mail.md) |
| TS3J/MP3 | `Ts3jVoiceProvider`, `MusicService`, FFmpeg/Concentus und optionales Paper-artiges Musik-Plugin |

## Plugin-Beispiel

```kotlin
class ModerationPlugin : JavaPlugin() {
    override fun onEnable() {
        getCommand("move")!!.setExecutor { sender, _, _, args ->
            if (args.size != 2) false else {
                server.teamSpeak.move(args[0].toInt(), args[1].toInt())
                sender.sendMessage("Client verschoben.")
                true
            }
        }
    }
}
```

Die zugehörigen Klassen importieren und `move` in `plugin.yml` mit `permission: moderation.move` und passender `usage` deklarieren.
`depend` enthält eine YAML-Liste verpflichtender Plugin-Namen. Alle müssen vorhanden und azyklisch sein;
`softdepend` und `loadbefore` steuern optionale Reihenfolgen. `api-version: '2.0'` bezieht sich auf Cerberus TS.
Versionsbereiche zwischen Plugins sind nicht implementiert.
Fremdbibliotheken sollten im Plugin-JAR enthalten sein. Der gemeinsame Plugin-ClassLoader bietet keine Isolation;
Plugins können bei inkompatiblen Bibliotheksversionen kollidieren.

Die Low-Level-API ist als `CerberusPlugin.onLoad(context)` mit `META-INF/cerberus.properties` verfügbar.
Pro JAR genau ein Metadatenformat verwenden. Der frühere Annotationstyp `@Listener` heißt jetzt `@Subscribe`;
`Listener` ist wie bei Paper das Marker-Interface für Klassen mit `@EventHandler`-Methoden.
Die vollständige neue Syntax steht in [plugins.md](plugins.md).

## Umbenennung zu Cerberus TS

Cerberus Labs entwickelt und wartet das aktuelle Framework. Gegenüber dem früheren Kotlin-Zwischenstand ändern sich:

- Package-Präfix: `labs.cerberus.ts`; Plugins müssen mit diesen Imports neu kompiliert werden.
- Maven-Koordinaten: `labs.cerberus:cerberus-ts:2.0.0-SNAPSHOT`.
- Einstiegspunkt: `CerberusBot`; die Paper-artige Plugin-Basisklasse heißt weiterhin `JavaPlugin`.
- Konfiguration: `config/cerberus.properties`; Umgebungsvariablen beginnen mit `CERBERUS_`.
- Administrationsrecht: `cerberus.permissions.manage`.
- Low-Level-Metadaten: `META-INF/cerberus.properties`; das bevorzugte Format bleibt `plugin.yml`.

Bestehende Konfigurationswerte in die neue Datei übernehmen, Umgebungsvariablen und explizite Administrationsrechte entsprechend umbenennen.
Für Profile, Tokens und sonstige Daten kann dasselbe Datenverzeichnis weiterverwendet werden; ihre Speicherformate wurden durch die Namensänderung nicht verändert.

Die lokale Konsole kann `grant <UID> moderation.move 1` ausführen.
Für eine Gruppe: `group-create moderators`, `group-link moderators <TS-Servergruppen-ID>`;
Gruppenrechte über `PermissionService.grantGroup` setzen.

## Offene Arbeit und bewusste Grenzen

- Der Musikdienst ersetzt den alten MP3-Stack mit eigenständigen Voice-Verbindungen und persistenten Identitäten.
  Alte Voice-Konfigurationen werden nicht automatisch übernommen; Audiomixing mehrerer Titel innerhalb eines Bots ist nicht enthalten.
  Einrichtung und Befehle stehen in [music.md](music.md).
- Kein automatisches Konvertieren alter Profile/Rechte; Identitäten explizit übernehmen.
- Keine Binärkompatibilität oder Hot-Reload-Funktion. Der neue Matcher-Kern ist implementiert; vollständige alte Daten-/Rechte-Typverträge bleiben Teil der offenen Migration. Moderne JPA-/Hibernate-Entities und annotierte REST-Controller sind enthalten; alte `javax.persistence`-Entities benötigen eine Migration. Siehe [Datenbanken](database.md) und [Befehle](commands.md).
- Avatar-Dateitransfers wurden gegen TS6 geprüft; allgemeine Channel-Dateien noch nicht. SMTP wurde mit einem lokalen Testserver geprüft, noch nicht gegen einen externen Mailserver.
- WebQuery ermöglicht Query-Aufrufe und REST-/Konsolenbefehle. Chatbefehle/Echtzeit-Events benötigen SSH.
- Serverrechte und API-Key-Scopes bleiben erforderlich.
- Native TLS-Query-URIs aus Jeak 1 sind kein TS6-Standardtransport. Für verschlüsselte Stream-Sitzungen SSH verwenden.

Der alte Quellstand bleibt unter `legacy` als Nachschlagewerk, wird aber nicht gebaut oder ausgeliefert.

Die erweiterten Verträge für [Konfiguration und Lokalisierung](configuration.md) sowie [Tasks](scheduler.md) dokumentieren die neue API einschließlich korrigierter Fehler im Altstand.
