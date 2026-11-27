# Typisierte Befehle und eigene Matcher

Die Paper-artigen `getCommand(...).setExecutor(...)`-Befehle bleiben verfügbar. Für typisierte Befehle gibt es zusätzlich unveränderliche Spezifikationen nach dem Funktionsumfang von Jeaks Command-v2-API.

```kotlin
import labs.cerberus.ts.command.Commands.commandSpec
import labs.cerberus.ts.command.Commands.paramSpec
import labs.cerberus.ts.teamspeak.Client

override fun onEnable() {
    registerCommand(
        commandSpec("inspect", "community:inspect")
            .parameters(
                paramSpec("client", Client::class.java),
                paramSpec().optional(paramSpec("detail", Boolean::class.javaObjectType))
            )
            .permission("community.inspect", 4)
            .description("Client untersuchen")
            .executor { ctx ->
                val client = ctx.required<Client>("client")
                ctx.reply("Client: ${client.nickname}; Details: ${ctx.get<Boolean>("detail") ?: false}")
            }
            .build()
    )
}
```

`registerCommand` und `registerMatcher` auf `JavaPlugin` ordnen ihre Registrierung dem Plugin zu. Alternativ `context.command(spec)` und `context.matcher(...)` verwenden. Vor `onEnable()` sind Plugin-Befehle gesperrt; beim Entladen verschwinden Name, Aliases und eigene Matcher. Ein bereits begonnener Matcher darf zurückkehren, startet danach aber keinen Executor des entladenen Befehls mehr.

## Parameterspezifikationen

`parameters(...)` beschreibt geordnete Eingaben. `paramSpec(name, type)` bindet genau einen Token. `optional(child)` erlaubt das Weglassen, `firstMatching(a, b, ...)` probiert Alternativen in der angegebenen Reihenfolge. Beides lässt sich verschachteln. Ein Zweig muss auch die nachfolgenden Kriterien erfüllen: Ein optionaler String verbraucht beispielsweise nicht das einzige Argument, wenn danach eine Pflichtzahl benötigt wird.

Fehlgeschlagene Zweige hinterlassen keine Werte, Hinweise oder Argumentpositionen. Überzählige Eingaben und ungültige angegebene optionale Werte führen zu Fehlern. Die Auswertung ist auf 2048 Schritte begrenzt; außerdem gelten Grenzen für Schemagröße, Verschachtelung, Eingabelänge und Tokenzahl.

## Benannte Optionen

```kotlin
import labs.cerberus.ts.command.Commands.argumentSpec

commandSpec("inspect-options")
    .arguments(
        argumentSpec("client", "c", Client::class.java),
        argumentSpec().optional(argumentSpec("details", "d", Boolean::class.javaObjectType))
    )
    .executor { ctx -> /* ctx.required<Client>("client") */ }
    .build()
```

Beispiele: `!inspect-options --client=c:7 --details`, `!inspect-options -c c:7 -d false`. Unterstützt werden `--name=value`, `--name value`, Shorthands und boolesche Flags ohne expliziten Wert. String-Werte, die wie Optionen aussehen, quoten oder mit `=` angeben. Für positionale Befehle macht `--` alle folgenden Tokens zu Werten. Quotes und Escapes bleiben als Tokenherkunft erhalten; ein vollständig gequotetes `"--name"` wird nicht als Option ausgewertet.

Ein Befehl verwendet entweder benannte Optionen oder positionale Parameter. Unbekannte Optionen, doppelte Long-/Short-Angaben derselben Option, gemischte Eingaben und fehlende Optionswerte werden abgewiesen. Doppelte Kriteriennamen sind ungültig. Geteilte Shorthands dürfen registriert werden, können bei Mehrdeutigkeit aber nicht verwendet werden; vollständige Namen funktionieren weiter. Optionen werden nicht zu gebündelten Kurzflags wie `-abc` aufgeteilt.

## Verfügbare Typen

- `String`, primitive/geboxte Zahlen, `BigInteger`, `BigDecimal`, endliche `Float`-/`Double`-Werte, `Boolean`, `UUID` und `Locale`.
- `Client`: `c:<Client-ID>`, `db:<Datenbank-ID>`, eine alleinstehende Datenbank-ID, `uid:<UID>`, übliche TS-UIDs oder ein eindeutiger Namensbestandteil. Query-Clients werden übersprungen.
- `Channel`: Channel-ID, `cid:<ID>` oder eindeutiger Namensbestandteil.
- `TeamSpeakUser`: `db:<Datenbank-ID>`, `c:<Online-Client-ID>`, `uid:<UID>`, übliche TS-UIDs oder ein Namenssuchmuster. Die Daten stammen aus der TeamSpeak-Datenbank und funktionieren auch für Offline-Benutzer.
- `PermissionGroup` und `PermissionSubject`: vorhandene IDs des aktuellen Framework-Rechtemodells. Die vollständige Migration der alten UUID-/Provider-Struktur bleibt unter P08/P25 offen.

Client-, Channel- und Benutzermatcher ergänzen `<Kriterienname>Id`. Mehrdeutige Suchen werden abgewiesen. `UserService` ist auch direkt verfügbar und prüft Serverfehler; Datenbanksuchen sind standardmäßig auf 100 Ergebnisse begrenzt. SQL-Suchmuster in Nickname-Abfragen werden vom Server ausgewertet. Verbindungs- und Rechtefehler werden nicht als leere Trefferliste verschluckt; Fehler 1281 bedeutet eine leere Datenbankantwort.

`Locale` akzeptiert normalisierte Sprach-Tags; `unset` wird als `xx`-Resetwert abgebildet. Die Locale-Befehle verstehen zusätzlich `reset`, `XX` und `unset`.

## Eigene Matcher

```kotlin
data class ProjectKey(val value: String)

registerMatcher(ProjectKey::class.java) { context, input ->
    if (input.matches(Regex("[A-Z]{2}-[0-9]+"))) {
        MatchResult.Success(ProjectKey(input))
    } else {
        context.incompatible(input)
    }
}
```

Alternativ `CriterionMatcher<T>` mit `supportedType` und `tryMatch(context, input)` implementieren. Plugin-Helfer injizieren Services und Locale-Units in solche Matcherobjekte. Java kann die Builder statisch aus `labs.cerberus.ts.command.Commands` importieren; `CommandPluginTest` kompiliert und lädt dafür ein echtes Java-Plugin-JAR.

Matcher müssen nebenwirkungsfreie Auflösungen liefern: Alternative oder optionale Zweige können mehrfach versucht werden. `MatchingContext.values` enthält die vorher gebundenen Werte als unveränderlichen Snapshot. Erfolgreiche Ergebnisse können zusätzliche Werte und Hinweise enthalten. Hinweise werden nur für den gewählten erfolgreichen Pfad übernommen. Ein Rejected-Ergebnis führt keinen Executor aus. Zusätzliche Werte dürfen keine Kriterien oder vorherige Werte überschreiben. Falsche Ergebnistypen werden abgefangen.

Die Registry normalisiert primitive Typen. Exakt registrierte Typen haben Vorrang; bei mehreren passenden abgeleiteten Typen ist eine explizite Registrierung nötig. Neue Registrierungen können vorherige überlagern. Beim Schließen eines Handles wird die vorherige Registrierung wieder sichtbar; bereits geschlossene alte Handles können neuere nicht entfernen. Pro Auswertung wird ein konsistenter Matcher-Snapshot verwendet.

## Rechte, Aliases und Lebenszyklus

`permission(id, minimum)` verlangt einen positiven Mindestwert. Die Prüfung erfolgt vor den Matchern und nochmals vor dem Executor anhand frischer Absender-/Servergruppen-Daten. Antworten prüfen erneut die UID, damit ein während der Auswertung wiederverwendeter Client-Slot nicht die Antwort des alten Absenders erhält. Es gibt dabei keine serverseitige atomare Transaktion zwischen UID-Prüfung und Nachricht.

Namen und Aliases werden gemeinsam registriert. Kollisionen lassen bestehende Befehle unverändert. Der Low-Level-Aufruf `CommandService.registerCommand(spec, replace = true)` ersetzt explizit kollidierende Registrierungen und entfernt deren übrige Aliases. Standardmäßig wird keine Registrierung verdrängt. `unregisterCommand(spec)` vergleicht die Spezifikationsinstanz; Registrierungs-Handles beeinflussen keine Ersatzregistrierung.

`getCommands()` und `getCommand(nameOrAlias)` liefern registrierte typisierte Spezifikationen. `help <name>` zeigt ihre Signatur. Die Hilfe berücksichtigt die Mindest-Rechte. Framework-Nachrichten für fehlende, inkompatible und mehrdeutige Matcherwerte verwenden die persistente Locale-Unit `commandService`, einschließlich UID-Sprachpräferenzen. Eigene Matcher können `context.message(...)` verwenden.

`CommandParityTest` prüft die alten gültigen Command-v2-Szenarien sowie Korrekturen für fehlerhafte Altpfade. Ganze Rechteprovider, die alte Daten-/Cache-API und unveränderte alte Plugin-JARs sind damit noch nicht vollständig migriert. Eine reine Funktionsparität des Matcher-Kerns ist kein Nachweis für die übrigen Bereiche.
