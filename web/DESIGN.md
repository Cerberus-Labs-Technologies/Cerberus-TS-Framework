# Cerberus TS – Weboberfläche

Software: operative TeamSpeak-Verwaltung für den Serverbetreiber und dessen Plugins.

Taste-Auswahl: `white-link-blue`, Familjen Grotesk + IBM Plex Mono, weiche Radien. Weiß und neutrale Flächen halten Serverdaten lesbar; Blau markiert Aktionen. Quelle: [Taste 2.5.1](https://github.com/Leonxlnx/taste-skill/blob/952435f63acece7d2fcb4b4d3c1786c1c43603e0/skills/taste-skill/SKILL.md). Die Designreferenzen wurden aus dieser Revision gelesen; die separat erhaltene Classic-v1-Datei wurde nicht als abweichender Skill aktiviert.

Der Auftrag ist eine Anwendung. Ihre Navigation, Tabellen, Channelhierarchie, Anmeldung und Plugin-Werkzeuge haben Vorrang vor den Landingpage-Rezepten des Skills (Vollbild-Hero, Marketingabschnitte, Footer, dekorative Effekte). Aus Taste übernehmen wir das gemeinsame Token-System, gleiche Inhaltskanten, konkrete Texte, lokale Schriften, zielgerichtete Interaktionen, Kontrast, Tastaturbedienung, Lade-/Leer-/Fehlerzustände und die Prüfung realer Browserdarstellung. Es gibt keine erfundenen Kundenzahlen oder Verlaufsdiagramme.

Stimme: technisch und direkt; Produkt und echte Serverzustände benennen. Formulare nennen Aktion und Ergebnis, Fehler erklären den fehlgeschlagenen Schritt. Die primäre Anmeldeaktion lautet „Mit Token anmelden“.

Ansichten:

- Anmeldung: Tokenfeld und Zugangshinweis; Prüfung gegen die echte Bot-API vor dem Öffnen.
- Übersicht: echte Verbindungszahlen, Channelhierarchie, ausgewählter Channel und laufende Musikbots.
- Clients: Suchfeld und aktuelle Benutzer/Voice-Bots; Query-Verbindungen ausgeblendet.
- Channels: Hierarchie, Belegung und Details; kein erdachter Verlauf.
- Plugins: geladene Plugins und ihre registrierten Seiten.
- Erweiterungen: vom Plugin gelieferte ES-Module und CSS mit Lebenszyklus und eigenen API-Namensräumen.

Desktop: feste Navigation links, gemeinsamer Kopf und Inhaltsbereich; auf 390/320 px eine Spalte und ein per Tastatur bedienbares Navigationspanel. Bewegung erklärt Hover, Klick und Öffnen; unter reduzierter Bewegung ruhen Animationen. Statusfarben haben eine semantische Funktion. Gegenüber den unveränderten shadcn-Vorlagen sind Farben, Typografie, Radien, Kontrollhöhen und Schatten angepasst.

Ausgangsnachweis: **GATE: FAIL 19 (round 1 of 3)**, keine Waiver. Anmeldung und Übersicht wurden bei fünf Breiten, die Welcome-Seite bei Desktop-/Mobilbreite aufgenommen und angesehen; 13 Live- und 20 lokale Browser-Szenarien bestehen. Alle offenen Gate-Befunde, die Grenzen des Layoutcheckers sowie Copy-/Performance-Ergebnisse stehen im [Prüfbericht](../docs/web-visual-validation.md). Der visuelle Abschluss bleibt offen.

Musiksteuerung: Bot-Auswahl und Verbindungsformular links, Player/Queue/Verbindung rechts; unter 1100 px eine Spalte. Formulare bleiben beim Polling stabil. Quellen und Sender-Metadaten werden als Text ausgegeben. Destruktive Aktionen benennen Queue-Verlust und Voice-Trennung. Die vorhandenen Farben, Schriften, Radien und Fokusmarkierungen gelten auch für die Plugin-Seite.

Verwaltung: Channels besitzen eigene Editier-, Positions- und Rechteformulare; die neue Gruppenseite trennt Server- und Channelgruppen durch zwei explizite Schalter. Listen und Editoren sind bei weniger als 1280 px gestapelt. Formularentwürfe bleiben während der Listenabfragen erhalten, Hilfetexte sind Beschreibungen und keine Bestandteile der Feldnamen. Das Löschen liegt in einem aufklappbaren Bereich mit benannten Folgen. Normale Gruppen sind editierbar; Query- und Vorlagengruppen zeigen ihren Schreibschutz. Funktions- und Darstellungsnachweise dieser Ergänzung werden in docs/web-administration-validation.md dokumentiert.
