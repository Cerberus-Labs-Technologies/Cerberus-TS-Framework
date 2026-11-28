# YouTube-Cookies und Video-Verbindungsprüfung

Geprüft am 28.11.2026. Die Anmeldung erfolgt optional über `music.cookies.file` beziehungsweise `CERBERUS_MUSIC_COOKIES_FILE` und gilt für Audio und Video. Es wurden ausschließlich erfundene Cookie-Daten getestet; ein erfolgreicher YouTube-Abruf mit einem angemeldeten Konto ist noch nicht bestätigt.

## Änderungen

- Pro Auflösung eine eigene temporäre Cookie-Datei mit Dateirechten 0600 und Verzeichnisrechten 0700 unter Linux/macOS. yt-dlp schreibt seine Aktualisierungen ausschließlich in diese Kopie. Aufgeräumt wird bei regulärem Abschluss, Fehler und Abbruch. Die Originaldatei bleibt unverändert.
- Fehlende, unlesbare, falsch formatierte und zu große Dateien werden mit fester Fehlermeldung abgewiesen. Cookie-Dateien innerhalb des Medienverzeichnisses einschließlich dorthin auflösender Symlinks sind unzulässig.
- Das Video-Angebot enthält Audio vor Video, eine gemeinsame explizite MediaStream-ID und zwei Sendespuren. Die öffentliche Container-IP wird auch in eingebetteten SDP-Kandidaten verwendet.
- Nicht verbundene Zuschauer erhalten nach 25 Sekunden eine sichtbare Diagnose. Fehlgeschlagene ICE-/SDP-Verbindungen geben den Zuschauerplatz wieder frei.

## Nachweise

Der vollständige Gradle-Build mit allen drei Beispielplugins und der getrennten Testdistribution besteht. 17 Node-Medientests einschließlich echter FFmpeg- und yt-dlp-Projektionsprüfungen bestehen. Kotlin prüft Cookie-Konfiguration, private Kopien, Ablehnung unsicherer Dateien und die tatsächliche Übergabe an einen Audio-Resolver-Testprozess. Distributions- und Pelican-Smoke-Tests prüfen Start, Query-Anmeldung, Plugins und sauberes Beenden.

Der [lokale Chromium-Test](validation/video-native-webrtc.json) verwendet den geänderten echten Publisher/Encoder und isolierte Signalisierung. Er weist dekodierte und gerenderte VP8-Bilder, laufende Wiedergabe, nicht leere Bildinhalte und Opus-Audioenergie nach.

Die Pelican-API bestätigte am laufenden Bot die öffentliche Video-IP und alle zugeordneten Ports 24900–24919. Ein [externer Chromium-Empfänger](validation/video-pelican-native-before.json) empfing eine eigens erzeugte MP4 aus dem laufenden Pelican-Container über den echten TS6-Signalisierungsweg. Bild und Ton funktionierten bereits im zuvor bereitgestellten Image. Temporärer Testbot, Empfänger und Testdatei wurden entfernt; `kino_1` blieb unverändert. Dieser Nachweis grenzt den gemeldeten Fehler ein, erklärt aber noch nicht den leeren Stream im offiziellen Client.

Offen bleiben ein Sicht-/Hörtest im offiziellen TeamSpeak-6-Desktop-Client mit dem aktualisierten Image und ein Test mit der konkret betroffenen MP4-Quelle. Die Cookie-Funktion garantiert keine Freigabe durch YouTube und ersetzt keine eventuell zusätzlich verlangten Tokens.

API-Schlüssel, Cookie-Inhalte, Web-Zugriffstokens und rohe Serverlogs gehören nicht zu den Prüfberichten oder zum Repository.
