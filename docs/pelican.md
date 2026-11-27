# Cerberus TS im Pelican-Panel

Das Egg betreibt das **Framework als eigenen Server**, neben dem vorhandenen TeamSpeak-Server.
Es enthält keine TeamSpeak-Serverinstallation. Entwicklung: Cerberus Labs.

## Dateien und Image

- [Pelican-Egg, aktuelles PLCN_v3-Format](../deploy/pelican/egg-cerberus-ts.json)
- [Kompatibles PTDL_v2-Egg für ältere Panels](../deploy/pelican/egg-cerberus-ts-v2.json)
- [Runtime-Dockerfile](../deploy/pelican/Dockerfile)
- [Image-Workflow](../.github/workflows/pelican-image.yml)

Image: `ghcr.io/cerberus-labs-technologies/cerberus-ts-framework:latest`.
Es enthält die gebaute JVM-Distribution mit Webinterface, Java 21, FFmpeg/ffprobe,
Python, yt-dlp mit den Standard-Abhängigkeiten einschließlich EJS, Node.js 24 und
Schriften für die Titelkarten. Das Framework wird im Panel weder kompiliert noch heruntergeladen.
Die Konsole unterstützt `web-token`, Framework-/Plugin-Befehle und `stop`.

Der Workflow baut und testet zuerst das amd64-Image mit einem lokalen Query-Testserver,
prüft Web-Anmeldung und Konsolen-Shutdown und veröffentlicht danach amd64/arm64.
Der arm64-Lauf enthält Dependency-/Programmprüfungen beim Image-Build; ein kompletter
arm64-Betriebstest ist damit nicht behauptet. `latest` wird erst nach erfolgreicher Validierung ersetzt.
Zusätzlich erhält jeder Stand den Tag `sha-<vollständiger Commit-SHA>`.
Ein grüner Workflow **Pelican runtime image** und ein abrufbares Package sind vor dem ersten Start erforderlich.

## Repository und Image-Zugriff

Das Framework-Repository ist öffentlich. Das [native Egg](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/raw/refs/heads/main/deploy/pelican/egg-cerberus-ts.json) herunterladen und als Datei importieren. Automatische Egg-Updates bleiben in den Exporten deaktiviert. Die Sichtbarkeit des GHCR-Packages wird separat vom Repository verwaltet.

Neue GHCR-Packages sind normalerweise privat. Für private Images auf dem Wings-Node
in `/etc/pelican/config.yml` die Registry ergänzen; vorhandene Docker-Einstellungen beibehalten:

```yaml
docker:
  registries:
    ghcr.io:
      username: "DEIN_GITHUB_LOGIN"
      password: "PAT_MIT_READ_PACKAGES"
```

Dafür einen **Personal Access Token (classic)** mit `read:packages` und Zugriff auf dieses Package verwenden, gegebenenfalls mit Freigabe
für das SSO der Organisation. Anschließend Wings neu starten. Der Registry-Token gehört nur
in die Wings-Konfiguration, nicht in Egg-Variablen, TeamSpeak-Konfigurationen oder das Repository.
Alternativ kann ein Organisationsadministrator das Package bewusst öffentlich bereitstellen.
Der Workflow verändert keine Package-Sichtbarkeit.

## Im Panel einrichten

1. **Admin → Eggs → Import**: `egg-cerberus-ts.json` hochladen. Falls das Panel PLCN_v3
   nicht versteht, `egg-cerberus-ts-v2.json` verwenden.
2. Einen neuen Server mit diesem Egg anlegen. Zunächst **2 GiB RAM**, 2 CPU-Kerne und
   4 GiB Speicher als Ausgangspunkt für Framework und wenige Musikbots vorsehen;
   tatsächlicher Bedarf hängt von Plugins, Musikbots und Warteschlangen ab.
3. Eine freie **TCP-Allocation**, beispielsweise `24875`, als primäre Allocation zuweisen.
   Das ist ausschließlich der Web-Port des Frameworks. Keine vorhandenen TeamSpeak-Ports
   erneut zuweisen. Das Webinterface bindet im Container an `0.0.0.0` und den primären Port.
4. Unter **Startup** TeamSpeak-Verbindung und gewünschte Plugins eintragen:

   | Variable | Bisheriger Testserver / Bedeutung |
   | --- | --- |
   | `CERBERUS_HOST` | `65.108.107.32` |
   | `CERBERUS_GENERATION` | `TS6` |
   | `CERBERUS_TRANSPORT` | `SSH` |
   | `CERBERUS_PORT` | `24873` — Query-SSH des TeamSpeak-Servers |
   | `CERBERUS_USERNAME` | Dein Query-Benutzer, bisher `serveradmin` |
   | `CERBERUS_PASSWORD` | Query-Passwort selbst eintragen; kein Passwort ist vorbelegt |
   | `CERBERUS_SERVER_ID` | `1` |
   | `CERBERUS_NICKNAME` | Eigener freier Name, etwa `Cerberus Panel` |
   | `CERBERUS_ADMIN_UID` | Deine TeamSpeak-UID für Framework-Adminrechte |
   | `CERBERUS_BUNDLED_PLUGINS` | Standard: `welcome,music` |

5. In **Dateien → config/known_hosts** den verifizierten SSH-Host-Key eintragen.
   Bei einem abweichenden Port muss der Eintrag beispielsweise so beginnen:

   ```text
   [65.108.107.32]:24873 ssh-rsa HIER_DEN_TATSAECHLICH_VERIFIZIERTEN_OEFFENTLICHEN_SCHLUESSEL_EINTRAGEN
   ```

   Das ist ein Platzhalter, kein gültiger Schlüssel. Den vollständigen Eintrag aus einer
   bereits überprüften `known_hosts` übernehmen. Das Egg akzeptiert unbekannte Schlüssel
   nicht automatisch. Die bisherige lokale Datei `config/ts6_known_hosts` wird nicht veröffentlicht.
6. Starten. Nach erfolgreicher Verbindung meldet die Konsole **`[cerberus] CONNECTED`**;
   erst dann markiert Wings den Server als bereit. Bei fehlender Verbindung ist das
   Webinterface unter Umständen bereits erreichbar, der Bot aber noch nicht bereit.
7. In der Panel-Konsole **`web-token`** eingeben. Das ausgegebene Token ist 24 Stunden gültig.
   Das Webinterface unter `http://NODE-IP:ALLOCATION` öffnen und mit dem Token anmelden.
   Unter **Channels** und **Gruppen** lassen sich Channel-Einstellungen, Server-/Channelgruppen, Rechte und Zuweisungen bearbeiten, siehe [Webverwaltung](web.md#channels-und-gruppen-verwalten).
   Unter **Musik-Bots** lassen sich bei geladenem `music`-Plugin Voice-Bots und Wiedergabe steuern, siehe [Musiksteuerung](music.md#steuerung-im-webinterface).
   Für dauerhaften Zugriff über das Internet HTTPS über einen Reverse Proxy einrichten.

Die Panel-Anmeldung meldet dich nicht automatisch im Framework-Webinterface an.
Der Bot benötigt ausgehenden Zugang zu Query-SSH, zum Voice-Port UDP `24871` und
zum vom TeamSpeak-Server ausgehandelten Datei-Port TCP `24872`; Radio/YouTube benötigen
zusätzlich ausgehendes HTTP/HTTPS. Für Voice-Clients sind keine zusätzlichen eingehenden
Allocations erforderlich. Bei getrennten Containern zeigt `localhost` nicht auf den TeamSpeak-Container.

## Plugins, Daten und Updates

- Eigene Plugin-JARs in `plugins/` hochladen.
- Gebündelte Beispiele über `CERBERUS_BUNDLED_PLUGINS` wählen: `welcome`, `music`,
  `server-setup`, kommagetrennte Kombinationen oder `none`.
- **`server-setup` ist standardmäßig aus.** Seine Aktivierung wendet beim Start den in
  [Server-Setup](server-setup.md) beschriebenen Channel-/Gruppenaufbau an.
  Vor Aktivierung `data/plugins/server-setup/config.yml` vorbereiten beziehungsweise nach
  dem ersten Laden gemäß Plugin-Dokumentation anpassen. Das Standardbeispiel nimmt bereits Änderungen vor.
- Die Namen `plugins/cerberus-bundled-{welcome,music,server-setup}.jar` sind reserviert.
  Beim Start werden ausgewählte Beispiele auf den Image-Stand aktualisiert; abgewählte
  Beispiele werden nur unter diesen drei reservierten Namen entfernt. Eigene JARs unter
  anderen Namen, Plugin-Konfigurationen und Daten bleiben erhalten. Keine zweite Kopie
  desselben Plugins unter anderem Dateinamen hochladen.
- Konfiguration: `config/cerberus.properties`; Daten, Profile, Tokens und Musikidentitäten:
  `data/`; Audiodateien: `data/music/files/`. Diese Verzeichnisse vor Umzügen sichern.
- Zugangsdaten können alternativ in `config/cerberus.properties` liegen. Dann das entsprechende
  optionale Passwort-/API-Key-Feld im Panel leer lassen. Andere Panel-Variablen überschreiben
  Dateieinstellungen; Web-Bindung, Allocation und Daten-/Plugin-Verzeichnisse verwaltet das Startskript.
  Panel-Benutzer mit Zugriff auf Variablen oder Dateien können dort abgelegte Geheimnisse lesen.
- Update: neuen Image-Build abwarten, den Container stoppen und mit aktualisiertem Image neu
  erstellen/starten. Ein bloßer JVM-Neustart in einem bestehenden Container aktualisiert kein Image.
  Alternativ einen bestimmten `sha-...`-Tag im Panel eintragen. Reinstallation erstellt nur fehlende
  Verzeichnisse/Host-Key-Vorlage und setzt keine Daten zurück.
- yt-dlp ist im Dockerfile über `YT_DLP_VERSION` festgelegt. Bei YouTube-Änderungen diese Version
  aktualisieren und das Image neu bauen. Musikbots/Queues stellen sich nach einem Neustart derzeit
  nicht automatisch wieder her; dies bleibt ein offener Punkt des Frameworks.

Die JVM erhält maximal 70 Prozent des Container-RAMs als Heap; der Rest ist für native
JVM-Daten, FFmpeg und Node vorgesehen. Bei vielen Musikbots entsprechend mehr RAM zuweisen.

## Lokal bauen und prüfen

```sh
python3 tools/test_pelican.py
python3 tools/test_pelican.py --distribution build/install/cerberus-ts-web-preview
docker build -f deploy/pelican/Dockerfile -t cerberus-ts:local .
# Unter Linux mit Docker:
python3 tools/test_pelican.py --image cerberus-ts:local
```

`python3 deploy/pelican/generate.py` erneuert beide Egg-Exporte aus derselben Quelle.
Die Tests prüfen unter anderem die Übereinstimmung der Exporte, Allocation, Plugin-Auswahl,
Datenbestand bei Updates, Ablehnung unzulässiger Parameter, Host-Key-Pflicht und Secret-Übergabe.
Der echte Smoke-Test verwendet ausschließlich einen lokalen Query-Testserver.
Ein erfolgreicher Image-Smoke-Test ersetzt keinen abschließenden Test auf deinem Pelican-Node.

Quellen: [Pelican-Egg-Konfiguration](https://pelican.dev/docs/eggs/creating-a-custom-egg/),
[aktueller Pelican-Importer](https://github.com/pelican-dev/panel/blob/main/app/Services/Eggs/Sharing/EggImporterService.php),
[private Wings-Registries](https://pelican.dev/docs/wings/optional-config/),
[GHCR-Authentifizierung](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-to-the-container-registry),
[yt-dlp-Abhängigkeiten](https://github.com/yt-dlp/yt-dlp#dependencies).
