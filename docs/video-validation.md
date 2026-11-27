# Video-Vorschau: Prüfstand

Die Funktion ist als Vorschau implementiert. Der vollständige Build ist bestanden; ein Sicht-/Hörtest im offiziellen TeamSpeak-6-Desktop-Client und die tatsächliche Pelican-NAT-Konfiguration sind separat zu bestätigen. Der bekannte visuelle Gesamtstatus der Webanwendung bleibt offen: [19 bestehende Taste-Befunde und fünf Warnungen](validation/web-administration-gate.json), keine Waiver. Für die Video-Erweiterung wurde kein neuer Gesamt-Gate-Durchlauf durchgeführt.

## Funktionale Nachweise

- 164 aufgezeichnete JVM-/Plugin-Tests: 163 bestanden, ein ausdrücklich deaktivierter alter Live-Test, keine Fehler.
- Sieben Node-Medienprüfungen bestanden: Channel-/Zuschauerfreigabe, kompaktes Opus-/VP8-Angebot mit Audio zuerst, Quellen- und Pfadprüfung inklusive Symlinks, tatsächliche FFmpeg-RTP-Ausgabe/Pause/Fortsetzen/Stop, Pelican-ICE-Adresse, stummes Video mit sauberem Dateiende und tatsächliche Laufzeit-Lautstärkeänderung bis auf stummes PCM.
- [17 Video-Browserprüfungen](validation/video-browser.json): echte Kotlin-HTTP-API und Worker-IPC mit isoliertem Fixture-Worker. Verbindung, Streamstatus, Zuschaueranzahl, Queue/Skip, Pause/Fortsetzen, Lautstärke, Channelwechsel, Löschen, Lesetoken-Abweisung, Polling ohne Fokus-/Entwurfsverlust, keine unbehandelten Browserfehler. Grenzen und Bedienelemente bei 1440/1024/820/768/390/320 px geprüft. Der Fixture-Worker überträgt kein echtes Video.
- [16 bisherige Audio-Browserprüfungen](validation/video-audio-regression.json) unverändert bestanden; tatsächliche WAV-Wiedergabe über FFmpeg und vorhandene Musik-API.
- Acht Pelican-Prüfungen, tatsächlicher lokaler Pelican-Start mit Webauthentifizierung/Console-Stop und Distributions-Smoke-Test bestanden. Kein lokaler Docker-Daemon verfügbar; das neue Container-Image muss zusätzlich in GitHub Actions gebaut/geprüft werden.

## TS6-Server und YouTube

[Live-Bericht](validation/video-live.json): TeamSpeak `6.0.0-beta14 [Build: 1794844729]`, zwei eigene SDK-Zuschauer über den echten Server. VP8- und Opus-RTP empfangen, ein empfangenes Video-Keyframe erfolgreich als PNG decodiert. Beide Zuschauer verbunden; Pause/Fortsetzen und Stop samt Aufräumen geprüft. Alle drei eigenen Test-Clients und der Teststream wurden entfernt; bestehende Benutzer, Channels und Gruppen wurden nicht verändert.

[YouTube-Bericht](validation/video-youtube-live.json): Der zuvor genannte Link startet über den echten Kotlin-Service und die authentifizierte Musik-Plugin-HTTP-API. Titel: „Bryan Adams & Friends - California Christmas“. Die vorab geprüfte Formatwahl vermeidet die bei der ersten Integration beobachteten nicht abrufbaren Format-URLs. Der YouTube-Test-Bot wurde anschließend entfernt. Ein länger laufender temporärer eigener Testbild-Bot wurde für den angefragten Desktop-Test bereitgestellt; dessen Bestätigung ist noch offen.

Diese Tests belegen weder eine offizielle TeamSpeak-API noch die Funktion jeder Quelle oder jedes NAT-Pfads. Die Bibliotheksversionen sind fest in `video/package-lock.json` gespeichert. Bedienung und Grenzen: [Video-Bots](video.md).

## Sichtprüfung

Die authentifizierte Video-Oberfläche wurde über das bestehende, unveränderte Taste-Screenshot-Werkzeug mit dem vorhandenen Login-Adapter aufgenommen und bei 1440 und 390 px angesehen. Die Aufnahmen zeigen isolierte HTTP-Fixture-Daten, keine produktiven Zuschauerzahlen. Dateien liegen lokal unter `web/shots/video-{1440,390}.png` und werden nicht ins Repository übernommen. Kein Überlauf oder überlagerte Bedienelemente in diesen Ansichten. Weitere Breiten wurden funktional, aber noch nicht als separate Bilder angesehen. Der vorherige visuelle Gesamtstatus wird dadurch nicht als bestanden ausgegeben.
