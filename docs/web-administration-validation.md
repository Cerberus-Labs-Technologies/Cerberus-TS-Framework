GATE: FAIL 19 (round 2 of 3)

# Webverwaltung – Prüfstand vom 27. November 2026

Die Channel- und Gruppenverwaltung ist implementiert, gepusht und als Pelican-Image veröffentlicht. Das unveränderte Taste-Gate lief gegen die öffentliche Anmeldung erneut durch und meldet weiterhin 19 offene Befunde sowie fünf Warnungen. Es gibt keine Waiver. Dies ist eine funktionale Erweiterung, keine bestandene visuelle Gesamtfreigabe. [Aktueller Gate-Bericht mit allen offenen Befunden](validation/web-administration-gate.json), [ursprüngliche Befunde und ihre Einordnung](web-visual-validation.md#alle-offenen-gate-befunde).

## Funktionen

Channels: erstellen, Name/Thema/Beschreibung bearbeiten, Typ und Client-Limit setzen, Audio/Qualität/Talk-Power/Passwort ändern, verschieben und leere Channels ohne Unterchannels löschen. Die API gibt keine Passwörter zurück. Revisionsprüfungen verhindern, dass ein inzwischen geänderter Channel unbemerkt durch einen alten Entwurf überschrieben wird.

Server- und Channelgruppen: normale Gruppen erstellen, umbenennen, kopieren, Rechte lesen/setzen/entfernen und Gruppen löschen. Vorlagen- und Query-Gruppen sind schreibgeschützt; Vorlagen können kopiert werden. Negated/Skip sind auf Servergruppen beschränkt. Mitgliedschaften nutzen Datenbank-IDs, auch für Offline-Clients; Channelgruppen lassen sich einem Channel zuweisen und auf dessen Serverstandard zurücksetzen. Das Löschen zugewiesener Gruppen erfordert die ausdrückliche Force-Auswahl.

Alle APIs benötigen passende Bearer-Scopes. Ein Lesetoken kann keine Änderung speichern. Zusätzlich gelten die TeamSpeak-Rechte der Query-Verbindung. Die Oberfläche umgeht weder Rechte noch Serverregeln. Vom Server-Setup-Plugin verwaltete Objekte können bei dessen nächstem Abgleich erneut auf seine YAML-Vorgaben gesetzt werden; Webänderungen aktualisieren diese YAML-Datei nicht automatisch.

## Funktions- und Protokollnachweise

- Funktionscommit: `1be7fe272e7310081f9b53fa387515f3bc6ed6c0`.
- Gesamtbuild: 163 Tests erfasst, 162 bestanden, ein ausdrücklich deaktivierter Live-Test, keine Fehler.
- Vier neue `WebAdministrationTest`-Integrationstests mit tatsächlichem HTTP-Server und zustandsbehaftetem Query-Partner: CRUD, Kopien, Revisionskonflikte, Platzierung/Zyklen, Passwort-Redaktion, Rechte und Mitgliedschaften, Query-Fehler, Schreibschutz und konkurrierende Schreibaktionen. Alle zwölf Schreibpfadarten werden vor Query-Ausführung durch GET-Tokens abgewiesen. API-Routen verschwinden beim Schließen.
- [23 neue Verwaltungsszenarien im Browser](validation/web-administration-browser.json): sichtbare Channel-/Gruppenformulare, Rechte und Zuweisungen, Konflikte, Lesetoken und jeweils beide Editorseiten bei 1440/1024/820/768/390/320 px. Formulare behalten beim Polling ihre ungesendeten Eingaben und ihren Fokus. Keine unbehandelten Browserfehler.
- [20 bestehende Webszenarien](validation/web-administration-core-browser.json) und [16 Musik-Szenarien](validation/web-administration-music-browser.json) weiterhin bestanden: zusammen 59 lokale Browser-Szenarien, einschließlich tatsächlicher lokaler WAV-Dekodierung.
- Distributions-Smoke-Test sowie acht Pelican-Skripttests und der Pelican-Starttest mit Web-Allocation, Token-Anmeldung und Konsolen-Shutdown bestanden.
- Die mitgelieferte offizielle TS6-beta14-Referenz wurde erneut anhand ihrer gepinnten Archivprüfsumme geprüft: 43 Query-Parametersätze und WebQuery-Eigenschaften. `setclientchannelgroup` und Mitgliedschaftsbefehle sind zusätzlich erfasst.
- [Tatsächlicher TS6-beta14-Test](validation/web-administration-live.json) über die neue HTTP-API: eigene temporäre Channels erstellen/lesen/ändern/verschieben/löschen, veraltete Revision abweisen, Channelrechte setzen/lesen/entfernen, beide Gruppentypen erstellen/umbenennen/kopieren/löschen, Gruppenrechte und leere Mitgliederlisten prüfen. Alle Testobjekte sind entfernt; `cleanupFailures` ist leer. Bestehende Gruppen und Mitgliedschaften echter Clients wurden nicht verändert.
- TS6 versieht neue Gruppen bereits mit Servervorgaben. Die Live-Prüfung kontrolliert deshalb die konkreten gesetzten/kopierten Rechte und setzt keine feste Anzahl voraus. Mitgliedschaftsänderungen und das Zurücksetzen auf die Standard-Channelgruppe sind am Query-Partner geprüft; sie wurden nicht an echten Benutzern getestet.
- Die reproduzierbare Live-Probe steht in [WebAdministrationLive.java](../tools/WebAdministrationLive.java). Sie verlangt ausdrücklich `--allow-live` und eine geeignete Testserver-Konfiguration, druckt keine Zugangsdaten und räumt ihre eigenen Objekte ohne Force-Löschung auf.

Der ursprüngliche Radio-Prozess und seine Distribution wurden bei dieser Arbeit nicht ersetzt oder neu gestartet.

## Veröffentlichung

Der [Pelican-Image-Workflow](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46048969990) und [Kotlin-Build/CodeQL](https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework/actions/runs/46048970111) des Funktionscommits sind erfolgreich. Linux-Build und Tests sowie der echte Start des nicht privilegierten amd64-Containers sind geprüft; das Image wurde für amd64 und arm64 veröffentlicht. Ein vollständiger ARM-Laufzeittest ist damit nicht behauptet.

Das veröffentlichte Image ist anonym abrufbar. Der bei dieser Prüfung beobachtete Index-Digest war `sha256:668d7d221de98c5198ac401d2439ac70c4471399e0f6627af88b9fb6500af659`. Der unveränderliche Funktionstag lautet:

```text
ghcr.io/cerberus-labs-technologies/cerberus-ts-framework:sha-1be7fe272e7310081f9b53fa387515f3bc6ed6c0
```

Spätere Commits zu Prüfhilfen und Dokumentation ändern den Stand der beschriebenen Verwaltungsfunktionen nicht. `latest` kann durch deren Image-Workflow einen neueren Digest erhalten.

## Darstellungsnachweis und Grenzen

Software zur operativen TeamSpeak-Verwaltung. Die bestehende Taste-Auswahl bleibt white-link-blue, Familjen Grotesk/IBM Plex Mono und weiche Radien. Stimme: technisch, direkt, deutsch. Formulare verwenden explizite Labels, separate Hilfetexte, sichtbaren Tastaturfokus und konkrete Erfolgs-/Fehlermeldungen. Listen und Editoren stehen auf breiten Bildschirmen nebeneinander, unter 1280 px untereinander. Marketing-Hero, Effekte und Moments wurden dem bestehenden Anwendungsauftrag entsprechend nicht ergänzt; diese Entscheidung ist keine bestandene Gate-Ausnahme.

Channels und Servergruppen wurden jeweils bei 1440/1024/820/768/390 px mit dem unveränderten Taste-Screenshot-Werkzeug aufgenommen und alle zehn Bilder angesehen. Lokale Dateien: `web/shots/administration-{channels,groups}-{1440,1024,820,768,390}.png`. Die Aufnahmen zeigen ausdrücklich die lokale UI-Testinstanz. Geöffnete Audio-/Passwort-/Löschbereiche, neue Gruppen und Channelgruppen sind funktional geprüft; sie sind nicht als vollständige eigene Screenshot-Matrizen dokumentiert.

Der Anmeldehelfer verwendet pro Aufnahme ein frisches Profil und meldet sich im selben Taste-Browser über das normale Formular an. Er wählt lediglich den vorhandenen lokalen Channel beziehungsweise die vorhandene Testgruppe aus. Ein älterer Abschlusshelfer blieb nach gespeicherter PNG hängen und wurde an seiner Zeitgrenze beendet. Die restlichen Aufnahmen wurden mit dem korrigierten Helfer abgeschlossen: erst nach gespeicherter PNG schließt er den Browser aus genau seiner CDP-Sitzung, notfalls nach fünf Sekunden über dessen ermittelte PID. Erst nach bestätigtem Browserende beendet er den zugehörigen hängenden Nodeprozess. Es wurden weder Anwendung noch Taste-Prüftool verändert oder andere Browser beendet. Es lief jeweils nur ein Aufnahmebrowser.

Das Gate prüft die öffentliche Anmeldung, nicht alle authentifizierten Editorzustände. Alle 19 offenen Befunde und fünf Warnungen bleiben im verlinkten JSON-Bericht sichtbar; die früheren Beschreibungen gelten weiterhin. Es gab keinen neuen separaten Konsistenz-, Layout-, Copy- oder Performancecheck der angemeldeten Editoren. Frühere Messungen der Anmeldung gelten nicht als Nachweis für diese Formulare. Der visuelle Gesamtabschluss bleibt offen.

Es wurden keine neuen Produktnamen, Preise, Kontakte, Statistiken oder echten Nutzerdaten angenommen. Eine kanonische öffentliche Web-Domain ist weiterhin nicht festgelegt. Zusätzliche Fotos, Grafiken, Kundenmaterialien oder Formularziele sind für diese Verwaltung nicht erforderlich. Die Testnamen und Zuweisungen sind als lokale Prüfwerte erkennbar.
