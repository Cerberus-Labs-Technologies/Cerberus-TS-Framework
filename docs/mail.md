# SMTP-Transporte und Benachrichtigungen

Der Bot lädt beim Start alle `.json`-, `.yml`- und `.yaml`-Dateien aus `<data.directory>/mail`. Jede Datei beschreibt einen benannten Transport. Die Registrierung erfolgt vor dem Laden der Plugins: `@TransportUnit` steht daher bereits in `onLoad()` und späteren injizierten Listenern/Controllern zur Verfügung. Das Laden stellt noch keine Verbindung zum Mailserver her. Fehlt das Verzeichnis, werden keine SMTP-Units angelegt.

```yaml
# data/mail/default.yml
host: smtp.example.test
port: 587
from: bot@example.test
username: bot@example.test
password: 'hier-das-SMTP-Passwort-eintragen'
tls: STARTTLS
properties:
  mail.smtp.connectiontimeout: 10000
  mail.smtp.timeout: 10000
  mail.smtp.writetimeout: 10000
```

Der Name ergibt sich aus dem Dateinamen ohne Endung; optional legt `id` einen eigenen Namen fest. Die Beispieladresse ist ein Platzhalter für den tatsächlichen SMTP-Anbieter. Konfigurationsdateien liegen im privaten Datenverzeichnis und werden nicht durch die Weboberfläche ausgeliefert.

## Jeak-Dateien übernehmen

Alte Jeak-Dateien aus dem bisherigen `conf/mail`-Verzeichnis können unverändert in das neue `data/mail`-Verzeichnis kopiert werden. JSON bleibt JSON; YAML ist zusätzlich möglich. Ein automatisches Verschieben sämtlicher alter Botdaten ist damit nicht verbunden.

Unterstützte Schlüssel:

| Schlüssel | Bedeutung / Auswahl |
| --- | --- |
| `host` | Standard `localhost` |
| `port` | Für geladene Units wie bei Jeak standardmäßig `25`; `MailService` als direkt konstruierte API verwendet weiterhin `587` |
| `from` | Absender im Nachrichtenkopf; Standard `mail@localhost` |
| `username`, `user` | SMTP-Benutzer; `username` geht vor |
| `pass`, `password` | SMTP-Passwort; wie im Altstand geht `pass` vor. Es gibt kein implizites Standardpasswort |
| `starttls` | Alter boolescher Schlüssel; Standard `true` |
| `implicitTls` | TLS bereits beim Verbindungsaufbau; alternativ den expliziten Modus verwenden |
| `tls` | Expliziter Modus `STARTTLS`, `IMPLICIT` oder `NONE`; geht den beiden obigen Schaltern vor |
| `properties` | Flache Map mit JavaMail-/Angus-Eigenschaften; Werte dürfen Strings, Zahlen und boolesche Werte sein |

`properties` überschreibt die resultierenden SMTP-Eigenschaften, beispielsweise Host, Port, Authentifizierung, Benutzer und Passwort. `mail.smtp.from` ist der SMTP-Envelope-Absender, etwa eine Bounce-Adresse; der `From`-Nachrichtenkopf stammt aus `from` bzw. einem validierten `From`-Override der Nachricht. Ohne expliziten Envelope-Absender verwendet Angus die `From`-Adresse, auch bei gesondertem `Sender`-Header. Die Eigenschaften sind in der [offiziellen Angus-SMTP-Dokumentation](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html) beschrieben.

Standardmäßig sind STARTTLS verpflichtend und die Prüfung des Servernamens aktiviert. Ein Server ohne STARTTLS erhält weder Zugangsdaten noch Nachricht. Dies korrigiert Jeaks opportunistisches Standardverhalten. `starttls: false` bzw. `tls: NONE` wählt ausdrücklich unverschlüsselten SMTP-Versand. Explizite Properties bleiben wirksam: Wird `mail.smtp.starttls.enable` gesetzt, folgt der Standard für `mail.smtp.starttls.required` diesem Wert; ein ausdrücklich gesetztes `required` geht vor. TLS-Schalter und Timeouts werden auf gültige Werte geprüft. Authentifizierung benötigt Benutzer und explizites Passwort.

## Nutzung aus einem Plugin

```kotlin
import labs.cerberus.ts.service.MailMessage
import labs.cerberus.ts.service.MailTransport
import labs.cerberus.ts.service.TransportUnit

@field:TransportUnit(name = "default")
lateinit var mail: MailTransport

fun sendNotice(address: String) {
    val message = MailMessage.builder()
        .to(address)
        .addSubjectText("Servernachricht")
        .addText("Deine Anfrage wurde verarbeitet.")
        .build()
    mail.dispatch(message).whenComplete { _, error ->
        if (error != null) logger.warning("SMTP-Versand fehlgeschlagen: ${error.javaClass.simpleName}")
    }
}
```

Die Felder und Methode gehören zu einem `JavaPlugin`. `send` arbeitet synchron; `dispatch` liefert ein `CompletableFuture` mit Erfolg oder Fehler. Der konkrete SMTP-Transport verwendet einen eigenen Worker und eine begrenzte Warteschlange. `MailMessage` unterstützt TO/CC/BCC, Plaintext, HTML, Header und binäre Anhänge. Nachrichten und Empfängerlisten werden beim Erstellen festgehalten. Der Builder dedupliziert wiederholt hinzugefügte Empfänger pro Empfängertyp und identische Anhangsobjekte; `header(name, null)` entfernt einen zuvor gesetzten Header. Headernamen werden dabei ohne Beachtung der Groß-/Kleinschreibung ersetzt oder entfernt. `From` und `Sender` dürfen pro Nachricht überschrieben werden; die Adressen werden validiert und Anzeigenamen MIME-kodiert. Empfänger, Betreff und MIME-Struktur werden über die jeweiligen Nachrichtenfelder statt über reservierte Header gesetzt. `MailAttachment.fromPath`/`fromFile` prüfen beim Anlegen, ob die Quelle eine lesbare reguläre Datei ist; das Lesen erfolgt beim Versand. BCC-Empfänger erscheinen nicht im übertragenen Nachrichtenkopf.

Für die bestehende Profilbenachrichtigung eine Unit `mail-notifications` anlegen. Der Kanal `mail` verwendet die Profiloption `notifications:email` und die persistente TeamSpeak-UID des Empfängers. Kurz-/Langtext, Betreff, Dringlichkeit und Lebensdauer werden weiterhin vom Benachrichtigungsdienst ausgewertet.

## Registrierung und Lebenszyklus

`MailTransports.load(path)` lädt zusätzliche Verzeichnisse atomar: Erst werden alle Dateien gelesen und alle Transporte erzeugt, anschließend werden sie gemeinsam registriert. Doppelte Namen oder eine ungültige Datei lassen die bisherigen Registrierungen bestehen; in diesem Ladeversuch erzeugte Transporte werden geschlossen. Syntaxfehler werden ohne Abdruck des Datei-Inhalts oder Passworts gemeldet. Ein fehlender Transport führt bei Injection zu einem Fehler und wird nicht stillschweigend durch einen anderen ersetzt.

`unitIds()` liefert einen unveränderlichen Namens-Snapshot. `remove(name)` entfernt die Registrierung und schließt einen konfigurierten Transport. `close()` schließt sämtliche konfigurierten Units, beendet wartende Dispatch-Futures und räumt aktive SMTP-Verbindungen auf. Der Bot erledigt dies beim Stoppen und auch nach einem fehlgeschlagenen Start. Nach dem Schließen kann dieselbe Registry nicht wiederverwendet werden.

Transporte, die ein Plugin über `register(name, transport)` bereitstellt, gehören weiterhin dem Aufrufer. Das Registrierungshandle entfernt ausschließlich seine eigene Registrierung, auch nach mehrfachem Schließen und Wiederregistrierung desselben Transportobjekts. Der Aufrufer ordnet eine eigene `AutoCloseable`-Implementierung zusätzlich seinem Plugin über `own(...)` zu.

## Nachweise und Grenzen

`MailParityTest` prüft den tatsächlichen lokalen SMTP-Dialog einschließlich CC/BCC, MIME und Anhängen. `MailConfigurationTest` prüft geladene JSON-/YAML-Units, alte Schlüssel und Properties, echte STARTTLS-/implizite TLS-Handshakes, Authentifizierung erst nach Verschlüsselung, Ablehnung nicht vertrauenswürdiger Zertifikate und falscher Servernamen, atomare Ladefehler sowie Ressourcenfreigabe. Die Zertifikate werden dafür in einem temporären Verzeichnis mit dem JDK-keytool erzeugt; der Test verändert keinen globalen Truststore.

`PluginMailTest` kompiliert ein echtes Java-Paper-Plugin-JAR, startet es über den Bot, prüft die frühe Injection und den tatsächlichen SMTP-Versand. Es prüft außerdem einen fehlgeschlagenen Start und den Profil-Mailkanal mit einem echten lokalen SMTP-Dialog. Diese lokalen Nachweise ersetzen weiterhin keinen Test gegen einen externen SMTP-Anbieter.
