# Beiträge zu Cerberus TS

Cerberus Labs entwickelt und wartet Cerberus TS. Der aktive Quellcode liegt unter
`src/main/kotlin/labs/cerberus/ts`, Tests unter `src/test/kotlin/labs/cerberus/ts`.
`legacy/` bewahrt den ursprünglichen Stand als historische Referenz.

## Entwicklungsumgebung

JDK 21 installieren und das Projekt als Gradle-Projekt öffnen. Den eingecheckten Wrapper verwenden:

```sh
./gradlew clean build :examples:welcome-plugin:build installDist
python3 tools/smoke_distribution.py
```

Kotlin-Konventionen einhalten, öffentliche API-Änderungen dokumentieren und relevante Tests ausführen.
Das Beispiel-Plugin zeigt die Paper-artige API; Details stehen in [docs/plugins.md](docs/plugins.md).

## Fehler und Änderungen

Bei einem Fehler Framework-Version, Betriebssystem/JDK, TeamSpeak-Serverversion, Query-Transport,
Reproduktionsschritte und relevante bereinigte Logs angeben. Zugangsdaten und Tokens entfernen.
Sicherheitsprobleme nach [SECURITY.md](SECURITY.md) melden.

Änderungen an der Plugin-API, Konfiguration oder Protokollbehandlung im Migrationsleitfaden erklären.
Vor einer Veröffentlichung Tests, Distribution und Beispiel-Plugin prüfen; Live-Kompatibilität nur dann
behaupten, wenn ein entsprechender Test tatsächlich erfolgt ist.
