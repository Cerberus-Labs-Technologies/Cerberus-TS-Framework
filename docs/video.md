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

Zusätzlich benötigt der Bot ausgehend Zugriff auf den TeamSpeak-Voice-Port, die Videoquelle und einen STUN-Server. Standard ist `stun:stun.l.google.com:19302`; mit `CERBERUS_VIDEO_STUN` änderbar. TURN-/SFU-Weiterleitung ist in dieser Vorschau nicht integriert. Bei schwierigen NAT-/Firewall-Konstellationen kann die direkte Verbindung scheitern; dann erscheint eine Zuschauerfehlermeldung. Die öffentliche IPv4 wird als ICE-Kandidat angekündigt, ohne einen Socket an eine im Container nicht vorhandene Adresse zu binden.

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
