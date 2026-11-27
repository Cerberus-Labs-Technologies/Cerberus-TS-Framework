# Webinterface und Plugin-Seiten

Das Webinterface wird mit dem Framework gebaut und aus dessen JAR ausgeliefert. Es verwendet React und shadcn-Komponenten. Es zeigt die Daten der jeweiligen Bot-Instanz: Verbindung, Clients, Channels, Musikbots und geladene Plugins. Eine zweite Bot-Instanz hat ihren eigenen Zustand.

## Start und Anmeldung

Die geladene Standardkonfiguration aktiviert die Oberfläche auf `127.0.0.1:24875`. `web.enabled=false` schaltet sie aus. `web.host` und `web.port` bestimmen den Listener; ein explizites `rest.port` verwendet stattdessen den gemeinsamen REST-Listener samt `rest.host`.

Den Bot mit `--console` starten und in seiner lokalen Konsole `web-token` eingeben. Das ausgegebene Token gilt 24 Stunden und erlaubt die Verwaltungs- und Plugin-APIs. Die Anmeldung prüft das Token gegen `/api/web/status`. Die Oberfläche hält es nur im Arbeitsspeicher; Neuladen oder Abmelden beendet diese Browseranmeldung. Das Token wird weder in der URL noch in LocalStorage, SessionStorage oder Cookies gespeichert. Serverseitig bleibt es bis zum Ablauf oder Widerruf gültig.

Die HTML-Seite und ausdrücklich registrierte Assets sind öffentlich. API-Aufrufe benötigen passende Bearer-Scopes. Ein reines Lesetoken kann mit `TokenService.generate(setOf("GET /api/web/*", "GET /api/plugins/*"), duration)` erstellt werden. Externer Zugriff kann über einen HTTPS-Reverse-Proxy erfolgen; der Listener bleibt standardmäßig lokal. Konfigurationspasswörter werden von der Verwaltungs-API nicht ausgeliefert.

Das gebündelte Musik-Plugin ergänzt die Seite **Musik-Bots** mit Bot-Verbindungen, Wiedergabe, Queue, Lautstärke und Channelwechsel. Die Bedienung und API stehen in [Musik-Bots](music.md#steuerung-im-webinterface).

## Seite aus einem Paper-artigen Plugin registrieren

Das vollständige Beispiel ist das [Welcome-Plugin](../examples/welcome-plugin). Es liefert eine Einstellungsseite samt tatsächlich speichernder API aus.

```kotlin
override fun onEnable() {
    if (!server.web.isAvailable) return
    server.web.registerPage(
        WebPage(
            id = "settings", title = "Einstellungen",
            description = "Begrüßungstext für dieses Plugin.",
            module = "index.js", stylesheet = "style.css"
        ),
        mapOf("index.js" to "web/index.js", "style.css" to "web/style.css")
    )
    server.web.route("GET", "/settings") {
        RestResponse(body = mapOf("greeting" to config.getString("greeting", "Hallo")))
    }
}
```

`WebPage` kommt aus `labs.cerberus.ts.web`, `RestResponse` aus `labs.cerberus.ts.service`. Ressourcen liegen im Plugin-JAR, etwa `src/main/resources/web/index.js`. Die Map ist eine ausdrückliche Asset-Liste; andere JAR-Dateien werden nicht veröffentlicht. Module dürfen ausschließlich lokale `.js`-/`.mjs`-Einstiegspunkte verwenden. Relative Importe müssen ebenfalls in der Liste stehen. Bis zu 128 Assets, 16 MiB pro Datei und 64 MiB pro Seite sind erlaubt. URLs enthalten einen Hash des Bundles, damit geänderte Plugin-Dateien einen neuen Moduleinstieg erhalten.

`server.web.route` stellt automatisch den Plugin-Namensraum `/api/plugins/<plugin-id>` voran. Alternativ registriert `server.web.registerController(controller)` annotierte REST-Controller in diesem Namensraum. Authentifizierung, Method-/Pfad-Scopes und Eingabevalidierung gelten auch dort. Das Plugin muss schreibende Requests und ihre fachlichen Berechtigungen selbst definieren. Registrierungshandles gehören automatisch zum Plugin; beim Entladen verschwinden Seiten, Assets und APIs.

## JavaScript-Vertrag

```javascript
export async function mount(root, api) {
  const settings = await api.request('/settings')
  const text = document.createElement('p')
  text.textContent = settings.greeting
  root.append(text)
  return () => { /* eigene Timer, Listener und andere Ressourcen freigeben */ }
}
```

`mount(root, api)` darf asynchron sein und eine Cleanup-Funktion zurückgeben. `api.version` ist `1`. `api.request(path, options)` ergänzt den eigenen API-Namensraum und das Zugriffstoken, liefert JSON und wirft bei HTTP-Fehlern einen Fehler mit lesbarer Meldung. `api.notify(text, "success" | "error")` zeigt eine Meldung im gemeinsamen Rahmen; `api.navigate("/clients")` navigiert innerhalb der Anwendung.

Jede Einbindung erhält einen eigenen DOM-Host. Beim Seitenwechsel werden ihre Requests abgebrochen, auch wenn das Plugin zusätzlich ein eigenes AbortSignal übergibt. Ein verspätetes `mount` kann den Host der nächsten Seite nicht überschreiben; sein zurückgegebenes Cleanup wird nachgeholt. Cleanup-Fehler werden protokolliert und blockieren die Navigation nicht. Fehler beim Laden eines Stylesheets oder beim Einbinden einer Seite werden mit einer Wiederholungsaktion angezeigt. „Daten aktualisieren“ bindet auch die aktuelle Plugin-Seite neu ein.

Ein Plugin muss Ressourcen, die es bereits vor einem fehlgeschlagenen `mount` angelegt hat, selbst freigeben. CSS sollte unter `[data-plugin-page="<plugin-id>/<page-id>"]` begrenzt werden. Plugins führen JavaScript im selben Browserkontext aus und sind vertrauenswürdiger Code; dieses SDK ist keine Sandbox für fremde oder unvertrauenswürdige Erweiterungen.

## Reproduzierbare Bedienprüfung

`tools/WebPreview.java` startet die gebaute Distribution mit echten HTTP-APIs und entweder einem lokalen Query-Fixture (`fixture`) oder einer ausschließlich lesenden TS6-Verbindung ohne Plugins (`live`). Die tatsächlichen Welcome- und Musik-JARs werden nur im Fixture-Modus geladen. Die Voice-Verbindungen des Musikdienstes werden dort durch lokale Testverbindungen ersetzt; FFmpeg und die HTTP-APIs bleiben echt. Der Harness schreibt seine kurzlebigen Testtokens in eine private temporäre Datei und gibt ausschließlich deren Pfad aus.

`web/scripts/browser-check.mjs <metadata.json> <report.json>` prüft die Anmeldung, Suche, Navigation, Fehlerwiederholung und Abmeldung bei 1440, 1024, 820, 768, 390 und 320 Pixeln. Im Fixture-Modus prüft es zusätzlich Speichern, eingeschränkte Tokens, asynchrones Einbinden, Stylesheet-Fehler, Cleanup-Fehler und Request-Abbruch. Es benötigt `npm ci` sowie einen Playwright-Browser oder `CERBERUS_BROWSER` mit einem Chromium-Pfad. Berichte enthalten keine Tokens.

Vom Repository-Verzeichnis aus lässt sich der lokale Harness so starten:

```sh
./gradlew :examples:welcome-plugin:jar :examples:music-plugin:jar installDist -Pcerberus.install.dir=build/install/cerberus-ts-web-preview
javac -cp 'build/install/cerberus-ts-web-preview/lib/*' -d /private/tmp tools/WebPreview.java
java -cp '/private/tmp:build/install/cerberus-ts-web-preview/lib/*' WebPreview fixture
```

Die Harness-Konsole mit `stop` beenden. Der ausgegebene Metadatenpfad ist das erste Argument des Browserprüfers, der aus `web/` ausgeführt wird. `live` statt `fixture` verwendet die lokale, nicht versionierte Datei `config/music-live.properties`; dieser Modus verbindet eine zusätzliche lesende Query-Sitzung und lädt keine Plugins. Die normale laufende Musik-Instanz wird dadurch nicht ersetzt.

Für angemeldete Taste-Aufnahmen hängt sich `web/scripts/shoot-auth.mjs` ausschließlich an den vom unveränderten Taste-Skript gestarteten Browser und bedient das normale Loginformular. Ein frisches Profil pro Aufnahme verhindert Verwechslungen mit wiederhergestellten Tabs. Erst nach gespeicherter PNG und bestätigtem Schließen dieses Browsers beendet der Helfer gegebenenfalls dessen zurückgebliebenen Nodeprozess. Er verändert weder Anwendung noch Prüftool und startet keinen zweiten Browser. Metadaten, Browserprofile und Aufnahmen bleiben lokale QA-Artefakte.

Diese Funktionstests ersetzen weder visuelle Prüfung noch die Prüfung jeder künftigen Plugin-Seite. Taste-Auswahl und Darstellungsnachweise stehen in [web/DESIGN.md](../web/DESIGN.md).

`web/scripts/music-check.mjs <metadata.json> <report.json>` prüft zusätzlich die Musiksteuerung mit tatsächlicher lokaler WAV-Dekodierung, Bot-Lebenszyklus, Lesetoken, Polling und sechs Bildschirmbreiten. Dieser mutierende Test akzeptiert ausschließlich den Fixture-Modus. FFmpeg muss im PATH der Vorschau liegen.
