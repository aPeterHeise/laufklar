# Validierung der persönlichen Vorschau

Stand: 13. November 2026. Paket `de.laufklar.app`, Version 0.1.4. Das ausgelieferte APK liegt in `dist/laufklar-preview.apk`.

## Erfolgreich ausgeführt

- **53 JVM-Tests**, keine Fehler: Puls-Paketformat und Hautkontakt, Glättung/Alter der Daten, GPS-Filter, Pace, Pausen und Zonenzeit, Serialisierung, konfigurierbare Ziele, Faktor 90/100/110 %, adaptive Phasen, Wiedereinstieg, Erhalt und Evidenzanforderungen für Steigerungen. Keine Pulsinitialisierungsansage beim Start, Minuten:Sekunden-Eingabe mit Fehlerfällen, Migration/Erhalt einer unbekannten Pace sowie Lernen des Ausgangstempos aus drei gut vertragenen Läufen. Zwölf zusätzliche Tests prüfen die Verbindungszuständigkeit bei Hintergrund, Aufzeichnung, Laufende, Wiederöffnung, expliziter Freigabe, Gerätewechsel und überlappenden Activity-Lebenszyklen.
- **14 Instrumentierungstests auf Android 16 / API 36**, keine Fehler, in einer isolierten ARM64-Emulatorinstanz:
  - Navigation und Datenschutzansicht;
  - Studienziel auswählen, 110 % übernehmen und gespeicherte Zielpace prüfen;
  - tatsächliche verschlüsselte Speicherung, Entschlüsselung und Löschung;
  - Foreground-Service für Laufband, Zeit/Distanz, Pause/Fortsetzung, Activity-Neuerstellung, einmaliges Speichern;
  - Wiederherstellung eines unterbrochenen Checkpoints, ohne Duplikat;
  - Minuten-Pace eingeben, Geschlecht per Dropdown wechseln, passende Studienreferenz aus dem noch ungespeicherten Profil übernehmen und unbekannte Pace speichern;
  - frische Pulswerte zwischen den UI-Zeittakten auf dem Startbildschirm kontinuierlich anzeigen;
  - frische Pulswerte zwischen den UI-Zeittakten im aktiven Lauf kontinuierlich anzeigen;
  - gespeicherten Sensor ohne Bluetooth-Suchtreffer anzeigen und auswählen; Live-Indikator bei frischem Puls aktiv, bei veraltetem Puls inaktiv;
  - Live-Karte folgt neuen Positionen ohne Änderung der Zoomstufe; echtes Wischen deaktiviert das Folgen, weitere GPS-Punkte verschieben die manuell gesetzte Ansicht nicht, das Positionssymbol zentriert wieder;
  - Abschlussauswahl pausiert Zeit und Strecke; Rückkehr setzt einen zuvor laufenden Lauf fort und lässt eine vorherige Pause bestehen; Eingabe bleibt bei Activity-Neuerstellung erhalten; Verwerfen entfernt den Checkpoint ohne wiederhergestellten Lauf oder Health-Connect-Sitzung;
  - tatsächliche Laufbanddistanz vor dem ersten Health-Connect-Export korrigieren, ungültige Eingabe sperren, korrigierte Distanz und genau eine Sitzung zurücklesen; Wartezeit im Abschlussdialog verlängert den gespeicherten Lauf nicht;
  - Laufbandschätzung unverändert übernehmen und beim nächsten Lauf Tempo auf null zurücksetzen;
  - Benachrichtigung mit eigenem monochromen Statussymbol und einem Kanal ohne Ton während des Laufs vorhanden, nach Speichern und Verwerfen entfernt;
  - **echtes Health Connect**: Sitzung, GPS-Route, Pause und Messwerte schreiben, wiederholen, eigene Datensätze gezielt zurücklesen, genau einen Sitzungseintrag nachweisen und eine Distanzkorrektur ohne zusätzlichen Distanzeintrag verifizieren. Anschließend wurden die synthetischen Testeinträge entfernt.
- Ein früherer Stand bestand außerdem vier Instrumentierungstests auf Android 15 / API 35. Die finale vollständige Suite wurde auf API 36 geprüft.
- `testDebugUnitTest`, `lintDebug`, `assembleRelease`: erfolgreich.
- Android Lint: **0 Fehler, 10 Warnungen**. Sechs Hinweise betreffen neuere Abhängigkeitsversionen, drei eine optionale URI-Kotlin-Schreibweise. Ein statischer Kontext-Hinweis betrifft den prozessweiten Bluetooth-Empfänger; dieser hält ausdrücklich nur den Application-Kontext. Kein Lint-Baseline-Verstecken von Fehlern. Der Touch-Beobachter der Karte verbraucht keine Ereignisse; MapView behält die Klick-/Accessibility-Behandlung. Der dazugehörige statische Click-Hinweis ist deshalb gezielt mit Begründung ausgenommen.
- Optimierte, verkleinerte Release-APK erzeugt (ca. 2,3 MiB).
- Finale Release-APK im isolierten API-36-Emulator installiert und gestartet. Laufband-Testlauf geöffnet und verworfen; das Läufer-Symbol während der Aufzeichnung und sein Verschwinden nach dem Verwerfen anhand von Screenshots bestätigt. Abschlussdialog mit allen drei Aktionen visuell geprüft. Die vier neuen Instrumentierungstests wurden nach Ergänzung der echten Wischgeste erneut erfolgreich ausgeführt.
- APK-Signatur mit `apksigner verify` erfolgreich überprüft (v2). Persönliche Debug-Signatur; keine Play-Store-Veröffentlichung.
- Manifest geprüft: Schreibrechte für Health Connect; keine Leserechte für fremde Gesundheitsdaten, keine Hintergrund-Standortberechtigung. Internet für die optionale Straßenkarte.

Der letzte isolierte Emulatorlauf wurde benötigt, weil Tests eines weiteren Projekts zwischenzeitlich denselben ersten Emulator bedienten. Diese kollidierende Sitzung wurde nicht als bestandene App-Prüfung gewertet.

## Auf dem tatsächlichen Pixel bestätigt (bis Version 0.1.3)

- Pixel 11 Pro, Android 17 / API 37: signierte Release-APK per USB installiert und erfolgreich geöffnet.
- Update auf Version 0.1.1 (Versionscode 2) nach Ende des Nutzer-Testlaufs erfolgreich installiert; installierte Versionsnummer auf dem Pixel überprüft.
- Version 0.1.3 auf dem Pixel installiert: gespeichertes echtes Fitbit Air verbindet sich direkt; grüne Live-Anzeige mit frischen Messwerten bestätigt.
- **Hardware-Neustarttest bestanden:** App ohne aktive Aufzeichnung in den Hintergrund geschickt, zwei Sekunden gewartet und wieder geöffnet; anschließend wieder frischer Live-Puls. Danach App-Prozess per `am force-stop` vollständig beendet, zwei Sekunden gewartet und kalt neu gestartet; erneut automatisch frischer Live-Puls. Während dieser Testfolge wurden weder Google Health geöffnet noch die Herzfrequenzfreigabe dort zurückgesetzt.
- Beim Hardwaretest wurde außerdem eine Kollision zwischen zwei MainActivity-Instanzen im Android-Stapel gefunden. Sichtbarkeit und Pulsvorschau werden jetzt je Ansicht verwaltet. Zwei gezielte Logiktests prüfen, dass die alte Ansicht beim Stoppen die neue nicht trennt und eine unsichtbare Startseite den Sensor nicht für eine andere Ansicht belegt.
- Echtes Google Fitbit Air verbunden; tatsächliche Live-Pulswerte empfangen und auf dem Startbildschirm sowie im vom Nutzer gestarteten Testlauf angezeigt.
- Deutsche Offline-Testansage ausgelöst; vom Nutzer als gut hörbar bestätigt.
- Health Connect verbunden; erteilte Schreibrechte für Training, Herzfrequenz, Distanz, Geschwindigkeit und Route auf dem Gerät überprüft. Keine synthetischen Testläufe auf dem Nutzertelefon angelegt.
- Nutzerbeobachtung eines blinkenden Pulswerts nachvollzogen: Ein frisch empfangener Wert konnte neuer als der letzte UI-Zeittakt sein und deshalb irrtümlich ausgeblendet werden. Korrektur berücksichtigt beim Rendern die aktuelle Uhr; oben genannte Regressionstests decken beide Anzeigen ab. Die Zehn-Sekunden-Grenze für tatsächlich veraltete Werte bleibt erhalten.

## Noch auf dem tatsächlichen Telefon prüfen

- Version 0.1.4 wurde im Emulator geprüft. Das Pixel war bei dieser Änderung nicht per USB verbunden. Mitlaufende Karte und Statussymbol bitte beim nächsten echten Lauf auf dem Pixel bestätigen.

- Fitbit Air: längere kontinuierliche Aufzeichnung, Tragekontaktverlust und Wiederverbindung nach Funkreichweiten-Abbruch.
- Ausgabe über die tatsächlich verwendeten Kopfhörer, Zusammenspiel mit Musik/Audiofokus.
- Outdoor-GPS bei Bewegung, Genauigkeit und Verhalten bei ausgeschaltetem Bildschirm, realer Akkuverbrauch.
- Sichtbarkeit der eigenen Läufe in den vom Nutzer verwendeten anderen Apps mit deren eigenen Health-Connect-Freigaben.
- Längere reale Läufe, Entzug von Berechtigungen während eines Laufs, Hersteller-/Android-Varianten und sehr große Schriftgrößen.

Es wurde kein klinischer Test durchgeführt. Die Trainingsregeln, Fitnesshinweise und Pulsmessung sind nicht als medizinische Diagnose oder garantierte Leistungsprognose validiert.
