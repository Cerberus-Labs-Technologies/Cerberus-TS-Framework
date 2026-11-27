# Validierung

Die Kotlin-Fassung wurde lokal mit JDK 21, Kotlin 2.4.21 und Gradle 9.9.2 gebaut.
Letzter vollständiger Lauf einschließlich Server-Setup-Plugin: **158 Tests erfasst, 157 bestanden, 1 explizit deaktivierter Live-Test, 0 Fehler**.
Der Build enthält inzwischen auch das React-/shadcn-Webinterface und benötigt zusätzlich Node.js 22.15+ und npm.
Der Live-Audiotest wurde zusätzlich separat erfolgreich mit aktivierten Zielserver-Variablen ausgeführt.
Der zusätzliche Distributionstest
bestand Start, Query-Login, Beispiel-Plugin-Befehl und sauberes Herunterfahren.

```sh
./gradlew clean build :examples:welcome-plugin:build :examples:music-plugin:build :examples:server-setup-plugin:build installDist
python3 tools/check_ts6_reference.py --archive /pfad/zum/offiziellen/teamspeak6-server-linux-arm64.tar.xz
python3 tools/smoke_distribution.py
```

Der erste Befehl kompiliert Anwendung und Beispiel-Plugin, führt die Tests aus und baut ZIP-/TAR-/Startskript-Distributionen.
Der Referenzcheck verifiziert die SHA-256-Prüfsumme des offiziellen TS6-beta14-Archivs und gleicht 40 Parametersätze sowie WebQuery-Merkmale gegen die enthaltenen Handbücher ab.
Der Smoke-Test verwendet ausschließlich einen lokalen TCP-Testpartner und temporäre Dateien.

## Automatisiert geprüft

- Vollständiges Escaping einschließlich Bell/vertikalem Tab, Unicode, leeren Werten und `=` in Werten; 1.000 deterministische zufällige Rundläufe.
- Raw-Login vor Serverauswahl, LFCR-Banner und Event-Registrierungen.
- Mehrzeilige Antworten mit dazwischen eintreffenden Events, 20 gleichzeitige Aufrufer, Serverfehler und anschließende Requests.
- Timeouts, Verbindungsende und Query-Aufrufe aus Event-Handlern.
- Echter lokaler SSH-Handshake und Shell mit Apache MINA SSHD: Passwortanmeldung, bekannte Host-Keys, Ablehnung unbekannter Schlüssel und kein zusätzlicher Query-Login.
- WebQuery-API-Header, POST/JSON ohne doppelte Escapes, HTTP-/TeamSpeak-Fehler, fehlender Statuscode, Batchaufrufe, Voice-Port- und Instanzpfade sowie stockende HTTP-Antwortkörper.
- Binärer Upload/Download mit Transfer-Key und vom Server gemeldeter IP, Größenlimit und unterschiedlichen Transfer-IDs.
- Rechtevererbung, spezifische Regeln, Ablehnungen, Gruppenverknüpfung, Zyklen und Persistenz.
- Profile, Zusammenführen, Lokalisierung, Scheduler, Service-Injection, Token-Scopes/Ablauf/Widerruf und beschädigte JSON-Dateien.
- TaskBuilder-Snapshots, Objektidentität, Neuplanung laufender Tasks ohne Überlappung, serialisierte/asynchrone Worker, Fehler und Wiederholungsbedingungen, dauerhafter Abbruch trotz ignorierter Interrupts, alte Handles nach Wiederregistrierung und Entladen eines Plugins mit laufendem/wartendem Task.
- Config-/Locale-/Transport-Qualifier mit Plugin-Kontext und Defaults aus einem Plugin-JAR; kategoriale JSON-/YAML-Dateien, gültige JSON-Ausgabe, Reload ohne Zustandsverlust bei Syntaxfehlern und Auflösung aller Felder vor Zuweisung.
- Locale-Units mit ursprünglicher Jeak-Testdatei, Betreiber-Overrides, Fallback pro Schlüssel, dynamisch aktualisierten Kontexten, fehlenden/wiederholten Parametern, persistenten UID-/Ländereinstellungen, Schreibfehlern und autorisierten Locale-Befehlen.
- Command-v2-Szenarien mit typisierten Parametern/Optionen, optionalen und alternativen Kriterien ohne Positions-/Wert-Lecks, Zahlenüberläufen, Quotes, booleschen Flags, Alias-/Shorthand-Konflikten, atomarem Ersetzen und lokalisierbaren Matcherfehlern. Custom-Matcher-Registrierungen werden wiederhergestellt; die Auswertung begrenzt kombinatorische Pfade.
- Typisierte Client-/Channel-/Offline-Benutzer-/Gruppen-/Subjekt-Auflösung, Mindest-Rechte vor dem Matching und frische Autorisierung vor dem Executor, Absender-ID-Wechsel und Entladen während eines blockierten Matchers. Ein frisch kompiliertes Java-Paper-Plugin-JAR registriert und entfernt seinen Befehl einschließlich Aliases und eigenem Matcher.
- REST-Authentifizierung und Entfernen registrierter Routen.
- Hibernate-/Hikari-Units mit echten H2-Verbindungen: Entity-CRUD, gemeinsame verschachtelte JDBC-/ORM-Transaktionen, Rollback trotz abgefangenem innerem Fehler, Callback nach Rollback, teilweise fehlgeschlagener Transaktionsstart, Thread-/Unit-Isolation, Entfernen/Neuanlegen von Units und Freigabe manuell geöffneter EntityManager.
- YAML-/JSON-PersistenceUnits einschließlich alter Jeak-Verbindungsschlüssel, Rücknahme teilweise geladener Units, bytecodebasierte Entity-Erkennung aus einem frisch kompilierten Java-Plugin-JAR und Initialisierung vor `onEnable()`.
- `@Transactional` auf tatsächlich ausgeführten Event-Listenern und HTTP-Controllern: Commit bei Erfolg, Rollback bei Fehler und Erhalt expliziter HTTP-Fehler.
- Dienstübergreifende Profilidentitäten, Optionen löschen, Merge-Strategien, unveränderliche Ereignis-Snapshots und unveränderter Speicherzustand bei Schreibfehlern.
- Lokaler SMTP-Dialog mit TO/CC/BCC, Unicode, Plaintext/HTML, Headern und binären Anhängen; Ablehnung unverschlüsselten Fallbacks, asynchronen Fehlern und benannten Transporten.
- Automatisch geladene alte SMTP-JSON- und neue YAML-Units, Schlüssel-Aliases, Property-Overrides, Envelope-/Header-Absender, MIME-kodierte Anzeigenamen, Deduplizierung, Header-Entfernung, Dateianhang-Prüfung, fehlgeschlagene Ladebatches und alte Registrierungshandles. Echte lokale STARTTLS- und implizite TLS-Handshakes mit AUTH erst nach Verschlüsselung; Ablehnung nicht vertrauenswürdiger Zertifikate und falscher Servernamen. Schließen einer blockierten SMTP-Sitzung und wartender Dispatch-Futures.
- Frisch kompiliertes Java-Paper-Plugin-JAR: automatische TransportUnit-Injection schon vor onLoad, Versand in onEnable und Ressourcenfreigabe beim Stoppen bzw. fehlgeschlagenen Start. UID-Profilbenachrichtigung über die automatisch geladene mail-notifications-Unit bis zur tatsächlich empfangenen lokalen SMTP-Nachricht.
- Benachrichtigungsrouting anhand von UID, Dringlichkeit, Lebensdauer und Profil-Mailadresse; wiederverwendete Client-IDs, UTF-8-Bytegrenzen, eigene Kanäle und Shutdown ausstehender Arbeit mit simulierten Query-/Mailtransporten.
- Annotierte REST-Controller über echtes lokales HTTP einschließlich typisierter Parameter, JSON-DTOs und generischer Listen, Fehlerantworten, Token-Scopes, Größenlimits und atomarer Registrierung.
- Öffentliche Web-Assets und geschützte Verwaltungs-APIs, Plugin-Seiten/Assets mit Ressourcenfreigabe und ein tatsächlich gebautes Welcome-Plugin-JAR mit persistenter Einstellungs-API. Fehlgeschlagene Konstruktion des Web-Dienstes gibt teilweise registrierte Routen wieder frei und erhält bereits vorhandene fremde Routen.
- Reale Chromium-Bedienprüfung: Anmeldung/Fehler, Tastatur und Fokus, keine Tokens in Browser-Storage oder URL, Client-Suche, Channel-Auswahl, Navigation/Überlauf über sechs Bildschirmbreiten, Statusfehler/Wiederholung und Abmelden. Das lokale Fixture prüft zusätzlich tatsächliches Speichern über HTTP, Lesetoken-Ablehnung, verzögerte Plugin-Einbindung, Cleanup-/Mount-/Stylesheet-Fehler und Abbruch eigener Plugin-Requests beim Seitenwechsel.
- Laden des tatsächlich gebauten Beispiel-Plugin-JARs, Ausführen seines Befehls, Entfernen seiner Ressourcen und Aufräumen nach fehlgeschlagenem Plugin-Laden.
- Paper-artige Plugin-Syntax mit `JavaPlugin`, `plugin.yml`, Aliases, Usage-Ausgabe, Standard-/eigenem Executor und frischer Command-Autorisierung.
- `@EventHandler`-Prioritäten und `ignoreCancelled`, gebündelte YAML-Konfiguration pro Plugin-JAR, Speichern/Neuladen, optionale Abhängigkeiten und automatische Task-/Listener-Freigabe.
- Initiale Wiederverbindung, erneute Sitzungsinitialisierung, begrenzte Fehlversuche und Cache-Invalidierung.
- PCM-/Opus-Rundlauf mit Stereo, 48 kHz, 20 ms und Lautstärke einschließlich Stummschaltung; echter FFmpeg-WAV-Decoder mit gepaddetem Schlussframe (`CERBERUS_TEST_FFMPEG` war gesetzt).
- Musik-Queue, Skip/Stop, Titelersetzung, Abbruch und Freigabe blockierender Decoder, Wiederaufnahme nach Quellenfehlern, mehrere getrennte Bots und Instanz-/Queue-Limits.
- Lokale Musikpfade einschließlich Symlink-Grenzen; Laden des fertigen Musik-Plugin-JARs, Erstellen eines Bots per Aliasbefehl und Freigabe beim Deaktivieren.
- Paket-AEAD mit Bouncy Castle 1.87: identische UDP-Wiederholungen, getrennte Sende-/Empfangsoperationen und Ablehnung manipulierter MACs.
- ICY-Metadaten über beliebige Lesegrenzen, Unicode/Latin-1, Werbung und Titelwechsel. Ein echter FFmpeg-Test dekodiert Audio und Metadaten aus genau einer lokalen HTTP-Verbindung.
- Dynamische Beschreibung und JPEG-Titelkarte, 200-Codepoint-Beschreibungsgrenze, Pause-Zustand und zusammengefasste Änderungen ohne Avatar-Neuupload bei reiner Lautstärkeänderung.
- Asynchrone Voice-Avatar-Aushandlung mit `clientftfid`, frühe/fremde Notifications, Ablehnungen, vollständiger binärer Upload und Veröffentlichen der MD5 erst nach Transfer-Ende.
- Server-Setup: idempotenter zweiter Start, stabile IDs beim Umbenennen/Verschieben, Rechteänderung/-entfernung, CRUD beider Gruppentypen, Planmodus ohne Server-/Zustandsänderungen, Namenskollisionen und unbekannte Permissions vor Mutationen, Server-UID-/Channel-Markierungsprüfung, Erhalten zugewiesener Gruppen und belegter Channels sowie Wiederaufnahme nach Teilfehlern.
- Tatsächlich gebautes Setup-Plugin-JAR über den Plugin-Manager geladen: automatischer Startabgleich, Command-Alias und Entfernen des Befehls beim Deaktivieren.

Die maschinenlesbaren Ergebnisse stehen nach dem Testlauf in `build/test-results/test`;
der HTML-Bericht unter `build/reports/tests/test/index.html`. Die 15 Setup-Tests und ihr Bericht liegen zusätzlich unter `examples/server-setup-plugin/build/test-results/test` bzw. `examples/server-setup-plugin/build/reports/tests/test/index.html`.

## Live-Prüfung gegen TeamSpeak 6

Am **27. November 2026** wurde die gebaute Distribution mit dem Beispiel-Plugin gegen einen externen Linux-Server geprüft.
Der Server meldete `6.0.0-beta14`, Build `1794844729`.

- SSH-Passwortanmeldung als `serveradmin`, Serverauswahl und Nickname setzen.
- Vollständige Sitzungsinitialisierung einschließlich der fünf Event-Registrierungen.
- `version`, `whoami`, `serverinfo`, `channellist`, `clientlist -uid -groups` und frisches `clientinfo`.
- Laden und Aktivieren des Beispiel-Plugins sowie Ausführen von `hello "Kotlin Welt"` über die lokale Konsole mit Antwort `Hallo Kotlin Welt!`.
- Begrüßung beim Aktivieren des Beispiel-Plugins: Das Senden einer privaten Nachricht `hallo <Name>` an einen verbundenen Benutzer wurde vom Server bestätigt; Query-Clients wurden übersprungen. Die Anzeige im Benutzer-Client wurde nicht unabhängig geprüft.
- Private Nachricht an den Bot selbst und Empfang des passenden `notifytextmessage`-Events.
- Geordnetes Stoppen und Schließen der Verbindung.
- Zwei normale Voice-Clients über UDP: Musik-Bot und Empfänger. Der Empfänger dekodierte im Dateitest 199 Pakete als 48-kHz-Stereo mit je 20 ms; Pause/Fortsetzen bestanden.
- YouTube-Link des Benutzers über yt-dlp `2026.11.23` aufgelöst, abrufbares Format mit `--check-formats` geprüft, über FFmpeg dekodiert und als Opus übertragen. Der zusätzliche reproduzierbare `MusicLiveTest` empfing und dekodierte 53 Pakete und prüfte Pause/Fortsetzen; anschließend wurden beide Voice-Verbindungen geschlossen.
- Das fertige Musik-Plugin wurde anschließend mit echter SSH-Query gestartet. Über die lokale Konsole wurden `create`, `volume`, `play` und `status` ausgeführt. Der laufende Stream lieferte einem zusätzlichen Voice-Empfänger 351 dekodierbare Pakete; der Benutzer bestätigte die hörbare Wiedergabe seines YouTube-Links.
- Der vom Benutzer angegebene I♥X-MAS-Stream liefert `icy-metaint=16000`, Werbung sowie Künstler/Titel. Die Beschreibung wurde per `clientedit` gesetzt und eine 7,4-kB-JPEG-Titelkarte über TCP-Dateitransfer hochgeladen; `client_flag_avatar` wurde vom TS6-Server angenommen. Für die beiden Testidentitäten wurden ausschließlich eigene Beschreibung und bis zu 64 kB Avatar erlaubt.
- Im laufenden Radio-Bot wurden anschließend die Beschreibung zum aktuellen Titel und der gesetzte Avatar-Hash per Query zurückgelesen. Der Avatar wurde unabhängig vom Server heruntergeladen (8.442 Bytes) und als Bild geprüft. Der Benutzer bestätigte beide Anzeigen im TeamSpeak-Client. Der Stream liefert keine Cover-URL; deshalb wird eine Titelkarte verwendet.
- Setup-Plugin gegen denselben TS6-beta14-Server: temporäre permanente Channels mit Parent/Child, Server- und Channelgruppen aus Typ-0-Vorlagen erstellt; beide Gruppentypen umbenannt, Sortier-Permissions geändert, einen Channel umbenannt und nach Root verschoben sowie ein Channel-Recht entfernt. Ein anschließender Plan enthielt null Änderungen. Danach wurden beide Channels und beide Gruppen ohne Force gelöscht; alle temporären Objekte sind entfernt. Die Live-Ergebnisse führten zur expliziten Behandlung von Fehler 1281 für leere Listen und zum Vermeiden redundanter `channelmove`-Befehle.
- Der dauerhafte Community-Aufbau wurde danach mit 14 Channels, vier Servergruppen, drei Channelgruppen sowie Servername/Begrüßung angewendet (41 Änderungen, keine zurückgestellten Löschungen). Ein erneuter Plan ergab null Änderungen; Benutzer und Musikbot blieben in Channel 1 verbunden. Das Setup-JAR liegt im aktiven Plugin-Ordner für den nächsten Botstart; die bestehende Musik-Laufzeit wurde dafür nicht neu gestartet.
- Zusätzlicher lesender Matcher-Test gegen denselben TS6-Server: fünf erfolgreiche Auflösungen über Client-ID, Datenbank-ID, UID, persistenten DB-Benutzer und Channel-ID. `clientdbfind -uid pattern=...` und `clientdbinfo cldbid=...` liefen über eine separate SSH-Query-Verbindung. Es wurde keine Chatnachricht gesendet und die laufende Musik-Instanz nicht neu gestartet; der zusätzliche Query-Client wurde danach geschlossen.

Die lokale Verbindungskonfiguration und der beim ersten Kontakt erfasste SSH-Host-Key sind von Git ausgeschlossen.
Der Host-Key wurde für Folgeverbindungen fest hinterlegt, aber nicht unabhängig über die Serverkonsole bestätigt.

## Grenzen

Die automatisierten SSH-Tests verwenden modellierte Query-Antworten; die oben aufgeführte Live-Prüfung ergänzt diese.
Allgemeine Channel-Dateitransfers, WebQuery, eingeschränkte Query-Konten, Anti-Flood-Verhalten unter Last, Netzwerkabbrüche sowie
Musik-Chatbefehle durch einen echten Benutzer-Client wurden noch nicht gegen den Zielserver geprüft; der Plugin-JAR-Test verwendet einen simulierten Transport.
Lange Radio-Sitzungen und automatische Voice-Wiederverbindung sind nicht live geprüft; Voice-Bots müssen bei Verbindungsverlust derzeit neu erstellt werden.
Während des längeren Hörtests meldete TS3J vereinzelt `IllegalArgumentException` im Transport; die Ursache ist noch nicht eingegrenzt. Verbindung und hörbare Wiedergabe liefen dabei weiter.
Beim Neustart meldete der Server den bisherigen Query-Nickname noch als belegt. Der laufende Test-Controller verwendet deshalb `Cerberus Radio Control`; automatisches Wiederherstellen bei einem belegten Namen wurde nicht implementiert.
SMTP wurde mit einem lokalen Testserver einschließlich tatsächlicher STARTTLS-/impliziter TLS-Handshakes, Authentifizierung und Zertifikats-/Servernamen-Ablehnungen geprüft; eine Prüfung gegen einen externen Mailanbieter steht weiter aus. Hibernate/Pools wurden gegen H2 geprüft; eine externe MariaDB-Instanz ist noch nicht geprüft.
Das Webinterface wurde gebaut und seine HTTP-/Plugin-Integration automatisiert geprüft. Die Browser-Funktionsprüfung bestand 13 Szenarien mit echten TS6-Daten sowie 20 Szenarien mit einer lokalen Query-Fixture und der tatsächlichen Welcome-Plugin-JAR, jeweils einschließlich Navigation bei 1440/1024/820/768/390/320 Pixeln. Visuelle Taste-Prüfungen sind separat in `web/DESIGN.md` dokumentiert; es wird kein vollständiger visueller Abschluss behauptet.
Der GitHub-Actions-/CodeQL-Workflow wurde aktualisiert, aber nicht auf GitHub ausgeführt.

Unter der lokalen macOS-Version blockierte Gradles nativer Dateiwächter. `org.gradle.vfs.watch=false` verhindert dies;
der Build benötigt keinen Dateiwächter. Alle 358 ursprünglichen Dateien unter `src` sind bytegleich unter `legacy/src` erhalten.
