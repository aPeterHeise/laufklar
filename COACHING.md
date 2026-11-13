# Zwei Coaching-Szenarien

## Szenario 1: Deine Runde – umgesetzt in 0.1.5

Die normale Runde bleibt zeitlich offen. Der Coach kündigt am Start an, was heute passiert: fünf Minuten ankommen, dann einen Kilometer möglichst gleichmäßig laufen. Der Nutzer beendet den Lauf selbst. „Länger laufen“ nennt einen unverbindlichen Umfang, ohne die Aufzeichnung oder die Begleitung danach abzubrechen. Das bestehende Layout bleibt erhalten.

Die Aufgabe wird nachgehalten. Zwei aufeinanderfolgende, ausreichend vollständig aufgezeichnete 500-Meter-Abschnitte mit höchstens 15 Sekunden/km Unterschied zählen als gleichmäßiger Kilometer. Pausen und große Messlücken verhindern diesen Erfolg. Bei anhaltend hoher oder deutlich steigender Belastung wechselt die Aufgabe zur Entlastung. Erst nach tatsächlich gesunkenem Puls und einer anschließenden ruhigen Phase wird diese Anpassung positiv bestätigt. Aufgaben, Ergebnisse und Coach-Ereignisse bleiben mit dem Lauf gespeichert.

### Audio und Belastung

- Distanzansagen behalten den gewählten Abstand (250, 500 oder 1000 m). Sie enthalten Zahlen und gegebenenfalls einen relevanten Zusatz, nicht jedes Mal dieselbe Trainingsanweisung.
- Der Coach erklärt einen Entlastungshinweis höchstens zweimal ausführlicher. Innerhalb einer anhaltenden Episode bleiben weitere Wiederholungen aus; ein erneuter deutlicher Pulsanstieg darf trotzdem einen kurzen Hinweis auslösen. Sinkender Puls wird bestätigt.
- Bei manuell hinterlegten Grenzen orientieren sich Hinweise am konfigurierten Trainingsbereich. Die aus dem Alter geschätzte 70-%-Grenze wird im normalen Lauf nicht als ständige Bremse verwendet.
- Bei geschätzten Grenzen sind ein mindestens 30 Sekunden anhaltender Puls im oberen geschätzten Bereich oder ein deutlicher Pulsanstieg bei gleichem/ruhigerem Tempo Anlass für einen vorsichtigen Hinweis. Das kurzfristige Ziel von zunächst acht Schlägen weniger wird ausdrücklich als vorläufiges Entlastungsziel bezeichnet. Es ist keine gemessene Schwelle oder Aussage, dass ein bestimmter Puls sicher sei. Häufig gemessene hohe Werte verschieben die Grenzen nicht nach oben.
- Es gibt höchstens zwei positive Aufgaben-/Vergleichsmeldungen je Lauf. Bestätigungen einer tatsächlichen Entlastung bleiben separat möglich. Ohne frischen Puls gibt es keine Tempoaufforderung oder Fitness-Erfolgsmeldung.
- Sprachausgaben laufen mit leicht erhöhtem Tempo nacheinander. Distanz und Pulsintervention werden bei gleichzeitigem Anlass zu einer Ansage verbunden.

### Automatische Vergleiche und Streckenende

„Fortschritt vergleichen“ ist kein eigener Startmodus mehr. Historische Vergleichsläufe bleiben lesbar. Der Coach vergleicht die letzten passenden Outdoor-Aufzeichnungen: gleicher Startbereich, passende Laufrichtung und mindestens 300 Meter übereinstimmender Verlauf. Für eine Leistungsrückmeldung sind mindestens etwa 450 Meter, ein Abschnitt nach dem Ankommen, ausreichende Pulsabdeckung und keine Pause im Abschnitt erforderlich. Beschreibende Verbesserungen sind schnelleres Tempo bei ähnlichem/geringerem Puls oder geringerer Puls bei ähnlichem Tempo. Wetter, Tagesform und weitere Einflussfaktoren sind damit nicht kontrolliert; ein einzelner Vergleich ist kein Fitnessnachweis.

Die Reststrecke wird entlang des gespeicherten Weges berechnet, nicht als Luftlinie zum Start. Etwa 500 Meter vor dem früher aufgezeichneten Ende gibt es eine einmalige Orientierung. Das Ende einer Aufzeichnung wird nicht automatisch „Zuhause“ genannt. Bei Abweichung, unklarer Zuordnung, Gegenrichtung oder GPS-Lücke entfällt die Aussage. Am früheren Ende bleibt die Aufzeichnung offen. Es gibt keinen automatischen All-out-Sprint.

### Das Ziel bleibt persönlich

Wer die Zieldistanz bereits am Stück laufen kann, kann das im Profil hinterlegen. Die App kann dann das Zeitziel als nächste Aufgabe erklären. Ein kurzer Trainingslauf liefert noch keine belastbare 10-km-Prognose. Gemessene Zielerreichung setzt weiterhin die tatsächliche Distanz und gesamte verstrichene Zeit voraus. Persönliche Läufe und GPS-Koordinaten werden nicht mit dem Quellcode oder Release veröffentlicht.

## Szenario 2: Missionen unterwegs – Konzept, nicht implementiert

Vor dem Start bietet der Coach einen verständlichen Auftrag an, beispielsweise einen kontrollierten Wechsel zwischen zügigen und ruhigen Abschnitten auf der bekannten Runde. Aufgaben können während des Laufs angepasst werden: einen Abschnitt verlängern, eine Erholung einschieben oder die Tempoaufgabe beenden. Der Coach erklärt dabei jeweils den Grund und den nächsten Schritt. Der Nutzer bekommt eine verständliche Ergebnisgeschichte nach dem Lauf.

Diese Variante würde mehr Führung und häufigere Phasenwechsel bedeuten. Sie wird erst nach Rückmeldung zur ersten Variante umgesetzt. Eine Herausforderung am Streckenende wäre eine bewusst gewählte, kontrollierte Aufgabe mit anschließender Erholung, kein pauschales „alles geben“. Sprachrückmeldungen und Kopfhörertasten sind in 0.1.5 nicht neu angebunden.

## Technische Nachweise

Die Logik wird mit synthetischen Puls-/Bewegungsverläufen, gleichgerichteten und umgekehrten Routen, Pausen, Datenlücken sowie alten und neuen Speicherformaten geprüft. Ein lokal bereitgestellter echter Lauf kann zusätzlich wiedergegeben werden; solche Rohdaten und Replay-Protokolle bleiben außerhalb des Repositorys. Geräteprüfungen laufen zuerst in einem isolierten Emulator. Der aktuelle Prüfstand steht in `VALIDATION.md`.
