# Konfiguration, Übersetzungen und Injection

## Plugin-Konfiguration und benannte Dateien

Die Paper-artige `JavaPlugin.config` bleibt unter `<data.directory>/plugins/<plugin-id>/config.yml`. `saveDefaultConfig`, `reloadConfig` und `saveConfig` verwenden diese Datei und die gebündelte Ressource `config.yml`.

Zusätzlich stellt `ConfigurationService` benannte Dateien wie im alten ConfigProvider bereit. Basisverzeichnis: `<data.directory>/configuration`. Plugin-Kontexte ergänzen ihre Plugin-ID als Verzeichnis; Kategorien werden darunter angelegt.

```kotlin
import labs.cerberus.ts.service.Config
import labs.cerberus.ts.configuration.YamlConfiguration

@field:Config(id = "settings", category = "community", format = "yaml")
lateinit var settings: YamlConfiguration
```

Für Plugin `welcome` entsteht damit `configuration/welcome/community/settings.yml`. Ohne `id` gilt die Plugin-ID; ohne Plugin-Kontext wird ein Name aus deklarierender Klasse und Feld gebildet. Ohne `format` gilt wie beim alten ConfigProvider JSON. Unterstützt werden JSON und YAML einschließlich ihrer MIME-Namen. JSON wird als JSON gespeichert und strikt auf Syntax, doppelte Schlüssel und nachfolgende Dokumente geprüft. Kategorien können mehrere mit `/` getrennte Komponenten enthalten; Pfadwechsel wie `..` sind ungültig.

`@Config` unterstützt `YamlConfiguration`, `Path` und `File`. Ein Path-/File-Feld erstellt bei Bedarf Elternverzeichnisse, aber keine leere Datei. Konfigurationsobjekte für denselben Pfad werden geteilt. `configuration.save()` schreibt auf den ursprünglichen Pfad; `reload()` übernimmt Änderungen erst nach erfolgreichem Lesen. Fehler lassen den bisherigen Speicherzustand bestehen. `loadDefaults(input)` ergänzt Fallback-Werte; gespeicherte Werte gehen vor. Defaults werden durch `save()` nicht automatisch in explizite Werte umgewandelt.

## Übersetzungseinheiten

```kotlin
import labs.cerberus.ts.service.LocaleUnit
import labs.cerberus.ts.service.LocalizationUnit

@field:LocaleUnit(defaultResource = "localization/messages.json")
lateinit var messages: LocalizationUnit
```

Ohne explizite Unit-ID gilt die Plugin-ID. Mit `@LocaleUnit("shared")` lässt sich eine gemeinsame Einheit wählen. Die Ressource wird aus dem betreffenden Plugin-JAR gelesen und vor `onLoad()` geladen. Eigene Listener und Controller, die über den Plugin-Kontext registriert werden, erhalten denselben Injection-Kontext.

Format der Defaults, kompatibel mit Jeaks `langs`-Dateien:

```json
{
  "defaultLanguage": "en",
  "langs": {
    "en": {"welcome": "Hello %[name]!"},
    "de": {"welcome": "Hallo %[name]!"}
  }
}
```

Die Einheit speichert sich unter `<data.directory>/localization/units/<id>.json`. `loadDefaultsFromFile`, `loadDefaultsFromResource` und `loadDefaults` ergänzen fehlende Vorlagen und erhalten Betreiberanpassungen. `set(locale, key, template)` setzt einen Wert; `null` entfernt ihn. `reload()` lädt die gespeicherte Datei neu. Bereits abgerufene Kontexte sehen die aktuellen Daten.

```kotlin
val context = messages.context("de-DE")
val text = context.text("welcome", mapOf("name" to "Anna"))
```

Die Suche verwendet zunächst die gewünschte Region, danach ihre Basissprache, die Unit-Standardsprache und den Dienst-Fallback. Fehlende Schlüssel können einzeln auf eine Fallback-Sprache zurückgreifen. Parameterwerte werden wörtlich eingesetzt; darin enthaltene Platzhalter werden nicht erneut ausgewertet.

`getMessage(key)` liefert das unveränderte Template. `getMessage(key, params)` erhält Jeaks Verhalten: Bei fehlenden Parametern kommt das unveränderte Template zurück. `text(key, params)` ist strikt und wirft `MissingLocaleParameterException`. `optMessage` liefert ein Java-Optional, `uncheckedGetMessage` einen nullable String für unbekannte Schlüssel.

## Benutzerpräferenzen und Länder

`LocalizationService.localeOfClient(client)` berücksichtigt zuerst die UID-Präferenz, danach `client_country` und zuletzt den Fallback. `localeOfUser(uid)` funktioniert auch für Offline-Benutzer. `setLocaleForClient(uid, locale)` speichert die Präferenz; `null` setzt sie zurück. Einstellungen liegen unter `localization/settings.json` mit `fallbackLocale`, `customLangs` und optional `countryLocales`.

Ländercodes und Sprachcodes sind verschieden. Der Dienst verwendet für eindeutige Länder die Sprachinformationen der JVM. Mehrsprachige Länder erhalten ohne explizites `countryLocales`-Mapping den konfigurierten Fallback. `setCountryLocale("CH", Locale.forLanguageTag("de-CH"))` legt beispielsweise eine Betreiberentscheidung fest. Sprach-Tags werden normalisiert; ungültige Tags werden abgewiesen.

`unit.getContext(client)` berücksichtigt diese Auswahl; `contextForUser(uid)` arbeitet ohne Online-Client. `context("de-DE")` erwartet einen Sprach-Tag, `getContext("DE")` einen Ländercode.

Im Bot verfügbar:

- `!locale` zeigt die eigene effektive Sprache.
- `!locale de-DE` setzt die eigene Präferenz; `!locale reset` entfernt sie. `XX` und `unset` bleiben als Reset-Aliases verfügbar.
- `!locale de-DE <UID>` und `!locale-arg --locale=de-DE --user=<UID>` ändern andere Benutzer mit `cerberus.locale.manage`.
- In der lokalen Konsole bei Änderungen eine UID angeben. `locale` ohne Argumente zeigt dort den globalen Fallback.

Die bisherige globale `register(locale, messages)`-/`text(...)`-API und UTF-8-Properties bleiben verfügbar. Globale Bundles und benannte Units sind getrennte Namensräume.

## Service- und Transport-Injection

`@Inject` löst registrierte Services auf. `@TransportUnit(name = "alerts")` löst einen zuvor in `MailTransports` registrierten Transport auf; das Feld kann `MailTransport` oder dessen konkrete Implementierung sein. Konfigurierte SMTP-Units aus `<data.directory>/mail` werden automatisch vor dem Laden der Plugins registriert. Eigene Transport-Provider müssen ihre Registrierung vor der Injection des Verbrauchers bereitstellen. Format, alte JSON-Schlüssel und Lebenszyklus sind in [SMTP-Transporte](mail.md) beschrieben.

Die speziellen Marker können allein oder mit `@Inject` verwendet werden. Ein Feld darf nur einen speziellen Marker haben. Felder müssen veränderbar und nicht statisch sein. Geerbte Felder werden berücksichtigt. Der Injector löst alle Felder auf, bevor er Werte zuweist; eine fehlgeschlagene Auflösung hinterlässt keine teilweise zugewiesenen Felder. Datei-/Default-Erstellung während der Auflösung kann bereits erfolgt sein.

Außerhalb des Plugin-Managers kann `services.inject(target, InjectionContext(id, resourceLoader))` den Kontext explizit angeben. Unit-, Kategorie- und Ressourcenpfade werden validiert. Diese API ersetzt keine automatische Übernahme sämtlicher alter Konfigurationen; der Importauftrag unter P25 bleibt offen.
