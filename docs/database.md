# Datenbanken und Transaktionen

`DatabaseService` verwaltet benannte JDBC-Datenquellen und Hibernate-PersistenceUnits. Beim Botstart werden `.yml`-, `.yaml`- und `.json`-Dateien unter `<data.directory>/database/` gelesen. Der Dateiname ohne Endung ist die Unit-ID; `id` kann sie explizit festlegen. Alle Konfigurationen werden vor dem ersten Verbindungsaufbau gelesen. Ein Initialisierungsfehler entfernt und schließt die in diesem Ladevorgang bereits angelegten Units.

## Konfiguration

Beispiel `data/database/default.yml`:

```yaml
jdbcUrl: jdbc:mariadb://127.0.0.1:3306/cerberus
username: cerberus
password: 'BETREIBER_PASSWORT'
pool:
  maximumPoolSize: 4
  minimumIdle: 0
  connectionTimeout: 10000
hibernate:
  hibernate.hbm2ddl.auto: validate
```

MariaDB Connector/J 4.0.0 ist enthalten. Andere JDBC-Treiber können im Plugin-JAR bereitgestellt werden; gegebenenfalls `pool.driverClassName` setzen. Die Schemaerstellung ist ohne entsprechende Hibernate-Einstellung deaktiviert. `create`, `create-drop` oder `update` nur verwenden, wenn deren Schemaänderungen beabsichtigt sind.

Jeaks JSON-Schlüssel `url`, `user`, `pass` und `dataSourceOpts` werden ebenfalls verstanden. Ohne URL wird aus `driver`, `host`, `port` und `schema` eine URL zusammengesetzt. Alte Datenbankinhalte und `javax.persistence`-Entities werden dadurch nicht automatisch migriert. Eigene Entities auf `jakarta.persistence` umstellen und neu kompilieren.

Die Unit verwendet ihren eigenen Hikari-Pool. Standard: maximal vier Verbindungen, keine Mindestzahl ungenutzter Verbindungen, zehn Sekunden Wartezeit auf eine freie Verbindung. Unter `pool` gelten Hikari-Einstellungen, unter `hibernate` Hibernate-Einstellungen. Verbindungseinstellungen dürfen den verwalteten Pool nicht ersetzen.

## Entities und Plugin-Lebenszyklus

Nach `onLoad()` aller Plugins und vor ihrem `onEnable()` durchsucht der Bot die Plugin-JARs nach `jakarta.persistence.Entity`, `Embeddable`, `MappedSuperclass` und `Converter`. Die Erkennung liest Bytecode und führt keine statischen Initialisierungen aus. Hibernate kann die Klassen beim anschließenden Aufbau seiner Metadaten initialisieren. Mit `entities: [example.MyEntity]` lässt sich die Menge der gemappten Klassen einer Unit explizit festlegen.

```kotlin
import jakarta.persistence.Entity
import jakarta.persistence.Id
import labs.cerberus.ts.service.DatabaseService

@Entity
open class Note(@field:Id open var id: Long = 0, open var text: String = "")

// Beispielsweise in onEnable():
val database = getService(DatabaseService::class.java)
database.withEntityManager("default") { manager ->
    manager.persist(Note(1, "Community gestartet"))
}
```

Entities benötigen einen geeigneten parameterlosen Konstruktor und für Hibernate-Proxies erweiterbare Klassen/Eigenschaften. Alternativ die entsprechenden Kotlin-JPA-/All-open-Compilerplugins im eigenen Plugin-Projekt konfigurieren.

## Transaktionsvertrag

- Verschachtelte Aufrufe derselben Unit im selben Thread verwenden denselben EntityManager und dieselbe Transaktion. Nur der äußerste Aufruf beginnt und bestätigt sie.
- Ein innerer Fehler markiert die gesamte Transaktion für Rollback. Auch wenn der Plugin-Code den Fehler abfängt, verhindert `RollbackOnlyException` ein äußeres Commit.
- Fehler werden nach dem Rollback weitergegeben. `withEntityManager(action, onError)` gibt bei Fehlern `null` zurück; der Callback sieht den abgeschlossenen Rollback und die freigegebene Verbindung. Bei einem inneren Aufruf läuft sein Callback erst an der äußeren Transaktionsgrenze.
- Verschiedene Threads und verschiedene Units verwenden getrennte Transaktionen. Es gibt keine verteilten Transaktionen über mehrere Units.
- `database.transaction("default") { connection -> ... }` innerhalb einer ORM-Unit nutzt dieselbe physische Verbindung und flusht vorher ausstehende ORM-Änderungen. Den übergebenen Connection-/EntityManager nicht selbst committen, zurückrollen oder schließen. JDBC-Änderungen aktualisieren bereits geladene ORM-Objekte nicht automatisch; bei Bedarf `refresh`/`clear` verwenden.
- Außerhalb von ORM-Units lassen sich vorhandene `DataSource`s über `register(name, source)` nutzen. Die Registrierung besitzt die übergebene Quelle nicht; der Aufrufer schließt sie selbst.
- Manuell über `unit.entityManager` geöffnete Manager nach Gebrauch schließen. Die Unit verfolgt diese Manager und schließt verbliebene Exemplare beim Herunterfahren; offene Transaktionen werden zurückgerollt.

## Annotationen

```kotlin
@EventHandler
@Transactional(unit = "default")
fun onCommunityEvent(event: CommunityEvent) {
    database.withEntityManager { manager -> /* Änderung */ }
}
```

`labs.cerberus.ts.service.Transactional` wirkt auf durch den Framework-EventBus aufgerufene `@EventHandler`-/`@Subscribe`-Methoden sowie auf registrierte REST-Controller-Methoden. REST-Parameter werden vor Beginn gebunden; Commit/Rollback erfolgt vor der Antwort. `orm = false` unterstützt registrierte reine JDBC-Quellen. Normale direkte Methodenaufrufe und Selbstaufrufe werden nicht abgefangen. EventBus und RestService benötigen dafür den DatabaseService; die Bot-Zusammensetzung stellt ihn automatisch bereit.

## Freigabe und Prüfungen

`removeUnit(id)` beendet laufende Transaktionen, schließt Pool und ORM-Ressourcen und gibt den Namen zur erneuten Registrierung frei. Schließen aus der eigenen laufenden Transaktion wird abgewiesen. Beim Bot-Shutdown schließen erst Plugins und ihre Aufgaben, danach die Datenbanken. Transaktionsblöcke müssen zurückkehren; ein dauerhaft blockierter Plugin-Aufruf kann die Freigabe verzögern.

`DatabaseParityTest` prüft echte H2-/Hibernate-Transaktionen, verschachtelten Rollback, Callback-Reihenfolge, JDBC-/ORM-Zusammenspiel, Thread-/Unit-Isolation, Konfigurationsfehler und annotierte HTTP-/Event-Aufrufe. `PluginPersistenceTest` baut ein Java-Plugin-JAR und prüft Entity-Erkennung und Startreihenfolge. Eine externe MariaDB-Instanz wurde noch nicht geprüft.

Grundlagen: [Hibernate 7.4](https://docs.hibernate.org/orm/7.4/quickstart/html_single/), [HikariCP](https://github.com/brettwooldridge/HikariCP), [ClassGraph für JDK 17–24](https://github.com/classgraph/classgraph/tree/v5).
