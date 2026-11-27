# YouTube-Metadaten: Fehlerbehebung

Der gemeldete Fehler wurde mit `https://www.youtube.com/watch?v=si_oAlgehIU` reproduziert. Der bisherige yt-dlp-Aufruf lieferte 10.595.990 Bytes JSON. Das 2-MiB-Limit des Readers beendete diesen Abruf. Die Oberfläche meldete dabei irreführend ein Problem mit yt-dlp bzw. der JavaScript-Runtime. Ein erfolgreicher früherer Test mit einem anderen Link hatte diesen Fall nicht abgedeckt.

Audio- und Video-Aufrufe verwenden jetzt yt-dlps Ausgabeprojektion: nur tatsächlich benötigte Wiedergabefelder, keine Untertitelkataloge oder vollständigen Formatlisten. Für getrennte Videoformate werden auch die einzelnen Formatobjekte auf URL und Header reduziert. Fehlende Top-Level-URLs werden als `null` serialisiert; kombinierte Formate liefern eine leere Liste getrennter Formate und bleiben abspielbar. Dokumentation der Syntax: [yt-dlp Output Template](https://github.com/yt-dlp/yt-dlp#output-template).

Die Video-Werkzeugausgabe wird weiterhin begrenzt. stderr wird kontinuierlich geleert, mit maximal 32 KiB gespeichert und ausschließlich auf feste Fehlerklassen abgebildet. Es gelangen keine rohen Ausgaben, signierten URLs oder Header in die Weboberfläche. Zeitüberschreitungen, Größenlimit, ungültiges JSON und fehlende Werkzeuge sind unterscheidbar. Der Publisher bewahrt diese sicheren Fehlermeldungen bis zum Webstatus.

Geprüft:

- Vollständiger Build mit 163 bestandenen JVM-/Plugin-Tests und einem unverändert deaktivierten Live-Test.
- Zwölf bestandene Node-Prüfungen inklusive echter FFmpeg-/yt-dlp-Aufrufe. Der neue Offline-Test erzeugt mehr als 14 MB Metadaten mit großen Untertitel- und Fragmentfeldern und prüft vier Projektionen für Audio sowie kombinierte/getrennte Videoformate. Jede resultierende Antwort bleibt unter 10 KB. Quotes und Unicode-Titel bleiben erhalten.
- Prozessprüfungen für stderr unter Last, fehlende Werkzeuge, Timeout, zu große Ausgabe, ungültiges JSON und sichere Weitergabe einer YouTube-Ablehnung durch den Publisher.
- Der konkrete Nutzer-Link wurde erneut auf TS6 beta14 an zwei eigene SDK-Zuschauer übertragen. Beide Medienarten wurden empfangen, ein empfangenes Videobild erfolgreich decodiert, Pause/Fortsetzen/Stop geprüft. Alle dabei erzeugten Test-Clients und der Teststream wurden entfernt. [Live-Ergebnis](validation/youtube-large-metadata-live.json).
- Im Dockerfile wird der Offline-Projektionstest zusätzlich mit dem tatsächlich im Image installierten pip-yt-dlp als non-root Container-Benutzer ausgeführt. Damit prüft der Image-Bau diesen Fall ohne YouTube-Anfragen. Der lokale Docker-Daemon ließ sich nicht verwenden; der Linux-Nachweis ist inzwischen in GitHub Actions erfolgreich abgeschlossen. Die Veröffentlichung für amd64 und arm64 ist erfolgreich.

Der Test gegen den TS6-Server lief vom Entwicklungsrechner. Er ersetzt keine Bestätigung nach dem Update des auf Hetzner laufenden Bot-Containers. Eine zusätzliche IP-Sperre oder Kontobeschränkung durch YouTube bleibt grundsätzlich möglich und wird nun gezielter gemeldet. Die bestehenden offenen visuellen Befunde der Webanwendung sind von diesem Backend-Fix unberührt; es gab keine Änderungen an der Oberfläche.


## Veröffentlichung

Funktionsstand: `2db76fd48243888a0b9f0337fb02e17dbf33f906`.

- [Pelican-Image: Validierung und Veröffentlichung erfolgreich](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46082050561).
- [Kotlin-Build und CodeQL erfolgreich](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46082050596).
- Image: `ghcr.io/cerberus-labs-technologies/cerberus-ts-framework:latest`; fester Tag `sha-2db76fd48243888a0b9f0337fb02e17dbf33f906`.
- Öffentlich abrufbarer Manifest-Digest: `sha256:c25b59e09b879d491eed666a0d352188ff1d7caf787e7955050b22e98e4fe96a` (linux/amd64 und linux/arm64).
- [Selbsttest aus der fertigen Distribution](validation/youtube-metadata-distribution.json): 14.000.659 Bytes erzeugte Metadaten, projizierte Antworten von 248 bis 543 Bytes, alle vier Prüfungen bestanden. Außerhalb des Checkouts ausgeführt.
- Distributions-Smoke-Test für Start, Plugin-Query-Verbindung und sauberes Beenden bestanden.

Für die Übernahme das neue Image ziehen und den Bot-Container neu starten; anschließend den Video-Bot verbinden und den betroffenen Link erneut starten. Die Bestätigung auf dem auf Hetzner laufenden Container steht noch aus.
