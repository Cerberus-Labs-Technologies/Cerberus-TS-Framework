# Cerberus TS – Änderungen

## 2.0.0-SNAPSHOT

- Musiksteuerung als Plugin-Seite im Webinterface: Bots verbinden/trennen, Wiedergabe und Queue, Pause/Skip/Stop, Lautstärke, Channelwechsel und Titel-/Fehlerstatus; geschützte HTTP-API mit Plugin-Lebenszyklus.

- Pelican-Eggs für PLCN_v3/PTDL_v2, Container-Image mit Musik-Werkzeugen und GitHub-Packages-Workflow; persistente Serverdaten und konsolenunabhängiges Bereitschaftssignal.

- Cerberus Labs übernimmt die Entwicklung und Wartung der Kotlin-Neufassung unter dem Namen Cerberus TS.
- Packages und Maven-Gruppe auf `labs.cerberus`, Artefakte und Startprogramm auf `cerberus-ts` umgestellt.
- Konfiguration auf `cerberus.properties`, Umgebungsvariablen auf `CERBERUS_` und Framework-Rechte auf `cerberus.*` umgestellt.
- Paper-artige Plugin-API mit `JavaPlugin`, `plugin.yml`, Listenern, Commands, YAML-Konfiguration und Scheduler.
- TeamSpeak-Query-Unterstützung über SSH, TS3 Raw und HTTP/HTTPS-WebQuery mit dokumentiertem TS6-Abgleich.
- Musikdienst mit mehreren Voice-Bots, persistenten Identitäten, Stereo-Opus, FFmpeg, YouTube über yt-dlp und Paper-artigem `music`-Plugin.
- Queue, Pause/Fortsetzen, Skip, Stop, Kanalwechsel und Lautstärke; gezieltes Recht `cerberus.music.control`.
- ICY-Radio-Metadaten aus derselben Audioverbindung; dynamische Beschreibung und Avatar mit Cover oder generierter Titelkarte, einschließlich Werbung/Pause/Leerlauf.
- Paper-artiges Server-Setup-Plugin: deklarative Channels, Server-/Channelgruppen, Vorlagenkopien, Rechteabgleich, Planmodus und persistente Objektverwaltung mit Löschschutz.

Bekannte Grenzen und Migrationshinweise stehen in [docs/migration.md](docs/migration.md).
Die ursprüngliche Projekthistorie liegt unverändert unter `legacy/CHANGELOG`.
