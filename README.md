# Laufklar

Native Android-App für Outdoor-Läufe und Laufband, mit direktem Bluetooth-Live-Puls vom Google Fitbit Air, deutschem Audio-Coaching und einem dauerhaften persönlichen Laufziel. Standardprofil: 38 Jahre, männlich, 83 kg, bisher etwa 5:45 min/km; Ziel 10 km in 50 Minuten. Kein Benutzerkonto und kein Backend.

## Installieren

Die installierbare APK und ihre SHA-256-Prüfsumme findest du im [GitHub-Release v0.1.5](https://github.com/aPeterHeise/laufklar/releases/tag/v0.1.5). Die lokale persönliche Vorschau liegt unter `dist/laufklar-preview.apk`. Auf das Android-Telefon übertragen, dort öffnen und die Installation aus der verwendeten Datei-App erlauben. Android 14 oder neuer wird benötigt. Die APK ist für persönliches Sideloading signiert, keine Play-Store-Veröffentlichung. Bei Updates über die bestehende App installieren, nicht vorher deinstallieren: Deinstallation entfernt den lokalen Verlauf.

1. In **Google Health → Verbindungen → Fitbit Air → Herzfrequenz teilen → Starten** die Übertragung aktivieren.
2. In Laufklar **Pulssensor verbinden**, Bluetooth-Freigaben erteilen, das Air auswählen und gegebenenfalls die Android-Pairingabfrage bestätigen. Warten, bis Live-Puls erscheint.
3. Unter **Profil → Audio-Coach → Ansage testen** die Stimme prüfen. Eine deutsche Offline-Stimme muss in Androids Sprachausgabe installiert sein.
4. **Profil → Health Connect verbinden** öffnen und die Schreibrechte erteilen. GPS-Routen sind separat optional. Danach können andere freigegebene Apps die Aufzeichnung aus Health Connect lesen.
5. Draußen präzisen Standort erlauben. Für das Laufband sind keine Standortrechte nötig. Bei verstelltem Laufbandtempo die km/h in der Live-Ansicht aktualisieren. Beim Beenden die tatsächliche Enddistanz vom Laufband eintragen oder die Schätzung übernehmen.

Ein **grüner Punkt vor dem Puls** zeigt an, dass die Verbindung steht und ein höchstens zehn Sekunden alter Messwert vorliegt. Bei fehlenden oder veralteten Werten ist der Punkt grau. Auf der Laufstartseite wird das gespeicherte Fitbit direkt verbunden, auch ohne neuen Bluetooth-Suchtreffer. Unter **Pulssensor verbinden → Gespeicherten Sensor verbinden** lässt sich die Verbindung neu aufbauen; **Sensor freigeben** trennt sie ausdrücklich.

Nach dem Lauf bleibt der Bluetooth-Client für eine mögliche 60-Sekunden-Cooldown-Messung aktiv und wird danach freigegeben. Beim Verlassen der App ohne Aufzeichnung oder Cooldown wird er ebenfalls freigegeben. Während einer laufenden oder pausierten Aufzeichnung bleibt die Verbindung auch bei ausgeschaltetem Bildschirm bestehen. Beim erneuten Öffnen der Laufstartseite wird wieder verbunden. Die Herzfrequenz-Freigabe am Fitbit bzw. in Google Health muss weiterhin aktiv sein.

## Ein dauerhaftes Ziel

**Profil → Laufziel entdecken** öffnet den Zielassistenten. Alter und Studien-Vergleichsgruppe auswählen, 5 km / 10 km / Halbmarathon wählen und den Tempofaktor einstellen. **Als dauerhaftes Laufziel übernehmen** speichert es. Beliebige Distanzen von 1 bis 42,2 km und Zielzeiten können auch direkt im Profil eingegeben werden.

- 100 % entspricht der ausgewählten Studien-Referenzgeschwindigkeit.
- 90 % ist entspannter: Pace = Referenzpace / 0,90.
- 110 % ist schneller: Pace = Referenzpace / 1,10.
- Das Startprofil bleibt bei 10 km / 50 Minuten, bis ein anderes Ziel übernommen wird.
- Die Vorschläge verwenden Alters-/Geschlechtsmediane selbstberichteter **Bestpaces über unterschiedliche bevorzugte Distanzen**. Eine gemeinsame Norm für Alter × Geschlecht × Distanz veröffentlicht die verwendete Tabelle nicht. Die Übertragung des Altersankers auf die gewählte Distanz ist ausdrücklich eine App-Entscheidung. Die getrennten Distanzmediane werden als zusätzlicher Vergleich angezeigt.

Quelle: [Thuany et al. (2021), Running Pace Percentile Values for Brazilian Non-Professional Road Runners](https://pmc.ncbi.nlm.nih.gov/articles/PMC8304551/), Tabellen 2–3, [DOI](https://doi.org/10.3390/healthcare9070829). Autorinnen/Autoren: Mabliny Thuany, Beat Knechtle, Lee Hill, Thomas Rosemann, Thayse Natacha Gomes. Original unter **CC BY 4.0**. Ausgewählte Tabellenwerte sind in `ResearchNorms` lokal enthalten; Auswahl, deutsche Darstellung, Faktorsteuerung und Zielübertragung stammen von Laufklar. Für sehr kleine, fehlende oder unzureichend abgesicherte Altersgruppen wird kein präziser Altersvorschlag erzeugt. Manuelle Ziele bleiben möglich.

## Persönlicher Audio-Coach

**Meine Runde** begleitet deine gewohnte Strecke ohne festes Zeitende: fünf Minuten ankommen, dann ein gleichmäßiger Kilometer als kleine Aufgabe. Der Coach passt die Aufgabe bei steigender Belastung an, erklärt wichtige Eingriffe und bestätigt eine wirksame Entlastung. Die 500-Meter-Ansagen (einstellbar) bleiben erhalten; wiederholte allgemeine Bremssätze entfallen. Höchstens zwei kurze positive Aufgaben-/Vergleichsmeldungen würdigen belegbare kleine Erfolge.

Vergleiche entstehen automatisch auf passenden Abschnitten bekannter Wege, mit gleicher Richtung und ausreichenden Messdaten. Der bisherige separate Vergleichsmodus ist aus der Startauswahl entfernt; alte Läufe bleiben erhalten. Etwa 500 Meter vor dem Ende einer erkannten früheren Strecke gibt es eine Orientierung, ohne automatischen Sprint oder Laufabschluss. **Länger laufen** bietet einen Umfangsvorschlag für eine verlängerte Runde. Im Profil lässt sich hinterlegen, welche Distanz schon am Stück gelaufen wurde; daraus wird keine Zielzeit erfunden.

Die erste umgesetzte Variante und eine mögliche zweite Variante mit stärker geführten Aufgaben stehen in [COACHING.md](COACHING.md).

## Der Trainer im Alltag

Die bisherige Pace wird im Profil als **Minuten:Sekunden pro Kilometer** eingegeben, etwa `5:30`. Mit **Weiß ich noch nicht** bleibt sie offen. Dann sammelt der Trainer zunächst drei lockere Läufe mit gültiger Strecke/Pace und Belastungsgefühl von höchstens 4/10, bevor ein Ausgangstempo für Tempoeinheiten verwendet wird. Zielpace und bisherige Pace sind getrennte Angaben. Alter und Geschlecht bestimmen die Studien-Vergleichsgruppe; ein geänderter Zielvorschlag wird ausdrücklich übernommen. Gewicht wird nicht zu einem unbelegten Puls- oder Pace-Faktor verrechnet.

Es gibt keinen 12-Wochen-Abschluss und keine automatische Zielverschärfung. Empfehlungen werden aus lokal aufgezeichneten Läufen neu berechnet:

- **Kennenlernen:** erste lockere Einheiten; Daten und Belastungsgefühl sammeln.
- **Aufbau:** Länge und gelegentliche Tempoabschnitte schrittweise anpassen.
- **Erhalt:** dokumentiertes Ziel weiter pflegen; es bleibt dasselbe Ziel.
- **Wiedereinstieg:** nach mindestens 14 Tagen Pause die ersten drei Einheiten kürzen.
- **Erholung:** hohe berichtete Belastung, Müdigkeit und zu schnell gewachsener Umfang führen zu leichteren Vorschlägen.

Vor dem Lauf kann die Tagesform angegeben werden. Bei Krankheit/Beschwerden wird kein Lauf gestartet. Intensivere Einheiten sind erst nach der im Profil bestätigten Klärung der ärztlichen Rückfrage, mehreren Läufen und ausreichendem Abstand wählbar. Maximal eine Tempoeinheit innerhalb von sieben Tagen. Ein Ziel wird nur mit der **gesamten verstrichenen Zeit einschließlich Pausen** als erreicht erkannt; Outdoor und Laufband werden getrennt betrachtet.

Eine vollständig absolvierte, gut verträgliche Tempoeinheit mit brauchbaren Puls- und Tempodaten kann den nächsten Vorschlag leicht steigern. Zuerst 5 Sekunden/km schneller bis zum Zieltempo; anschließend gegebenenfalls ein zusätzlicher Abschnitt, später etwas längere Abschnitte. Pro Schritt wird eine Größe verändert. Ohne Belastungsangabe oder verlässliche Messdaten gibt es keine automatische Temposteigerung. Dies ist ein nachvollziehbarer lokaler Regelalgorithmus, kein klinisch validierter oder cloudbasierter KI-Trainer.

## Aufzeichnung und Coaching

- GPS-Aufzeichnung in einem Foreground Service bei ausgeschaltetem Bildschirm. Ein weißes Läufer-Symbol in der Statusleiste und die laufende Android-Benachrichtigung bleiben während einer laufenden oder pausierten Aufzeichnung sichtbar; nach Abschluss einer eventuellen Cooldown-Messung oder nach Verwerfen verschwinden sie. Android-Benachrichtigungen müssen erlaubt sein. Der Aufzeichnungskanal bleibt ohne Ton und Vibration; Pause/Fortsetzen ist auch in der Benachrichtigung möglich.
- Die Live-Karte folgt automatisch dem letzten gültigen GPS-Punkt und behält die Zoomstufe bei. Manuelles Verschieben schaltet das Folgen aus; mit dem Positionssymbol oben rechts wird es wieder aktiviert. Gespeicherte Läufe öffnen weiterhin mit einer Übersicht der gesamten Route.
- **Beenden** pausiert die Aufzeichnung für die Abschlussauswahl. **Lauf speichern** bzw. **Schätzung speichern** übernimmt den Lauf, **Nicht speichern** verwirft ihn samt Wiederherstellungspunkt ohne Health-Connect-Export. **Zurück zum Lauf** setzt eine zuvor laufende Aufzeichnung fort; ein bereits pausierter Lauf bleibt pausiert. Die Zeit in der Abschlussauswahl verlängert einen dort gespeicherten Lauf nicht.
- Auto-Pause für Outdoor-Läufe: nach etwa acht Sekunden verlässlich gemessenem Stillstand pausieren, nach drei Sekunden Bewegung mit Ansage fortsetzen. Manuelle Pausen bleiben bestehen; im Profil abschaltbar.
- Die GPS-Linie ist live und im Verlauf nach den damaligen Pulszonen gefärbt. Fehlende aktuelle Pulswerte bleiben grau.
- Ungenaue GPS-Fixes und unrealistische Sprünge werden verworfen. Pausen und lange GPS-Lücken bilden getrennte Kartensegmente. Fehlende Strecke wird nicht erfunden.
- Bluetooth Heart Rate Service 0x180D / Measurement 0x2A37, 8-/16-Bit-Werte, Kontaktstatus, Verbindungsanzeige und begrenzte Wiederverbindungsversuche.
- Pulsglättung: Median der letzten 15 Sekunden, mindestens drei Werte, jüngster Wert maximal zehn Sekunden alt.
- Optionale deutsche Ansagen alle 250, 500 oder 1000 m: Gesamtstrecke, letzte 60-Sekunden-Pace, Puls und Coaching. Gezielte, begrenzte Belastungshinweise und Rückmeldung bei gesunkenem Puls; Intervallwechsel nach Zeit. Ansagen werden nacheinander gesprochen, Kilometer ohne unnötige Nachkommanullen. Ohne verlässlichen Puls keine Beschleunigungsaufforderung.
- Laufbandstrecke wird abschnittsweise aus eingegebener Geschwindigkeit × aktiver Zeit berechnet. Tempoänderungen gelten ab ihrer Eingabe, Pausen zählen nicht. Jeder neue Lauf beginnt ohne voreingestelltes Tempo. Ohne Tempoeingabe gibt es Zeit/Puls, aber keine Live-Distanz/Pace oder Distanzansagen. Beim Beenden lässt sich die tatsächliche Laufbanddistanz eintragen (0,01–200 km); leer bleibt es bei der Schätzung. Die Korrektur gilt bereits beim ersten Health-Connect-Export und lässt sich später in den Laufdetails ändern. Kein automatischer Zugriff auf das Laufband.
- Aktive Zeit nutzt Androids monotone Uhr. Verschlüsselte Checkpoints alle fünf Sekunden. Bei Prozessabbruch wird beim nächsten Start bis zum letzten Checkpoint gerettet, nicht unsichtbar weitergerechnet. Die App setzt nach einem Neustart den Lauf nicht automatisch fort. Maximale Aufzeichnungsdauer sechs Stunden.
- Historie mit rollierender Wochenübersicht, Karte, Pulskurve, Zonenzeit und Messabdeckung; Belastung (1–10), Notizen, JSON-/GPX-Export, lokales Löschen.

## Entwicklung verstehen

Passende Streckenabschnitte werden während normaler Läufe automatisch verglichen. Für die zusätzliche längerfristige Auswertung gleichmäßiger Abschnitte nach dem Aufwärmen braucht es genügend aktuelle Puls- und Tempowerte, mindestens 80 % Pulsabdeckung und geringe Geschwindigkeitsschwankungen; pausierte Tests werden nicht verglichen. Draußen und Laufband werden nicht als gleichwertige Messbedingungen behandelt. Eine Änderung des Puls/Tempo-Verhältnisses innerhalb des Laufs wird beschreibend angezeigt, ohne klinische Gut-/Schlecht-Grenze. Hitze, Steigung, Schlaf, Alkohol, Stress, Medikamente und optische Messfehler können Ergebnisse verändern.

## Cooldown

Nach „Lauf speichern“ wird der Lauf sofort lokal gesichert. Bei frischem Puls oberhalb des hinterlegten Ruhepulses folgt eine 60-Sekunden-Messung; ohne hinterlegten Ruhepuls wird nur der Pulsabfall bestimmt. Start-/Endpuls, Messwerte und Ergebnis bleiben beim Lauf gespeichert. Der persönliche Score beschreibt den in dieser Minute abgebauten Anteil des Abstands zum Ruhepuls (0–100), ohne medizinische Bewertung. Unvollständige oder unterbrochene Messungen erhalten keinen Score. Die zusätzliche Minute verändert weder Strecke noch aktive Zeit.

## Daten und Health Connect

Profil und Laufdateien werden mit AES-GCM und einem gerätegebundenen Android-Keystore-Schlüssel im privaten App-Speicher gespeichert. Android-Cloud-Backup und Gerätetransfer sind ausgeschlossen. Es gibt keine Analytik, Werbung oder Laufklar-Cloud.

Internet wird ausschließlich für optionale OpenStreetMap-Karten verwendet. Der Kartenanbieter sieht IP-Adresse und angefragte Kacheln; keine Pulswerte oder Laufdateien werden hochgeladen. Karten können im Profil abgeschaltet werden. Die Karte verwendet einen identifizierenden User-Agent, sichtbare Attribution und lokalen Kachelcache; kein Massen-Vorladen.

Health Connect erhält nach einmaliger Freigabe automatisch Trainingssitzung, Distanz, Herzfrequenz und Geschwindigkeit, optional die Route. Die Automatik lässt sich im Profil abschalten. Nach einem Cooldown werden dessen zusätzliche Pulswerte separat und sein Ergebnis in der Trainingsnotiz übergeben; die Laufdauer bleibt unverändert. Stabile Client-IDs und Versionen erlauben erneutes Teilen und Korrekturen ohne doppelte Einträge. Laufbanddistanz und eingegebene Geschwindigkeiten werden als manuelle Daten markiert. Fehlgeschlagene automatische Exporte bleiben mit Status sichtbar, werden beim nächsten Öffnen erneut versucht und können auch in der Detailansicht wiederholt werden. Die App fordert keine Leserechte für fremde Gesundheitsdaten an.

Health Connect bleibt auf dem Telefon. Andere Apps können nach ihrer eigenen Freigabe Daten lesen und eventuell selbst in eine Cloud übertragen. Ebenso kann die Google-Health-App unabhängig von Laufklar Fitbit-Daten synchronisieren. Das muss in diesen Apps konfiguriert werden.

**Lokales Löschen entfernt keine bereits exportierten Dateien oder Health-Connect-Einträge.** Diese lassen sich im Dateimanager bzw. in Health Connect löschen. JSON-/GPX-Archive sind unverschlüsselt; ein Wiederimport ist in dieser Vorschau nicht enthalten.

## Build und Tests

JDK 17, Android SDK Plattform 36, Build Tools 35.0.0. `ANDROID_HOME` setzen oder eine nicht eingecheckte `local.properties` mit `sdk.dir=...` anlegen.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest assembleRelease
```

Gradle 8.11.1 (Wrapper mit SHA-256), Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Compose BOM 2025.04.01, Health Connect 1.1.0, osmdroid 6.1.20. `compileSdk`/`targetSdk` 36, `minSdk` 34. Die gepinnten Versionen sind reproduzierbar, nicht sämtlich die jeweils neuesten Versionen. Der persönliche Release-Build ist bewusst mit dem lokalen Debug-Schlüssel signiert. Für eine Play-Veröffentlichung sind ein dauerhaft gesicherter Release-Schlüssel, passende Health-/Foreground-Service-Erklärungen und weitere Geräteprüfungen erforderlich.

Siehe `VALIDATION.md` für tatsächlich ausgeführte Prüfungen und `RESEARCH.md` für Forschung und Grenzen. Verbindung mit dem echten Fitbit Air, empfangener Live-Puls und hörbare deutsche Testansage wurden am Pixel bestätigt. GPS-Qualität unter Bewegung, längere Bluetooth-Verbindungen, Kopfhörer mit Musik und Akkulaufzeit bleiben praktische Laufprüfungen.
