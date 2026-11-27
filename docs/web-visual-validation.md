GATE: FAIL 19 (round 1 of 3)

# Weboberfläche: Prüfstand vom 27. November 2026

Das Gate ist nicht bestanden; es gibt keine Waiver. Der Gesamtauftrag und der visuelle Abschluss bleiben offen. Dieser Bericht hält die tatsächlichen Nachweise fest und ersetzt die Jeak-Paritätsmatrix nicht.

Kind: operative Software zur TeamSpeak-Verwaltung. Taste 2.5.1 aus der in [DESIGN.md](../web/DESIGN.md) verlinkten Revision; Palette white-link-blue, Familjen Grotesk und IBM Plex Mono, weiche Radien. Die neutralen Flächen halten Serverdaten lesbar; Blau bezeichnet Aktionen. Der Auftrag verlangt eine Anwendung. Deshalb wurden weder Marketing-Hero noch dekorative Effekte, Moments oder Szenen hinzugefügt. Das ist eine begründete Abweichung vom Landingpage-Rezept, keine bestandene Gate-Ausnahme.

Stimme: technisch, direkt, deutsch; echte Serverzustände und konkrete Aktionen. Kein neuer Produktname wurde in dieser Oberflächenphase erfunden, keine Preise, Kundenzahlen, Fotos oder Produktbilder angenommen. Es gibt keine Formulare mit .example-Zielen und keinen öffentlichen kanonischen Domainnamen. Zusätzliche Fotos oder Kundenmaterialien sind für die Verwaltungsansichten nicht erforderlich.

## Funktionsnachweise

- Vollständiger Build: 146 Tests erfasst, 145 bestanden, ein explizit deaktivierter Live-Test, keine Fehler.
- [13 Browser-Szenarien gegen echte TS6-Daten](validation/web-browser-live.json), mit separater lesender Query-Sitzung ohne Plugins.
- [20 Browser-Szenarien mit lokalem Query-Fixture und tatsächlicher Welcome-JAR](validation/web-browser-fixture.json): Speichern, eingeschränktes Token, verzögerte Einbindung, Cleanup-/Mount-/Stylesheet-Fehler und Abbruch von Requests beim Seitenwechsel. Der letzte Lauf prüft auch sichtbaren Tastaturfokus und den unbekannten Status in der Verbindungskarte.
- Navigation und horizontale Dokumentgrenzen bei 1440, 1024, 820, 768, 390 und 320 Pixeln; Escape und Fokusrückgabe des mobilen Menüs.
- Distributionstest gegen lokalen Query-Partner: Start, Login, Plugin-Befehl und geordnetes Stoppen bestanden.

## Darstellungsnachweise und Grenzen

Anmeldung und angemeldete Übersicht wurden mit dem unveränderten Taste-Screenshot-Skript bei 1440/1024/820/768/390 Pixeln aufgenommen und angesehen. Die Welcome-Seite wurde bei 1440/390 Pixeln aufgenommen und angesehen. Die Bilder liegen lokal unter `web/shots/`; sie zeigen ausdrücklich die beschriftete lokale UI-Testinstanz. Clients, Channels und Plugins wurden funktional geprüft, aber nicht als vollständige eigene Screenshot-Matrix bewertet. Der letzte anschließende Codewechsel betrifft die Fehleranzeige der Verbindungskarte; dieser Fehlerzustand ist durch den letzten Browsertest, nicht durch die normalen Übersichtsaufnahmen nachgewiesen.

Der Aufnahmehelfer verwendet frische Profile und meldet sich über das normale Formular im selben Taste-Browser an. Ein zuerst wiederhergestellter alter Tab führte zu falschen Tablet-Aufnahmen; diese wurden ersetzt und erneut angesehen. Ein nach dem Browserende zurückgebliebener Nodeprozess wird ausschließlich nach gespeichertem Bild und bestätigter Browsertrennung beendet. Kein Prüftool wurde geändert.

Die [erste vollständige Konsistenzprüfung über fünf Breiten](validation/web-consistency-initial.json) meldete 97 FAIL und 13 WARN, ohne Probe-Fehler. Sie fand unter anderem den fehlenden sichtbaren Input-Fokus; dieser wurde korrigiert und im echten Browser geprüft. Das [anschließende Gate](validation/web-gate.json) dedupliziert seine Befunde und meldet 19 offene FAIL und fünf WARN. Seine [Konsistenzdaten](validation/web-consistency-gate.json) gehören zum abschließend gebauten Stand. Es gibt keinen PASS durch Entfernen oder Umgehen von Prüfungen.

Der separat mit `--shots` gestartete Layoutchecker brach bei `res.sections.map` mit einem internen Fehler ab. Das spätere Gate lief durch und lieferte den folgenden tatsächlichen Status. Der eigenständige Layoutlauf gilt daher nicht als bestanden.

Der Copy-Linter auf dem vom Gate gerenderten HTML: 65 Wörter, 0 FAIL, 0 WARN. Die [Performanceprüfung](validation/web-perf.json) bei 1440/390 Pixeln mit je drei Läufen: 0 FAIL, zwei WARN; etwa 446 KiB pro Seitenladung, sieben Requests und Layout Shift 0.000. Die zwei Warnungen betreffen fehlende HTML-Breiten-/Höhenattribute an den Logo-Bildern. Ihre Größen sind bereits per CSS festgelegt; die Warnungen bleiben sichtbar dokumentiert. Die Prüfung betrifft die öffentliche Anmeldung, nicht jede authentifizierte oder künftige Plugin-Seite.

## Alle offenen Gate-Befunde

Die folgenden Meldungen sind unverändert aus dem Gate übernommen. Mehrere betreffen die Vorschrift, dass Marketingabschnitte jeweils einen ganzen Bildschirm füllen. Raster- und Kantenbefunde bleiben ebenfalls offen und werden nicht pauschal als falsch markiert.

1. **hero-fill** — `main#login > section.login-form-section "Am Bot anmelden"`; Breiten: 1440, 390.
   the next section shows on the first screen: it starts at 264px on a 900px screen (the hero ends at 635px)
   Vorschlag des Prüfers: let the hero fill the first screen alone (min-height: 100svh, less above it): nothing of the next section on load

2. **flush** — `section.login-copy > div.login-diagram > div.diagram-node.diagram-core > span "Cerberus TS"`; Breiten: 1440.
   section.login-copy and section.login-form-section run together: -369px between their content, on one ground
   Vorschlag des Prüfers: give the section its padding at the edge where the ground changes (the closing call keeps a clear gap above the footer)

3. **cta** — `main#login > section.login-copy`; Breiten: 1440, 390.
   the hero has no button (the nav's does not count)
   Vorschlag des Prüfers: add the action: a button in the hero, one on every priced item, one in the closing section

4. **off-grid** — `section.login-copy > div.login-diagram > div.diagram-node:nth-of-type(1) > span "TeamSpeak"`; Breiten: 1440.
   left edge at 137px, 7px off a common edge of its section at 130px (3 elements, like p.max-w-md.text-lg)
   Vorschlag des Prüfers: move it onto the page margin, or the column edge its section uses

5. **moment-none** — `(the page)`; Breiten: 1440.
   a software page (--type) deals no moment: every kind carries one, two on a long page (moments.md)
   Vorschlag des Prüfers: deal one (moments.mjs pick --page software) and mount it in its own section

6. **hero-screen** — `main#login > section.login-form-section "Am Bot anmeldenVerwende einen Zugriffsto"`; Breiten: 1440.
   the hero ends at 640px on a 900px screen, 260px above the fold: it ends at the fold, 1px either way (SR-1)
   Vorschlag des Prüfers: make the hero exactly one screen (height: 100svh, its content fitted inside), never taller or shorter

7. **hero-screen** — `main#login > section.login-copy "Cerberus TS 2.0TeamSpeak-Verwaltungfür d"`; Breiten: 1440.
   631px of the next section shows on the first screen (from 269px on a 900px screen): nothing below the hero shows at load (SR-1)
   Vorschlag des Prüfers: end the hero at the fold so nothing of the next section shows at load (hero height: 100svh)

8. **section-height** — `div#root > div.login-page > footer.login-footer "Cerberus LabsTeamSpeak-Verwaltung mit Pl"`; Breiten: 1440.
   the section is 72px tall (0.1 screens), 828px short of one screen (900px): every section after the hero is one screen or two, 2px either way (SR-2)
   Vorschlag des Prüfers: set the section to one screen or two (height: calc(var(--h,1) * 100vh), --h 1 or 2) and cut or split its content to fit

9. **section-height** — `main#login > section.login-copy "Cerberus TS 2.0TeamSpeak-Verwaltungfür d"`; Breiten: 1440.
   the section is 366px tall (0.4 screens), 534px short of one screen (900px): every section after the hero is one screen or two, 2px either way (SR-2)
   Vorschlag des Prüfers: set the section to one screen or two (height: calc(var(--h,1) * 100vh), --h 1 or 2) and cut or split its content to fit

10. **one-section** — `h1#login-title "TeamSpeak-Verwaltungfür deine Community."`; Breiten: 1440.
   the screen at scroll 0px (stride 0) shows 366px of section 2 and the heading of section 2 whole and the content of 2 sections, over 24px each beside section 1: a screen shows one section (another may peek 8px, 24px WARN) (SR-4)
   Vorschlag des Prüfers: make each section exactly one screen or two, so a screen shows one section and no neighbour peeks

11. **one-heading** — `h2#sign-in-title "Am Bot anmelden"`; Breiten: 1440.
   the hero holds an h2 (1): its heading is the h1, and an h2 opens the next section (SR-5)
   Vorschlag des Prüfers: give each section one h2, first in its .wrap, and the hero only its h1

12. **container-edges** — `div#root > div.login-page > header.login-header "Cerberus TSCerberus Labs"`; Breiten: 1440.
   the nav's content runs 149px from the left and 101px from the right (read from its outermost blocks: no .wrap): the page's container starts at 101px; 149 and 101px are not one margin; the margin is 64-120px at 1440px (one container, equal margins, 2px either way) (SR-6)
   Vorschlag des Prüfers: use one container on every section and the nav (.wrap): one margin left and right, 64-120px on a desktop, 16-24px on a phone

13. **container-edges** — `div#root "Zum InhaltCerberus TSCerberus LabsCerber"`; Breiten: 1440.
   a <div> at the top of the page, outside header.nav, main, footer.closing and nav.actionbar (SR-6)
   Vorschlag des Prüfers: build the page as header.nav, a main of sections (each one in-flow .wrap plus background layers) and footer.closing

14. **container-edges** — `main#login > section.login-form-section "Am Bot anmeldenVerwende einen Zugriffsto"`; Breiten: 1440.
   the section needs exactly one in-flow child .wrap (it has 4 in-flow children, 0 .wrap) (SR-6)
   Vorschlag des Prüfers: build the page as header.nav, a main of sections (each one in-flow .wrap plus background layers) and footer.closing

15. **container-edges** — `main#login > section.login-copy "Cerberus TS 2.0TeamSpeak-Verwaltungfür d"`; Breiten: 1440.
   the section needs exactly one in-flow child .wrap (it has 4 in-flow children, 0 .wrap) (and 1 more like it: "Cerberus LabsTeamSpeak-V") (SR-6)
   Vorschlag des Prüfers: build the page as header.nav, a main of sections (each one in-flow .wrap plus background layers) and footer.closing

16. **container-edges** — `h2#sign-in-title "Am Bot anmelden"`; Breiten: 1440.
   5 text blocks start on a left line at x 897, 797px from the container's left: text starts on the container's left, or on one split line (x 627, 732, 837) (also "Verwende einen Zugriffstoken aus", "Zugriffstoken", "Erstelle den Token in der Bot-Ko") (SR-6)
   Vorschlag des Prüfers: start the text on the container's left, or on the one split line every split shares

17. **container-edges** — `section.login-copy > div.login-diagram > div.diagram-node:nth-of-type(1) > span "TeamSpeak"`; Breiten: 1440.
   1 text block starts on a left line at x 137, 36px from the container's left: text starts on the container's left, or on one split line (x 627, 732, 837) (and 2 more like it: x 130, "TeamSpeak-Verwaltungfür ") (SR-6)
   Vorschlag des Prüfers: start the text on the container's left, or on the one split line every split shares

18. **text-only** — `main#login > section.login-form-section "Am Bot anmelden"`; Breiten: 390.
   the closing section is only text: no picture, icon, mock or effect in it
   Vorschlag des Prüfers: give the section a visual: a photograph that fits, the real icons, or a UI fragment on a ground

19. **container** — `page`; Breiten: 1440, 390.
   no .wrap
   Vorschlag des Prüfers: put the content in one .wrap

## Verbleibende Warnungen

- `copy-headings`: „Am Bot anmelden“ wird vom Kategorienprüfer nicht als Abschnittsnomen erkannt. Die deutsche Formularüberschrift bleibt vorerst bestehen.
- `copy-buttons`: „Mit Token anmelden“ beginnt nicht mit einem Verb. Die Formulierung bezeichnet die tatsächliche deutsche Anmeldeaktion.
- `copy-actions`: Die primäre Aktion steht nicht zusätzlich in Hero und Navigation. Die Anmeldung ist ein einzelnes Formular.
- `section-gaps`: Die nebeneinander angeordneten Anmeldespalten werden als überlappende vertikale Abschnitte bewertet. Die tatsächlichen Bilder zeigen die Spalten nebeneinander; der Befund bleibt ungewavert.
- `share-meta`: OpenGraph-/Twitter-Vorschaumetadaten fehlen. Für die interne Verwaltung ist noch keine öffentliche Domain oder Vorschaugrafik vorgesehen.

Weitere Plugin-Oberflächen, die vollständige Browser-/Darstellungsprüfung aller Verwaltungsseiten und die offenen Jeak-Funktionen aus [jeak-parity.md](jeak-parity.md) bleiben Arbeit. Dieser Prüfstand ist keine Freigabe des Gesamtumfangs.
