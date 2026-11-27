# TeamSpeak-Protokollabgleich

Recherche: 27. November 2026. Zuletzt geprüftes offizielles Serverrelease:
[TS6 6.0.0-beta14 vom 17.11.2026](https://github.com/teamspeak/teamspeak6-server/releases/tag/v6.0.0-beta14).
Gemeint ist die Serverversion, nicht die Version eines verbundenen Desktop-Clients.

Zusätzlich wurden `doc/server/webquery.md` und die befehlsspezifischen `serverquerydocs/*.txt`
direkt aus dem [offiziellen ARM64-Serverarchiv](https://github.com/teamspeak/teamspeak6-server/releases/download/v6.0.0-beta14/teamspeak6-server-linux-arm64.tar.xz) gelesen.
SHA-256 des geprüften Archivs: `06c25ad0d875a65b2ffb2361a84916b54f3ca7b2de61f959f7e15fa53ddec1fa`.
Der Inhalt kann mit `python3 tools/check_ts6_reference.py --archive <datei>` erneut geprüft werden.
Das Serverprogramm wurde dafür nicht ausgeführt.

## Transport und Authentifizierung

Die [offizielle TS6-Konfiguration](https://github.com/teamspeak/teamspeak6-server/blob/main/CONFIG.md)
führt SSH, HTTP und HTTPS. Der alte Raw-/Telnet-Zugang ist für TS6 nicht verfügbar;
die [TeamSpeak-Supportantwort zur Aktivierung](https://community.teamspeak.com/t/how-to-enable-the-serverquery/64103/3)
verweist auf SSH. Cerberus TS weist `TS6 + RAW` bereits in der Konfiguration ab.
SSH nutzt Port 10022, HTTP 10080, HTTPS 10443. TS3 Raw bleibt explizit mit Port 10011 nutzbar;
siehe [offizielle TS3-Portliste](https://support.teamspeak.com/hc/en-us/articles/360002712257-Which-ports-does-the-TeamSpeak-3-server-use).

SSH authentifiziert sich über SSH und öffnet eine Shell ohne PTY; kein zusätzlicher Query-`login`.
Raw sendet zuerst `login client_login_name=... client_login_password=...`, danach `use sid=...` bzw. `use port=...`.
SSH-Host-Keys werden gegen `known_hosts` geprüft.

Seit [TS6 beta13](https://github.com/teamspeak/teamspeak6-server/releases/tag/v6.0.0-beta13) ist Gastzugriff optional möglich:
SSH-Benutzer `guest` ohne Passwort bzw. WebQuery ohne API-Key. Der Server kann beides abschalten.
Cerberus TS aktiviert das ausschließlich mit `guest=true`, `username=guest`, `generation=TS6`.
Die Bot-Initialisierung benötigt möglicherweise mehr Rechte, als ein Gast besitzt; Gastmodus ist kein allgemeiner Administratorzugang.

## Stream-Protokoll

Syntax: `command key=value -option`, mehrere Datensätze getrennt durch `|`.
Antworten enden mit `error id=... msg=...`; Erfolg ist `error id=0`.
Notifications beginnen mit `notify...` und können zwischen Daten und Abschlusszeile einer Antwort eintreffen.

Cerberus TS verarbeitet UTF-8, LF, CRLF und LFCR sowie TS3-/TS6-Banner.
Query-Escapes: `\\`, `\/`, `\s`, `\p`, `\a`, `\b`, `\f`, `\n`, `\r`, `\t`, `\v`.
Vertikaler Tab ist U+000B, Bell U+0007. Erst Struktur trennen, danach genau einmal decodieren.

`servernotifyregister` registriert server-, channel-, textserver-, textchannel- und textprivate-Ereignisse.
Nach Wiederverbindung werden Auswahl, Nickname und Registrierung erneuert.
Query ist kein normaler Voice-Client: Nicht alle Client-/Gruppen-/Rechteänderungen sind als Query-Event zugesichert.
Autorisierung fragt `clientinfo` frisch ab; allgemeine Snapshots laufen nach 30 Sekunden ab.

## WebQuery

Die [TeamSpeak-Anleitung](https://community.teamspeak.com/t/webquery-discussion-help-3-12-0-onwards/7184)
zeigt `x-api-key`, Pfade wie `/1/channellist?-topic&-icon` und JSON mit `body`/`status`.
Das aktuelle mitgelieferte `webquery.md` dokumentiert zusätzlich POST mit JSON-Objekten und JSON-Arrays für Batches
sowie `/byport/{voicePort}/{command}`. Cerberus TS nutzt deshalb POST/JSON, den API-Key ausschließlich im Header
und Serverauswahl über ID oder Voice-Port. Instanzbefehle können mit `QueryScope.INSTANCE` ohne Serverpräfix gesendet werden.
HTTP-Weiterleitungen sind abgeschaltet; `use` entfällt zugunsten des Pfades.

JSON-Werte werden nicht zusätzlich mit dem Stream-Decoder bearbeitet.
TeamSpeak-Fehlercodes werden auch bei HTTP-Fehlern ausgewertet; HTTP 200 allein reicht nicht für Erfolg.
Ein fehlender Statuscode ist ein Protokollfehler.
WebQuery hat keine dauerhafte Event-Sitzung; Cerberus TS lehnt `servernotifyregister`, `help` und `ft*` über HTTP ab,
wie im mitgelieferten Handbuch aufgeführt. Batchaufrufe sind implementiert.

## Datei und Sprache

Dateien werden über `ftinitdownload`/`ftinitupload` ausgehandelt; Daten laufen über eine separate TCP-Verbindung.
Cerberus TS verwendet den ausgehandelten Port, eine gegebenenfalls gemeldete Transfer-IP und den Transfer-Schlüssel.
Fehlt `ip`, wird der konfigurierte Serverhost verwendet. Transfer-IDs werden je Verbindung vergeben; `proto=0` wird explizit angefordert.
TS6 beta14 ändert laut Release Notes unter anderem die UTF-8-Behandlung von Dateinamen auf Linux.

Voice/Opus verwendet einen anderen Client-/UDP-Stack; siehe [offizielle SDK-Seite](https://www.teamspeak.com/en/solutions/business-sdk).
Query-Kompatibilität beweist keine Voice-Kompatibilität. Der neue TS3J-Adapter wurde deshalb zusätzlich über
UDP gegen TS6 beta14 geprüft: Ein zweiter normaler Voice-Client empfing und dekodierte die gesendeten Stereo-Opus-Pakete.
Details zum TS6-Handshake und zur Paketverschlüsselung stehen in [music.md](music.md).

## Beleggrenze

Automatisierte Tests verwenden einen TCP-Partner, einen echten lokalen SSH-Server (Apache MINA SSHD) und HTTP-Testendpunkte.
Sie prüfen den Client gegen modellierte Protokollantworten. Zusätzlich wurden SSH-Anmeldung, Sitzungsinitialisierung,
Serverabfragen, Beispiel-Plugin und private Nachrichtenbenachrichtigungen gegen einen laufenden TS6-beta14-Server geprüft;
Umfang und verbleibende Grenzen stehen in [Validierung](validation.md).
Serverseitige Details vor Produktiveinsatz mit `help <command>` auf der eingesetzten Version prüfen.

## Webverwaltung: zusätzlicher TS6-Nachweis

Die Verwaltungs-API wurde zusätzlich gegen TS6 beta14 mit ausschließlich eigenen temporären Channels und Gruppen geprüft: Channel-CRUD, Revisionenkonflikt, Verschieben, Channelrechte, Server-/Channelgruppen anlegen/umbenennen/kopieren/löschen und Gruppenrechte setzen/lesen/entfernen. Neue Gruppen können bereits Server-Standardrechte enthalten. Kopien werden auf das Vorhandensein der gesetzten Rechte geprüft, nicht auf eine fest angenommene Anzahl. Alle Testobjekte wurden ohne Force-Löschung wieder entfernt; bestehende Gruppen und echte Client-Zuweisungen blieben unverändert. [Live-Ergebnis](validation/web-administration-live.json). Mitgliedschaftsänderungen und das Zurücksetzen auf die Standard-Channelgruppe sind gegen einen zustandsbehafteten Query-Testpartner und das mitgelieferte `setclientchannelgroup`-Handbuch geprüft, nicht an echten Benutzern.
