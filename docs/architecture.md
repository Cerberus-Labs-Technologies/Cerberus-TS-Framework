# Architektur und Analyse des Altstands

Ausgangspunkt: Branch `bleeding-1.X.X`, Commit `bede7f4f575d828e67fa54c4f65b338c70c73443` aus `jeakfrw/jeak-framework`.
Der Altstand umfasst rund 32.000 Zeilen über Java-API, Implementierung, Tests und Ressourcen.

## Bisheriger Ablauf

`Main` erstellte Bot-Instanzen. `JeakBot` entdeckte annotierte Plugin-Klassen über ClassGraph,
erzeugte Dienste, injizierte Felder und verteilte Initialisierungsereignisse.
`TS3ConnectionTask` verwaltete Verbindung und Wiederholungen. `Server` wählte den virtuellen Server und registrierte Events.
`TSQueryConnection`/`SerialMessageChannel` verwalteten Requests und Byte-Streams;
`QueryParser` baute Antworten und Benachrichtigungen auf.

Darauf bauten Client-/Channel-Caches, User-/Profil-Dienste, Rechteprovider, Befehle,
Lokalisierung, Scheduler, REST, Mail und Benachrichtigungen auf.
Der separat aktivierbare Voice-Dienst nutzte TS3J, native Opus-Anbindung und FFmpeg.

Konkrete Altlasten:

- Gradle 6.7.1, entfernte `compile`-/`runtime`-Konfigurationen und zusätzlicher privater Maven-Host.
- JSch 0.1.55 statt gepflegtem Fork mit aktuellen SSH-Algorithmen.
- Port 443 als Fallback für mehrere Query-Transporte.
- Vorhandene HTTP/HTTPS-Schemata endeten in `not yet supported`.
- Raw-Login erfolgte nach der Serverauswahl.
- Vertikaler Tab im Encoder fälschlich U+2B7F; Bell fehlte; Decoder konnte über das Arrayende lesen.
- Experimentelle Voice-Abhängigkeiten mit alten Git-Versionen und nativen Plattformbestandteilen.

## Neue Zuständigkeiten

| Paket | Aufgabe |
| --- | --- |
| `query` | Konfiguration, Escaping, Request-/Antwortmodell, SSH, TS3 Raw, WebQuery |
| `teamspeak` | Sitzungsinitialisierung, UID-Abfragen, Snapshots, Dateitransfers |
| `event` | Geordnete Listener, Abbruch, Query-/Text-/Statusereignisse |
| `plugin` | `JavaPlugin`/`plugin.yml`, Paper-artiger Lebenszyklus, Abhängigkeiten, Ressourcen |
| `configuration` | Sichere YAML-Metadaten und `config.yml` mit gebündelten Standardwerten |
| `command` | Parser, Argumente/Optionen, Autorisierung, Antworten |
| `service` | Scheduler, Rechte/Profile/Tokens, REST, JDBC, SMTP, Lokalisierung |
| `voice` | Voice-Vertrag, TS3J-Adapter mit TS6-Handshake, begrenzter Audiopuffer und Paket-AEAD |
| `music` | Mehrere Voice-Bots, persistente Identitäten, Warteschlangen, FFmpeg, YouTube-Auflösung und Opus-Encoding |
| `CerberusBot` | Zusammensetzung, Wiederverbindung, Herunterfahren |

## Nebenläufigkeit

Das Stream-Protokoll kennt keine Request-ID. Ein fairer Lock erlaubt einen laufenden Befehl pro Verbindung.
Ein eigener Reader vervollständigt Antworten an `error id=...`; Notifications gehen an einen separaten begrenzten Executor.
Dadurch darf ein Event-Handler selbst Query-Aufrufe ausführen. Ein Überlauf der Event-Warteschlange beendet die Sitzung,
statt Ereignisse still zu verlieren. Ein Request-Timeout verwirft die Sitzung, damit verspätete Antworten nicht dem falschen Request zugeordnet werden.

Listener laufen sequenziell pro Verbindung. `@EventHandler` verwendet Paper-artige Prioritäten und Abbruchsemantik;
`Listener` ist ein Marker-Interface. Die Low-Level-Annotation heißt `@Subscribe`.
Lange Arbeiten als Plugin-Task ausführen.
Ressourcen werden in umgekehrter Registrierungsreihenfolge freigegeben.
Mehrere Bot-Instanzen benötigen getrennte Datenverzeichnisse.

## Daten und Rechte

JSON-Schreibvorgänge verwenden eine temporäre Datei, `force(true)` und atomaren Dateiaustausch, wo unterstützt.
Syntaxfehler und leere vorhandene Dateien werden beim Laden abgewiesen. Ein Datenverzeichnis ist für einen Bot-Prozess vorgesehen.

Framework-Rechte sind unabhängig von TeamSpeaks eigener Rechteprüfung.
Spezifische direkte Benutzerregeln haben Vorrang; danach folgen Gruppen und deren Eltern.
Innerhalb eines Subjekts gilt die spezifischste Regel (`a.b`, dann `a.*`, dann `*`).
Bei mehreren geerbten Werten hat eine Ablehnung (`<= 0`) Vorrang, sonst der höchste Wert.
Zyklen werden abgewiesen; Servergruppen werden bei Befehlen frisch über `clientinfo` ermittelt.
Fehlende Rechte erlauben nichts.

REST-Tokens entstehen mit `SecureRandom` und werden nur gehasht gespeichert.
SSH prüft bekannte Host-Keys, HTTPS den JVM-Truststore. Diagnoseausgaben enthalten keine Query-Werte oder Credentials.
