GATE: FAIL 19 (round 1 of 3)

# Musiksteuerung im Webinterface – Prüfstand vom 27. November 2026

Die Musiksteuerung ist implementiert und funktional geprüft. Das Taste-Gate hat die unveränderte öffentliche Anmeldung wiedererkannt und seine bisherigen 19 Befunde wiederverwendet; es ist keine neue Prüfrunde und keine bestandene visuelle Gesamtfreigabe. [Aktueller Gate-Bericht](validation/music-web-gate.json), [alle bisherigen Befunde samt Warnungen](web-visual-validation.md#alle-offenen-gate-befunde). Es gibt keine Waiver.

## Funktionsnachweise

- Commit `843eef3b9041073523e784d2c8d4d3723afc9cb8`: Gesamtbuild erfolgreich, 159 Tests erfasst, 158 bestanden, ein ausdrücklich deaktivierter Live-Test, keine Fehler.
- `MusicWebTest` lädt die tatsächliche Musik-Plugin-JAR und prüft HTTP-Authentifizierung und Method-/Pfad-Scopes, Eingabevalidierung, Bots verbinden/trennen, echte WAV-Dekodierung mit FFmpeg, Queue, Pause/Fortsetzen, Skip/Stop, Lautstärke und Channelwechsel. Ein blockierter Verbindungsaufbau weist konkurrierende Schreibaktionen mit 409 zurück. Beim Entladen werden Voice-Verbindungen, Seiten, Assets und APIs entfernt.
- [16 erfolgreiche Browser-Szenarien](validation/music-web-browser.json): tatsächliche Plugin-Seite und HTTP-API, WAV-Wiedergabe, Erstellen/Trennen, Steuerung, sichtbarer Medienfehler, unveränderte Formulare und Fokus beim Polling, Lesetoken, Cleanup und Dokumentgrenzen bei 1440/1024/820/768/390/320 Pixeln. Keine unbehandelten Browserfehler.
- Separater Distributionstest erfolgreich: Start, Query-Login, Plugin-Befehl und Shutdown. Acht Pelican-Skripttests sowie der Starttest mit Web-Allocation und Token-Anmeldung erfolgreich.
- [Pelican-Image-Build](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46016215681) erfolgreich: Build/Tests in Linux, tatsächlicher Start des nicht privilegierten amd64-Containers und Veröffentlichung für amd64 und arm64.
- [Kotlin-Build und CodeQL](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46016215438) erfolgreich.
- Das aktualisierte `latest`-Image ist anonym abrufbar, Digest `sha256:86c43b64aeae0f65fa87070eb3d5bf2269ae8f34f5cc562a0ea1996999ec952e`. Unveränderlicher Tag: `ghcr.io/cerberus-labs-technologies/cerberus-ts-framework:sha-843eef3b9041073523e784d2c8d4d3723afc9cb8`.

Die HTTP- und Browserprüfungen dieses Schritts verwenden lokale Query-/Voice-Testverbindungen und echte Audiodekodierung. Sie ersetzen keinen neuen Hörtest im TS6-Client. Der ursprüngliche Radio-Prozess und seine Distribution wurden nicht neu gestartet oder überschrieben. Automatisches Wiederverbinden und Wiederherstellen von Bots/Queues bleiben offen.

## Darstellung und Taste

Software zur operativen TeamSpeak-Verwaltung; vorhandene Palette white-link-blue, Familjen Grotesk/IBM Plex Mono und weiche Radien. Die Musikseite übernimmt Formularhöhen, Fokusmarkierungen und Statusfarben des bestehenden Rahmens. Bot-Auswahl und Verbindungsformular stehen links, Player/Queue/Verbindung rechts; unter 1100 px folgt eine Spalte. Stimme: direkt, technisch, deutsch, mit konkreten Aktionen und tatsächlichen Zuständen.

Die angemeldete Musikseite wurde mit dem unveränderten Taste-Screenshot-Werkzeug bei 1440/1024/820/768/390 Pixeln aufgenommen und jede Aufnahme angesehen. Lokale Dateien: `web/shots/music-{1440,1024,820,768,390}.png`. Die Bilder zeigen ausdrücklich die lokale UI-Testinstanz, eine pausierte Test-WAV, ihre Warteschlange und 25 % Lautstärke. Das Verbindungsformular klappt bei vorhandenen Bots zu; sein geöffneter Zustand und die Bedienung werden durch die Browserprüfung abgedeckt.

Der Desktop-Aufnahmehelfer blieb nach gespeicherter PNG beim Browserabschluss hängen und endete an seiner Zeitbegrenzung. Der eindeutig zugehörige Testbrowser wurde aufgeräumt. Für die weiteren vier Aufnahmen wurde eine lokale Kopie des Anmeldehelfers verwendet, die ausschließlich nach der „saved“-Meldung den zugehörigen Taste-Browser schließt; das Taste-Werkzeug und die Anwendung wurden dafür nicht verändert. Alle vier weiteren Aufnahmen und die anschließende Browserfreigabe wurden bestätigt. Es lief jeweils nur ein Aufnahmebrowser.

Das Gate prüft weiterhin die öffentliche Anmeldung. Seine 19 offenen Befunde und fünf Warnungen bleiben vollständig in den verlinkten Berichten erhalten. Die angemeldete Musikseite wurde nicht separat durch Konsistenz-, Layout-, Copy- oder Performancechecker bewertet. Frühere Ergebnisse dieser Werkzeuge betreffen die Anmeldung und werden nicht als Musikseiten-Nachweis ausgegeben. Der visuelle Gesamtabschluss bleibt offen.

Es wurden keine neuen Produktnamen, Kontaktdaten, Preise, Statistiken oder Nutzerdaten angenommen. Eine öffentliche kanonische Web-Domain ist weiterhin nicht festgelegt. Zusätzliche Fotos, Grafiken, Kundenmaterialien oder Formularziele sind für diese Musikverwaltung nicht erforderlich; die Darstellung verwendet echte Bedienelemente und klar bezeichnete Testdaten. Marketing-Hero, dekorative Effekte und Moments wurden im Einklang mit dem bestehenden Anwendungsauftrag nicht ergänzt; dies ist keine Gate-Ausnahme.
