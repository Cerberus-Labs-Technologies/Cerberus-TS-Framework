# Server-Setup

Das optionale Plugin `server-setup` richtet einen Community-Server beim Aktivieren ein. Es erstellt und bearbeitet Channels, Servergruppen und Channelgruppen; aus der Konfiguration entfernte eigene Objekte werden wieder gelöscht, sofern sie unbenutzt sind.

```sh
./gradlew :examples:server-setup-plugin:build
cp examples/server-setup-plugin/build/libs/server-setup-plugin.jar plugins/
```

`plugin.directory` kann einen anderen Plugin-Ordner festlegen. Nach dem nächsten Bot-Start liegt die editierbare Konfiguration unter `<data.directory>/plugins/server-setup/config.yml`. Das Plugin verwendet die Paper-artige API mit `JavaPlugin`, `plugin.yml`, `saveDefaultConfig()` und `getCommand(...).setExecutor(...)`.

## Vorgeschlagener Aufbau

Der Server erhält den Namen **Cerberus Labs | Community** und eine Begrüßung. Die 14 neuen Channels werden hinter vorhandenen Root-Channels eingeordnet:

```text
── COMMUNITY ──
  Lounge
  Talk 1
  Talk 2
── GAMING ──
  Gaming 1
  Gaming 2
  Duo                    (2 Plätze)
── MUSIK & PAUSE ──
  Radio Lounge           (Opus Music, Qualität 8)
  AFK                    (benötigte Talk-Power 75)
── SUPPORT & TEAM ──
  Support · Warteraum
  Team · Intern          (Join- und Subscribe-Power 50)
```

Servergruppen: **CL | Leitung**, **CL | Moderation**, **CL | Mitglied**, **CL | Musikbot**. Leitung basiert auf der eingebauten Server-Admin-Vorlage und enthält deren Administratorrechte. Moderation erhält Power 60 zum Betreten/Abonnieren, Verschieben und Kicken. Der Musikbot darf seine eigene Beschreibung und Avatare bis 64 kB setzen. Channelgruppen: **CL | Channel-Leitung**, **CL | Channel-Moderation**, **CL | Sprecher**.

Gruppen werden aus eindeutigen **Typ-0-Vorlagen** kopiert und ausschließlich als normale **Typ-1-Gruppen** erstellt. Vorlagen und Query-Gruppen bleiben erhalten. Die Vorlage gilt beim Erstellen; ein späterer Wechsel von `template` ersetzt bestehende Gruppenrechte nicht. Rechteänderungen an bestehenden Gruppen über `permissions` konfigurieren.

Es gibt keine automatischen Gruppenzuweisungen an Menschen oder Voice-Bots. Bestehende Rollen, Standard-Channels und der aktuelle Aufenthaltsort des Musikbots werden nicht geändert. Die Radio Lounge ist als Ziel für einen späteren `!music move main <cid>` vorbereitet.

## Konfiguration und Befehle

Die YAML-Schlüssel sind stabile IDs innerhalb des Plugins. Für eine Umbenennung bleibt beispielsweise `lounge` bestehen, während nur `name` geändert wird:

```yaml
apply-on-start: true
prune: true
server:
  virtualserver_name: 'Meine Community'
channels:
  community:
    name: 'Community'
  lounge:
    name: 'Plauderecke'
    parent: community
    properties:
      channel_topic: 'Für alle willkommen'
    permissions:
      i_channel_needed_join_power: 0
server-groups:
  member:
    name: 'Mitglied'
    template: 'Normal'
    permissions:
      i_group_sort_id: 30
channel-groups:
  operator:
    name: 'Channel-Moderation'
    template: 'Operator'
    permissions:
      i_group_sort_id: 20
```

- `!setup status`: letzter Lauf und aktueller Bearbeitungsstatus.
- `!setup plan`: YAML neu laden, validieren und geplante Änderungen ohne Serveränderungen anzeigen.
- `!setup apply`: YAML neu laden und abgleichen.
- Alias: `!einrichtung`. In der lokalen Konsole das `!` weglassen.

Die Befehle erfordern das Framework-Recht `cerberus.setup.admin`. Die lokale Bot-Konsole besitzt es automatisch. Von dort lässt es sich gezielt mit `grant <UID> cerberus.setup.admin 1` vergeben. TeamSpeak-Gruppen berechtigen nicht automatisch zu Framework-Befehlen.

`apply-on-start: false` deaktiviert den automatischen Lauf. `prune: false` behält entfernte Einträge. Zum Entfernen aller Einträge einer Kategorie ausdrücklich `channels: {}`, `server-groups: {}` bzw. `channel-groups: {}` setzen; ein fehlender Abschnitt verwendet die gebündelten Defaults. Neue Channels sind permanent. Reihenfolge und Hierarchie folgen der YAML. Es werden nur die angegebenen Channel-Eigenschaften verwaltet; entfernte Eigenschaften wie ein Topic behalten ihren bisherigen Wert, bis ausdrücklich ein neuer Wert gesetzt wird. Die Beschreibung enthält zusätzlich eine Eigentumsmarkierung. Aus `permissions` entfernte, zuvor vom Plugin verwaltete Rechte werden entfernt; andere Rechte bleiben erhalten. Das gilt auch, wenn ein verwaltetes Recht ursprünglich aus einer Gruppenvorlage stammte.

## Zustand und Löschschutz

`state.yml` bindet Objekt-IDs an die Server-UID und diese Plugin-Installation. Channels tragen zusätzlich eine Markierung in ihrer Beschreibung. Fehlende oder fremde Markierungen sowie abweichende Gruppennamen/-typen stoppen den Lauf vor Änderungen. Namenskollisionen werden nicht automatisch übernommen. Fehlende eigene Objekte können neu angelegt werden. `state.yml` erhalten und zusammen mit `config.yml` sichern; ohne Zustand übernimmt das Plugin keine vorhandenen Objekte.

Löschungen betreffen ausschließlich IDs aus dem eigenen Zustand und verwenden immer `force=0`. Belegte oder zum Standard gemachte Channels, Eltern mit verbleibenden Unterchannels sowie zugewiesene Gruppen bleiben erhalten und erscheinen als zurückgestellte Löschungen. Ein späterer Lauf versucht sie erneut. Es gibt kein `permreset`, kein rekursives Zwangslöschen und keinen Reset der Standardrollen. Dateien in einem zum Löschen freigegebenen eigenen Channel werden nicht separat gesichert.

Der erste ausführende Lauf sichert Servername/Begrüßung und Channel-/Gruppeninventar in `initial-inventory.yml`. Das ist eine Bestandsaufnahme, kein vollständiges TeamSpeak-Backup mit Mitgliedschaften, Dateien oder sämtlichen Gruppenrechten. Der Abgleich ist keine serverseitige Transaktion: Erfolgreiche Objekte werden sofort protokolliert; nach einem Fehler kann erneut abgeglichen werden. Geht eine Create-Antwort nach erfolgreichem Anlegen verloren, verhindert eine Namenskollision das automatische Übernehmen; diesen Sonderfall muss ein Administrator prüfen. Ein Dateilock schützt denselben Zustandsordner vor gleichzeitig laufenden Setup-Prozessen.

Der Query-Account braucht Lese-/Änderungsrechte für Serveranzeige, Channels, Gruppen, Vorlagen, Permissions und Mitgliedschaftslisten. Fehlende Rechte führen zu einem gemeldeten TS-Fehler; das Plugin verleiht dem Query-Account selbst keine Rechte. Die Arbeit läuft auf einem eigenen Worker, damit die Command- und Musikverarbeitung weiterlaufen kann.

## Protokollprüfung

Die Parameter sind gegen die mitgelieferte Dokumentation im offiziellen [TS6-beta14-Archiv](https://github.com/teamspeak/teamspeak6-server/releases/tag/v6.0.0-beta14) und die Hilfe des Zielservers abgeglichen. Insbesondere verwendet `servergroupcopy` die Parameter `ssgid`/`tsgid`, `channelgroupcopy` dagegen `scgid`/`tcgid`. Leere Rechte- oder Mitgliederlisten mit Fehler 1281 werden als leere Ergebnisse behandelt. Nach Channel-Erstellung und Verschieben wird die tatsächliche Reihenfolge neu gelesen: TS6 kann redundante `channelmove`-Befehle mit Fehler 770 ablehnen.
