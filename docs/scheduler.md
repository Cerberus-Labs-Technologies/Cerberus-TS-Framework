# Scheduler

Jeder Bot besitzt einen eigenen `TaskService`. Die Taskidentität ist das Taskobjekt; Namen dienen der Anzeige. Verschiedene Tasks dürfen denselben Namen haben. Ein erneutes `scheduleTask(task)` gibt bei einer laufenden Registrierung dasselbe Handle zurück.

```kotlin
val count = AtomicInteger()
val task = Task.builder()
    .name("community-refresh")
    .interval(10, TimeUnit.SECONDS)
    .initialDelay(Duration.ZERO)
    .runnable(Runnable { count.incrementAndGet() })
    .shouldReschedule { count.get() < 5 }
    .build()
val handle = getService(TaskService::class.java).scheduleTask(task)
own(handle)
```

Ein Builder-Reset verändert bereits gebaute Tasks nicht. `delay` setzt einen einmaligen Aufruf; `interval` einen wiederholten Aufruf mit derselben anfänglichen Wartezeit. `initialDelay` kann diese Wartezeit überschreiben. Verzögerungen dürfen null sein; Intervalle müssen positiv sein. Negative und in Nanosekunden nicht darstellbare Zeitspannen werden abgewiesen. Anders als der alte Sekunden-Countdown funktionieren auch null und kurze Intervalle.

Wiederholungen verwenden einen festen Abstand nach Abschluss des vorherigen Aufrufs. Derselbe Task überlappt sich nicht. Reguläre Ausnahmen werden gemeldet und verhindern weitere Wiederholungen nicht; Fehler in `shouldReschedule()` oder JVM-`Error`s beenden die Registrierung mit `FAILED`.

`rescheduleTask(task)` setzt die ursprüngliche Wartezeit zurück, `runTask(task)` fordert einen möglichst baldigen Aufruf an. `handle.reschedule(duration)` und `handle.runNow()` wirken nur auf ihre aktuelle Registrierung. Läuft der Task gerade, wird der neue Termin vorgemerkt; mehrere Anforderungen werden auf den letzten Termin zusammengeführt. Ein expliziter vorgemerkter Aufruf hat Vorrang vor der automatischen Wiederholungsentscheidung.

`snapshot` zeigt ID, Name, Ausführungsart, Status, Anzahl gestarteter Aufrufe, Fehleranzahl, letzten Fehler und nächsten Termin. `hasTask` gilt auch während eines laufenden Aufrufs; `snapshots()` enthält aktive Registrierungen und noch laufende abgebrochene Aufrufe. Abgeschlossene Historie wird nicht dauerhaft gespeichert.

## Abbruch und Ressourcen

`cancel()`/`close()` brechen die Registrierung dauerhaft ab und senden standardmäßig einen Interrupt an einen laufenden Aufruf. `cancel(false)` verzichtet darauf. Ignoriert Plugin-Code den Interrupt, läuft dieser Aufruf bis zu seiner Rückkehr weiter, wird aber nicht erneut eingeplant. `awaitTermination(duration)` wartet auf diese Rückkehr; Selbstwarten wird abgewiesen. Während der abgebrochene Aufruf noch läuft, darf dasselbe Taskobjekt nicht neu registriert werden. Danach ist eine neue Registrierung möglich; alte Handles können sie nicht beeinflussen.

`TaskService.close()` verwirft wartende Aufgaben und unterbricht laufende Aufrufe. Das Schließen wartet nicht unbegrenzt auf fremden Plugin-Code. Der Dienst nimmt danach keine Arbeit mehr an. Standardmäßig sind maximal 4096 gleichzeitige Registrierungen erlaubt; Timer und Worker besitzen begrenzte Arbeit bzw. entfernen verworfene Einträge.

## Paper-artige API

```kotlin
server.scheduler.runTaskLater(this, Runnable { /* serialisiert */ }, 20)
server.scheduler.runTaskTimer(this, Runnable { /* serialisiert */ }, 0, 200)
server.scheduler.runTaskAsynchronously(this, Runnable { /* unabhängig */ })
server.scheduler.runTaskTimerAsynchronously(this, Runnable { /* unabhängig */ }, 0, 200)
```

Die Zeitangaben sind wie bei Paper in 20 Ticks pro Sekunde. Reguläre Methoden verwenden einen gemeinsamen serialisierten Worker pro Bot. `runTaskAsynchronously`, `runTaskLaterAsynchronously` und `runTaskTimerAsynchronously` verwenden den getrennten Workerpool. Beide Ausführungsarten laufen im Botprozess; TeamSpeak stellt dafür keinen Server-Hauptthread bereit. Event-Callbacks und Query-Aufrufe werden nicht automatisch auf diesem Worker ausgeführt.

Paper-artige Registrierungen und `context.task(task)` gehören automatisch zum Plugin und werden beim Entladen abgebrochen. Ein bereits abgebrochener Task kann sich durch das Ende seines Aufrufs nicht wiederbeleben. `PluginTask` stellt `taskId`, `state`, `isCancelled`, `isCurrentlyRunning`, `snapshot` und `awaitTermination` bereit.

`TaskParityTest` und der Scheduler-/Entladetest in `PaperPluginTest` prüfen Builder-Snapshots, Objektidentität, Neuplanung ohne Überlappung, parallele/serialisierte Ausführung, Fehler, wartende und laufende Abbrüche, Wiederregistrierung und Plugin-Ressourcenfreigabe.
