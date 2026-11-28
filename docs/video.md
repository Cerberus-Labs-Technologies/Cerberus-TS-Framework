# Video-Bots (Vorschau)

Cerberus kann auf einem TeamSpeak-6-Server einen Video-Stream mit Ton veröffentlichen. Im Musik-Plugin zwischen **Audio-Bots** und **Video-Bots (Vorschau)** wechseln. Ein Video-Bot ist ein eigener TeamSpeak-Client mit dauerhafter Identität; bestehende Audio-Bots laufen unabhängig weiter.

## Bedienung

1. Video-Bot-ID, optional Anzeigenamen und Channel eingeben, dann **Video-Bot verbinden**.
2. YouTube-Link, direkte HTTP-/HTTPS-Videoquelle oder lokalen Dateinamen unter **Videoquelle** eingeben und **Video jetzt abspielen** wählen.
3. Im TeamSpeak-6-Client den Stream beim Bot öffnen. Bild und Ton werden im Stream übertragen; der Bot spielt den Ton nicht gleichzeitig als Channel-Audio ab.

Pause, Fortsetzen, Lautstärke, Nächstes Video, Stoppen, Warteschlange und Trennen sind im Webinterface verfügbar. Ein Channelwechsel beendet die Veröffentlichung und leert die Warteschlange. Lokale MP4-, WebM-, MKV-, MOV- und M4V-Dateien liegen unter `data/music/files/`. Stumme Videos erhalten einen stummen Audiokanal, den der TS6-Client für die Aushandlung benötigt. Eine reine Radio-/Audioquelle wird mit einer verständlichen Meldung abgewiesen.

Die Vorschau kodiert VP8 mit maximal 1280×720, 25 Bildern/s und ungefähr 1,5 Mbit/s Video plus 96 kbit/s Opus-Ton. Kleinere Quellen werden nicht hochskaliert. Maximal vier Zuschauer pro Video-Bot. Zuschauer müssen sich im selben Channel befinden; der Bot prüft die Freigabe und trennt Zuschauer beim Verlassen. Für jeden Zuschauer besteht eine eigene verschlüsselte WebRTC-Verbindung. Upload und CPU entsprechend einplanen.

## Pelican

Das aktualisierte Cerberus-Image enthält Java, Node, FFmpeg, ffprobe, yt-dlp und die fest versionierten Streaming-Bibliotheken. Egg aktualisieren bzw. die neuen Variablen übernehmen. Das alleinige Aktualisieren des Eggs aktualisiert kein bereits laufendes Container-Image.

1. Image neu ziehen und Server neu starten.
2. Dem **Cerberus-Bot-Container** zusätzlich den Bereich **24900–24919** als Allocations zuweisen und die Ports in Host-/Hetzner-Firewall für **UDP** öffnen. Diese Allocations werden nicht dem TeamSpeak-Servercontainer zugewiesen. Die primäre TCP-Allocation bleibt das Webinterface.
3. **Video public IPv4** (`CERBERUS_VIDEO_PUBLIC_ADDRESS`) auf die öffentliche IPv4 des Pelican-Nodes setzen. Auf dem genannten Root wäre das `65.108.107.32`, sofern der Bot auf demselben Node läuft.
4. **Video UDP first port / last port** auf `24900` / `24919` belassen oder einen freien anderen Bereich wählen. Alle Ports dieses Bereichs müssen 1:1 vom Host auf den Container veröffentlicht werden. Bereits belegte Ports vorher prüfen.

Zusätzlich benötigt der Bot ausgehend Zugriff auf den TeamSpeak-Voice-Port, die Videoquelle und einen STUN-Server. Standard ist `stun:stun.l.google.com:19302`; mit `CERBERUS_VIDEO_STUN` änderbar. TURN-/SFU-Weiterleitung ist in dieser Vorschau nicht integriert. Bei schwierigen NAT-/Firewall-Konstellationen kann die direkte Verbindung scheitern; dann erscheint eine Zuschauerfehlermeldung. Die öffentliche IPv4 wird sowohl im SDP-Angebot als auch in nachgereichten ICE-Kandidaten angekündigt, ohne einen Socket an eine im Container nicht vorhandene Adresse zu binden.

## Betrieb ohne Container

Die vollständige `installDist`-/ZIP-/TAR-Distribution enthält den Ordner `video/` samt npm-Abhängigkeiten. Node >=22.12, FFmpeg mit VP8/Opus, ffprobe und für YouTube yt-dlp bereitstellen. Der Kotlin-Dienst findet den Worker relativ zur Distribution. Im Checkout `npm --prefix video ci` ausführen. Optionale Pfade:

- `CERBERUS_VIDEO_HELPER`: absoluter Pfad zu `worker.mjs` (für eigene Installationslayouts).
- `CERBERUS_VIDEO_FFPROBE`: ffprobe-Pfad; standardmäßig neben dem konfigurierten FFmpeg bzw. im PATH.
- `CERBERUS_VIDEO_PORT_MIN` / `CERBERUS_VIDEO_PORT_MAX`: inklusive UDP-Portgrenzen, standardmäßig 24900–24919.
- `CERBERUS_VIDEO_PUBLIC_ADDRESS`: öffentliche IPv4 bei einer Portweiterleitung.

FFmpeg, yt-dlp und Node verwenden die bestehenden Musik-Werkzeugpfade. Die Player-Identität liegt geschützt unter `data/music/video/<id>/identity.key`. Dieses Verzeichnis persistent sichern. Quellen und Queue werden noch nicht über Neustarts hinweg gespeichert. Nach einem Verbindungsverlust den Video-Bot trennen und erneut verbinden. Pause/Fortsetzen setzt Linux oder macOS voraus. Auf Windows ist diese Aktion nicht verfügbar.

## API und Plugins

Das optionale Musik-Plugin stellt unter `/api/plugins/music` bereit:

- `GET /video`: Verfügbarkeit und Video-Bot-Status.
- `POST /video`: `{ "id": "kino", "channelId": 1, "nickname": "Cerberus Video" }`.
- `POST /video/{id}/actions`: `action` aus `play`, `enqueue`, `pause`, `resume`, `skip`, `stop`, `volume`, `move`; dieselben Feldnamen wie bei Audio-Bots.
- `DELETE /video/{id}`: Stream, Decoder und Client schließen.

Bestehende Bearer-Methoden-/Pfadrechte gelten auch hier. Ein reines Lesetoken darf keine Video-Aktion auslösen. Der Kotlin-Service ist über `getService(MusicService::class.java).video` erreichbar. Video wird derzeit über das Webinterface/API gesteuert; die vorhandenen `!music`-Befehle steuern weiterhin Audio-Bots.

## Protokoll und Grenzen

Das TS6-Streaming-Protokoll ist undokumentiert. Die MIT-Bibliothek [teamspeak-js](https://honeybbq.github.io/teamspeak-js/documents/streams.html) übernimmt die Signalisierung, [werift](https://github.com/shinyoshiaki/werift-webrtc) WebRTC/DTLS/SRTP und FFmpeg die Medienkodierung. Kotlin verwaltet Lebensdauer, API und Plugin-Anbindung. Das Framework benötigt dafür keinen grafischen TeamSpeak-Client.

TS6-Updates können die Kompatibilität ändern. Das Vorhandensein von `STREAM_P2P` wird vor der Freigabe geprüft; TS3 wird abgewiesen. Das Angebot enthält Opus-Audio vor VP8-Video und bleibt unter der TS6-Befehlsgrößengrenze. Nur eigene Streams werden beendet. Die Software-Tests ersetzen noch keinen Sicht-/Hörtest im offiziellen Desktop-Client und keinen Test der tatsächlichen Pelican-NAT-Konfiguration.

Reproduzierbare Prüfung: `CERBERUS_TEST_FFMPEG=/pfad/ffmpeg CERBERUS_TEST_FFPROBE=/pfad/ffprobe npm --prefix video test`. Der explizite Live-Test `node video/live-check.mjs --allow-live HOST VOICE_PORT CHANNEL_ID` verbindet nur eigene Test-Clients, erzeugt ein Testvideo und räumt Clients, Streams und Dateien anschließend auf. Er benötigt freie TeamSpeak-Client-Slots.


## YouTube-Fehler bei großen Metadaten

Einzelne YouTube-Videos liefern mehr als 10 MB Metadaten, etwa zu automatisch übersetzten Untertiteln. Ältere Cerberus-Versionen brachen oberhalb von 2 MiB ab und zeigten irreführend „yt-dlp und JavaScript-Runtime prüfen“. Der korrigierte Aufruf fordert für Video nur Titel, Medien-URLs und HTTP-Header der ausgewählten Formate an; für Audio kommen die verwendeten Titeldaten hinzu. Die Größenbegrenzung bleibt bestehen. Auch getrennte Audio-/Videospuren und kombinierte Formate sind abgedeckt.

Nach dem Image-Update den Bot-Container neu starten und denselben Link erneut starten. Nur das Webinterface neu zu laden aktualisiert keinen laufenden Medienprozess. Bereits gestartete Video-Bots werden beim Container-Neustart getrennt und müssen erneut verbunden werden. Die persistente Identität bleibt erhalten.

Video-Fehlermeldungen unterscheiden nun unter anderem eine YouTube-Bot-Bestätigung, HTTP 403/429, Alters-/Mitgliedschaftsbeschränkungen, eine fehlende Runtime und Zeitüberschreitungen. Diese Meldungen beruhen auf festen Fehlerklassen; temporäre signierte Stream-Adressen, Header und rohe Werkzeugausgaben werden nicht angezeigt.

Der Offline-Selbsttest im Image verwendet erzeugte Metadaten von mehr als 10 MB und benötigt keinen YouTube-Zugriff:

```sh
node /opt/cerberus/app/video/metadata-check.mjs
```

Er prüft das tatsächlich installierte yt-dlp als Container-Benutzer für Audio sowie kombinierte und getrennte Videoformate. Er wird beim Image-Bau ausgeführt. Die ursprüngliche Fehlerursache und die erneute Übertragung des betroffenen Links sind im [Prüfbericht](youtube-metadata-validation.md) dokumentiert.

## YouTube mit privater Cookie-Datei

Audio- und Video-Bots unterstützen optional eine Netscape-Cookie-Datei. Die Datei wird bei jeder YouTube-Auflösung neu gelesen. yt-dlp erhält eine private temporäre Kopie mit Dateirechten `0600`; gleichzeitige Bots ändern die hochgeladene Originaldatei nicht. Nach Erfolg, Fehler oder regulärem Abbruch wird die Kopie gelöscht. Cookie-Inhalte und Werkzeugfehler werden nicht in API-Antworten ausgegeben.

1. Nur die benötigten YouTube-Cookies im Netscape-Format exportieren. Die offizielle [Anleitung zum YouTube-Export](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies) beschreibt eine separate private Browsersitzung, `youtube.com/robots.txt`, den Export und das anschließende Schließen der Sitzung. Keine vollständigen Browserprofile exportieren.
2. Im Pelican-Dateimanager als `config/youtube.cookies.txt` hochladen. Die erste Zeile muss `# Netscape HTTP Cookie File` oder `# HTTP Cookie File` sein. Die Datei gehört außerhalb von `data/music/files`, Webassets und Git; Dateirechte möglichst `0600` setzen. Die Datei gewährt Zugriff auf die Sitzung: nicht im Chat, Repository oder in Screenshots veröffentlichen.
3. In `config/cerberus.properties` ergänzen:

   ```properties
   music.cookies.file=/home/container/config/youtube.cookies.txt
   ```

   Alternativ nach Egg-Aktualisierung **YouTube cookies file** (`CERBERUS_MUSIC_COOKIES_FILE`) auf diesen Pfad setzen. Ausschließlich den Dateipfad eintragen. Ein nicht leeres Panel-Feld überschreibt die Properties-Datei.
4. Das aktualisierte Image verwenden und den Bot-Container neu starten. Den Video-Bot erneut verbinden und den YouTube-Link starten. Spätere Cookie-Erneuerungen am selben Pfad werden beim nächsten Abruf übernommen.

Das ist keine Garantie gegen YouTubes Bot-Prüfung: Server-IP, zusätzliche Token-Anforderungen, abgelaufene Cookies und Videobeschränkungen können den Abruf weiterhin verhindern. yt-dlp weist auf mögliche Kontosperren hin; dafür nach Möglichkeit eine separate Sitzung/ein separates Konto verwenden. Details: [yt-dlp Cookie-FAQ](https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp) und [YouTube-Hinweise](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#youtube).

## Stream sichtbar, Bild und Ton fehlen

Eine veröffentlichte Stream-Ankündigung beweist noch keine Medienverbindung. Der Zuschauerzähler zählt erst verbundene WebRTC-Empfänger. Bleibt er nach dem Öffnen im TS6-Client bei null, öffentliche Video-IP, UDP-Allocations **am Bot-Container**, identische Host-/Container-Ports und beide Firewalls prüfen. Nach 25 Sekunden ohne Verbindung zeigt Cerberus eine konkrete Zuschauerfehlermeldung; anschließend den Stream im TS6-Client schließen und erneut öffnen.

Audio und Video werden explizit derselben MediaStream-ID zugeordnet und ausschließlich als Sendespuren angeboten. Der native Chromium-Empfängertest prüft nicht nur RTP-Pakete, sondern decodierte Videobilder, gerenderte Bildinhalte, fortschreitende Wiedergabe und decodierte Audioenergie. Lokal mit installiertem `web/node_modules/@playwright/test`, Chromium und FFmpeg ausführbar:

```sh
CERBERUS_BROWSER=/pfad/zu/chromium \
CERBERUS_TEST_FFMPEG=/pfad/zu/ffmpeg \
CERBERUS_TEST_FFPROBE=/pfad/zu/ffprobe \
node video/browser-check.mjs /tmp/video-native-report.json
```

Dieser Test verwendet den echten Publisher/Encoder, aber isolierte Signalisierung. Er ersetzt keinen Sicht-/Hörtest im offiziellen TS6-Client und keine Prüfung der laufenden Pelican-Instanz.
