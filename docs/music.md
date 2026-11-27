# Musik-Bots

Cerberus TS stellt mit `MusicService` mehrere unabhängige Voice-Clients bereit.
Das optionale Paper-artige Plugin `examples/music-plugin` steuert diese über `!music` oder `!musik`.
Die Kotlin-Implementierung verwendet TS3J für UDP/Handshake, FFmpeg für das Decoding
und Concentus für 48-kHz-Stereo-Opus in 20-ms-Paketen. Es wird keine Soundkarte benötigt.

## Installation

Auf dem Rechner beziehungsweise im Container **des Frameworks** müssen `ffmpeg`, `yt-dlp`
und für YouTube eine von yt-dlp unterstützte JavaScript-Runtime installiert sein (Standard: Node.js).
Aktuelle Versionen verwenden; bei YouTube-Problemen zuerst yt-dlp und die Runtime aktualisieren.
Diese Programme laufen außerhalb der JVM und werden nicht in das Framework-JAR eingebettet.
Die Pfade können über Properties oder entsprechende `CERBERUS_*`-Variablen gesetzt werden:

```properties
music.ffmpeg=ffmpeg
music.ytdlp=yt-dlp
music.js.runtime=node
music.max.bots=4
music.max.queue=100
```

```sh
./gradlew build :examples:music-plugin:build installDist
cp examples/music-plugin/build/libs/music-plugin.jar plugins/
build/install/cerberus-ts/bin/cerberus-ts --console
```

Im lokalen Bot-Terminal einem Benutzer gezielt die Steuerung erlauben:

```text
grant <TeamSpeak-UID> cerberus.music.control 1
```

Die UID lässt sich mit `!whoami` beim Query-Bot abfragen. Alle Musikbefehle benötigen
`cerberus.music.control`; die lokale Konsole darf sie ebenfalls ausführen.
Private Chatbefehle an den **Query-Bot** senden. Dieser steuert die getrennten Voice-Clients.
Private Nachrichten direkt an einen Voice-Musik-Bot werden bislang nicht als Befehle verarbeitet.

## Bedienung

```text
!music create lounge 1 Cerberus Music
!music play lounge song.mp3
!music play lounge https://example.org/radio-stream
!music play lounge https://youtu.be/VIDEO_ID
!music add lounge next.flac
!music queue lounge
!music pause lounge
!music resume lounge
!music volume lounge 25
!music skip lounge
!music stop lounge
!music move lounge 2
!music status lounge
!music remove lounge
!music list
```

`create` nimmt eine frei wählbare Bot-ID, die numerische Channel-ID und optional den Namen.
Der Name muss zwischen 3 und 30 Zeichen lang sein. Der Voice-Port wird über `serverinfo` ermittelt;
auf dem bisherigen Testserver ist dies UDP `24871`. Der Query-Port bleibt TCP `24873`.
Für mehrere Bots unterschiedliche IDs verwenden. Jeder Bot belegt einen normalen Server-Slot.
`play` ersetzt den laufenden Titel und leert die Warteschlange; `add` reiht einen Titel ein.
`skip` überspringt den aktuellen Titel; `stop` beendet die Wiedergabe und leert die Warteschlange.
Lautstärke: 0–100 %, zunächst 50 %. Änderungen wirken nach dem kleinen Audiopuffer.
Die Auflösung und Wiedergabe laufen im Hintergrund. Ladefehler stehen bei `status`.

Lokale Audiodateien liegen unter `<data.directory>/music/files/`.
Erlaubt sind MP3, WAV, FLAC, Ogg/Opus, M4A, AAC, WebM und MP4; Pfade außerhalb dieses
Verzeichnisses und ausbrechende Symlinks werden abgelehnt. Leerzeichen können zitiert werden.
Direkte HTTP-/HTTPS-Audio- und Radiostreams werden von FFmpeg gelesen. YouTube-Links
werden beim Abspielen über yt-dlp aufgelöst; Playlists werden nicht automatisch eingereiht.

## Beschreibung und Avatar

Jeder Musik-Bot aktualisiert seine eigene Beschreibung und seinen Avatar automatisch.
Die Beschreibung enthält Wiedergabestatus, Titel, Künstler, gegebenenfalls Radiosender und Lautstärke.
Der Avatar zeigt das mitgelieferte Cover mit Titelbeschriftung; ohne Cover entsteht eine 300×300-Pixel-Titelkarte.
Pause, Laden, Werbung und Leerlauf werden ebenfalls angezeigt. Updates werden im Hintergrund zusammengefasst;
unveränderte Bilder werden nicht erneut hochgeladen. Anzeigefehler stehen getrennt von Audiofehlern bei `!music status`.

Für die Bot-Identität müssen auf dem TeamSpeak-Server diese Rechte erlaubt sein:

| Recht | Wert |
| --- | --- |
| `b_client_modify_own_description` | `1` |
| `i_client_max_avatar_filesize` | mindestens `64000` |

Diese Rechte gezielt der jeweiligen Identität oder einer dafür angelegten Musik-Bot-Gruppe geben.
Der Framework-Code verändert Serverrechte nicht automatisch. Der vom Server ausgehandelte Datei-Port
muss erreichbar sein (beim Testserver TCP `24872`). Beschreibung und Avatar werden über die eigene
Voice-Identität gesetzt: `clientedit`, `ftinitupload` in Channel `0`, anschließend `client_flag_avatar` mit der Datei-MD5.
Im Voice-Protokoll wird dazu die mit `clientftfid` zugeordnete `notifystartupload` abgewartet;
der Dateiinhalt läuft anschließend über die separate TCP-Verbindung. Eine abgelehnte Aushandlung veröffentlicht keinen Avatar-Hash.
Datei- und Hash-Format wurden gegen TS6 beta14 geprüft; TS3-Client-Kompatibilität ist separat zu prüfen.

Bei ICY-Radios kommen Titelinformationen aus **derselben Verbindung wie die Audiodaten**.
ICY-Blöcke werden vor der Weitergabe an FFmpeg entfernt. Titelwechsel aktualisieren Beschreibung und Titelkarte;
bei erkannter Vorschaltwerbung wird kein alter Songtitel weiter angezeigt. Liefert der Sender kein Cover,
wird keines erfunden. YouTube liefert Titel, Künstler beziehungsweise Kanal und Vorschaubild über yt-dlp;
lokale Dateien verwenden derzeit ihren Dateinamen.

Identitäten liegen unter `<data.directory>/music/identities/<id>.ini` und bleiben bei
`remove` erhalten. Beim erneuten Verbinden mit gleicher ID erhält der Bot dieselbe Identität.
Diese Dateien enthalten private Schlüssel und gehören nicht in Git. Bot-Instanzen und
Warteschlangen werden beim Framework-Neustart nicht automatisch wiederhergestellt.
Ein Verbindungsabbruch wird im Status sichtbar; anschließend `remove` und `create` verwenden.
Zum Beenden stoppt `stop` im lokalen Framework-Terminal alle Bots und Decoder.

Ein optionales Serverpasswort wird über `CERBERUS_VOICE_PASSWORD` gesetzt und ist vom Query-Passwort getrennt.
Passwortgeschützte Channels können über die Kotlin-API mit `channelPassword` verbunden werden;
der Chatbefehl unterstützt bislang nur Channels ohne Passwort.

## Kotlin-API

```kotlin
val music = getService(MusicService::class.java)
music.create("lounge", "ts.example.org", 24871, channelId = 1)
music.bot("lounge").play("song.mp3")
music.bot("lounge").enqueue("https://youtu.be/VIDEO_ID")
music.bot("lounge").volume(25)
music.bot("lounge").pause()
music.bot("lounge").resume()
music.remove("lounge")
```

Verbindungsaufbau kann blockieren und gehört in einen Hintergrund-Task. Der Musikdienst
wird beim Framework-Shutdown geschlossen; Plugins müssen eigene Bots beim Deaktivieren freigeben.

## Protokoll und Abhängigkeiten

- [TS3J](https://github.com/Manevolent/ts3j), fest auf Commit `db57d60c989e399626aa16d921390f5033e6cdeb` gepinnt:
  enthält den TS5-/TS6-Lizenzhandshake. Die Bibliothek wird über das auf dieses Modul begrenzte JitPack-Repository bezogen.
- [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/) 1.87 ersetzt die alte transitive 1.67.
  `PacketAead` verwendet pro Datagramm eine eigene EAX-Operation und behält TS3Js Schlüsselableitung und Wire-Format bei.
  Identische UDP-Wiederholungen und MAC-Manipulationen sind durch einen Regressionstest abgedeckt.
- [Concentus](https://github.com/jaredmdobson/concentus) 1.0.2: portabler Opus-Encoder in der JVM.
- [FFmpeg](https://ffmpeg.org/ffmpeg.html) für Stereo-PCM und [yt-dlp](https://github.com/yt-dlp/yt-dlp) für YouTube.

Audio-Decoding, Opus-Rundlauf und Queue-/Abbruchverhalten werden in `MusicTest` geprüft.
Mit `CERBERUS_TEST_FFMPEG=/pfad/zu/ffmpeg ./gradlew test` wird auch der echte Decoder geprüft.
`MusicLiveTest` ist standardmäßig deaktiviert. Mit explizit gesetzten `CERBERUS_LIVE_HOST`,
`CERBERUS_LIVE_VOICE_PORT`, `CERBERUS_LIVE_CHANNEL`, `CERBERUS_LIVE_YOUTUBE`,
`CERBERUS_TEST_FFMPEG` und `CERBERUS_TEST_YTDLP` öffnet er zwei Voice-Verbindungen,
spielt den angegebenen Link kurz ab, dekodiert die empfangenen Pakete und prüft Pause/Fortsetzen.
Der Test schließt beide Verbindungen anschließend. Das fertige Musik-Plugin-JAR wird separat
mit einem simulierten Transport auf Command-Ausführung und Ressourcenfreigabe geprüft.
Den tatsächlich geprüften Live-Umfang dokumentiert [validation.md](validation.md).
