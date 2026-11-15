# Laufklar: persönlicher Audio-Coach mit Missionen

Stand: 15. November 2026. Übergabe an den nächsten Coding-Agenten. **Ursprünglicher Umsetzungsplan.** Version 0.1.7 setzt diesen Plan im neuen Aufzeichnungsablauf um. Den geprüften Stand und ausstehende Praxistests dokumentiert VALIDATION.md. Bei Umsetzung hat dieses Dokument Vorrang vor widersprechenden Zukunftsideen in COACHING.md; dessen Beschreibung des bisherigen Verhaltens bleibt historischer Kontext.

## 1. Nutzerauftrag und Produktentscheidungen

Der Nutzer läuft überwiegend mit Musik/AirPods auf einer etwa 4-km-Stammrunde, kann bereits 10 km laufen und möchte auf 10 km schneller werden. Hinterlegtes Ziel prüfen und übernehmen, nicht hart kodieren (bisher 10 km in 50 Minuten). Er kann die Strecke für angekündigtes Training verlängern. Das bestehende Interface gefällt ihm; Navigation und Gestaltung erhalten. Zentrale Interaktion bleibt Audio.

Festzuhalten aus beiden Feedbackrunden:

- Dauernde Bremsansagen wurden ignoriert. Wenige begründete Interventionen mit konkreter Handlung und anschließender Überprüfung sind erwünscht.
- Der Screenshot zeigte 25:12 Minuten, Ø 142, Maximum 157 bpm und einen insgesamt steigenden Puls mit Unterbrechungen. Ohne Pace, Gelände und Rohdaten ist weder eine Diagnose noch ein Urteil über persönliche Fitness möglich. 80–167 war die automatisch gepolsterte Diagrammskala, nicht der Messbereich. Es gibt keine Pflicht, so lange weiterzulaufen, bis der Puls ein Plateau erreicht.
- „Ich lerne dich kennen“ muss sichtbare Daten, Fortschritt und Konsequenzen haben. Drei Läufe dürfen kein leeres Versprechen sein.
- Nach einem frühen Vergleich fehlten Aufgaben und Orientierung. Gewünscht sind eine, bei passender Dauer zwei Missionen pro Lauf, aus einem Katalog von zehn Varianten.
- Nach dem Cooldown fehlt ein gesprochenes und gespeichertes Ergebnis: Strecke, Zeit, Aufgabe, passende Vergleiche, Pulsänderung, nächster Schritt.
- Puls 149 allein ist keine verständliche Rückmeldung. Bezug zum heutigen Auftrag, Zielband und dessen Verlässlichkeit erklären.
- Beim gleichmäßigen Kilometer berichtet der Nutzer ca. 30 s/km Beschleunigung in Hälfte zwei, keine Korrektur unterwegs und anschließend Lob. Das Erlebnis ist ernst zu nehmen; der genaue Ablauf ist ohne Coach-Ereignisse/Rohdaten nicht reproduziert.
- Aufgaben später als direkt nach fünf Minuten starten. Natürlichen Aufwärmanstieg nicht als Fehler bewerten.
- Abwechslung soll Training unterstützen. Keine zufälligen Maximalsprints, kein Rückwärtslaufen im Straßenverkehr, kein Wettbewerb um möglichst niedrigen Puls.
- Tagesform bleibt freiwillig. Aufzeichnung bleibt auch bei „angeschlagen“ möglich; keine Leistungsurteile gegen erholte Läufe. Auto-Pause und manuelle Pause bleiben erhalten.
- Firebase-Veröffentlichung bleibt ausschließlich manuell auf ausdrückliche Anweisung. Keine neue GitHub-Automation, IAM-Zugänge oder automatische Veröffentlichung aus diesem Plan.

Entscheidung: **Ein Lauf hat einen Trainingsauftrag, eine bis zwei passende Missionen und einen nachvollziehbaren Abschluss.** Missionserfolg, heutiger Leistungsvergleich und längerfristiger Fortschritt sind getrennte Ergebnisse. Zufall entscheidet zwischen passenden Aufgaben, niemals über die Intensität des Tages.

## 2. Tatsächlicher Stand im Code

Relevante Dateien liegen unter `app/src/main/java/de/laufklar/app/`.

- `AdaptiveTrainer.kt`: drei geeignete erholte Läufe können ein unbekanntes Ausgangstempo bestimmen, aber nur bei `usualPace == null`; der Standardwert ist derzeit 345 s/km. Historie beeinflusst außerdem Fokus, Umfang, Erholung und Freigabe/Steigerung von Tempoeinheiten. Der Wechsel aus LEARN ist nicht gleichbedeutend mit drei hochwertigen Referenzläufen. Es existiert keine Bibliothek freigeschalteter Missionen.
- `PersonalCoach.kt`: feste Gleichmäßigkeitsaufgabe nach fünf Minuten. Bewertung erst bei Distanzansagen; verwendet den jeweils letzten Kilometer statt unveränderlicher Aufgabenanker. Erfolg derzeit bei höchstens 15 s/km Halbkilometerdifferenz. Es fehlen eine verbindliche Auswertung bei Aufgabenende, gezielte Korrekturen während der Aufgabe und ein expliziter Nicht-erfüllt-Zustand. Nicht behaupten, 30 s/km würden vom vorhandenen Erfolgskriterium akzeptiert; rollendes Fenster, Ansagezuordnung und Ereignisse untersuchen.
- Vergleichslob derzeit höchstens einmal, positive Aufgaben-/Vergleichsmeldungen zusammen höchstens zweimal pro Lauf. Die fehlende Fortsetzung nach 2,5 km ist damit plausibel. Die Lösung ist mehr sinnvolle Zustandsrückmeldung, nicht unbeschränktes Lob.
- `EffortMonitor`: kurzfristiger Driftvergleich von ca. 90–180 Sekunden und zwölf bpm; bei geschätzten Zonen absolute Intervention erst ab `bounds[4]`. Langsame Drift kann fehlen. `Profile.estimated` unterscheidet nicht ausreichend zwischen gemessenem Maximalpuls und tatsächlich bestimmten Trainingsschwellen.
- `RouteCoach.kt`: Routenabgleich und Puls-/Pace-Vergleich vorhanden. Ergebnisse sind bislang vorwiegend fertige Texte, keine ausreichend reichhaltigen Ergebnisdaten. Referenzen sind auf acht letzte Läufe begrenzt, vor der Auswahl nach Tagesform.
- `Domain.kt`: bestehender Benchmark erfordert 30 Minuten und lange stabile Abschnitte. Für die normale 25-minütige Runde muss ein separater Kurzabschnittsvergleich entstehen; keine Werte durch gelockerte Kriterien stillschweigend als denselben Benchmark ausgeben.
- `RunService.kt`: sofortige Sicherung, anschließend optionale 60-s-Cooldown-Messung, finale Sicherung/Health Connect, dann Service-Ende. Keine abschließende Audio-Zusammenfassung; `onDestroy` beendet TTS. Eine Zusammenfassung einfach vor `stopSelf()` einzureihen würde sie abschneiden.
- `SpeechQueue.kt`: FIFO plus austauschbare/ablaufende Statusmeldungen. Für Missionen und Schlussansage weiterentwickeln.

## 3. Wissenschaftliche Grundlage und Grenzen

Die folgenden Arbeiten begründen Prinzipien, **nicht** die exakten Spieldauern, Grenzwerte oder die Wirksamkeit jeder Mission. Alle mit „Produktparameter“ bezeichneten Werte sind testbare Startentscheidungen, keine medizinisch validierten Grenzen.

- [Lamberts et al., 2004: Variation in heart rate during submaximal exercise](https://pubmed.ncbi.nlm.nih.gov/15320683/): wiederholte submaximale Messungen weisen natürliche Variation auf. Einzelne kleine Pulsänderungen nicht als Fitnessgewinn verkaufen. Kontext und wiederholte passende Messungen berücksichtigen.
- [Studie zu ventilatorischer Schwelle, Herzfrequenzreserve und RPE](https://pmc.ncbi.nlm.nih.gov/articles/PMC10524184/): persönliche Belastungsgrenzen lassen sich nicht zuverlässig aus festen Prozenten ableiten. Auch ein gemessener Maximalpuls ist keine gemessene aerobe Schwelle.
- [Fritzsche et al., 1999](https://pubmed.ncbi.nlm.nih.gov/10066688/): Puls kann während längerer konstanter Belastung steigen. Steigung einer Pulskurve allein ist kein Fehler und kein Gesundheitsurteil.
- [Muñoz et al., 2014, randomisierte Untersuchung bei Freizeitläufern](https://pubmed.ncbi.nlm.nih.gov/23752040/): unterschiedliche Intensitätsverteilungen verbesserten die 10-km-Leistung über zehn Wochen; der Gruppenunterschied war insgesamt nicht statistisch eindeutig. Daraus folgt keine universelle 80/20-Pflicht und keine Aussage, mittlere Intensität sei nutzlos.
- [Franch et al., 1998](https://pubmed.ncbi.nlm.nih.gov/9710865/): mehrwöchiges intensiveres Lauftraining kann Laufökonomie und Leistung verbessern; nicht jedes kurze schnelle Spiel ist damit einzeln validiert. Gezielte Tempoeinheiten statt täglicher spontaner Intensivierung.
- [Støren et al., 2008, randomisierte Studie](https://pubmed.ncbi.nlm.nih.gov/18460997/): ergänzendes Krafttraining verbesserte bei trainierten Läufern die Laufökonomie. Kein Beleg dafür, dass Audio-Aufgaben beim Joggen Krafttraining ersetzen. Eigenständiges Kraftmodul später, nicht Teil dieser Umsetzung.
- [Reliabilität von Erholungspulsmessungen](https://pubmed.ncbi.nlm.nih.gov/21450521/): Erholung muss als Messprotokoll betrachtet werden. Pulsabfall nach Speichern ist nicht automatisch klinische HR-Recovery nach Belastungsende; Gehen, Stehen und Verzögerung beeinflussen die Vergleichbarkeit.

Begriffe: „Puls bei gleichem Tempo“ ist ein beschreibender Trainingsindikator, keine Messung der Sauerstoffkosten/Laufökonomie. Kein VO₂max-Schätzwert, keine Gesundheitsampel, keine 10-km-Prognose aus einer kurzen Runde. Schneller mit höherem Puls ist zunächst eine höhere Belastung, nicht automatisch schlechter. Langsamer mit niedrigerem Puls kann genau das Tagesziel erfüllen.

## 4. Ehrliches Kennenlernen und Zielbereiche

Neue eigenständige Datenqualität statt Freischaltung allein nach Anzahl der Läufe:

- `LearningProgress`: Anzahl geeigneter Läufe, vorhandene Referenzabschnitte, fehlende Voraussetzung, Datum, Profilversion, verwendete Run-IDs.
- Produktparameter: ein geeigneter Lernlauf ist gespeichert, nicht wiederhergestellt, READY, gleiches Laufmedium, RPE 2–4/10 nachgetragen und enthält nach mindestens acht aktiven Minuten einen zusammenhängenden Abschnitt von mindestens fünf Minuten mit zuverlässiger Bewegung und mindestens 80 % Pulsabdeckung. Während des Abschnitts keine Pause, intensive Mission oder aktive Entlastung. Maximal ein Lernpunkt pro Lauf.
- Drei solche Läufe innerhalb der letzten 35 Tage liefern einen ersten Arbeitsstand, keine physiologischen Schwellen. Bereits geeignete Historie anrechnen; kein künstlicher Neustart. Ohne RPE: Lauf bleibt gespeichert, Status nennt genau diese fehlende Angabe.
- Angegebenes `usualPace` bleibt als Nutzereingabe erhalten. Zusätzlich `observedEasyPace` aus geeigneten Abschnitten bestimmen und als andere Quelle speichern. Trainingsplanung muss diesen Arbeitsstand tatsächlich verwenden, auch wenn eine ältere Nutzereingabe existiert. Für EASY gewinnt ein gültiger beobachteter Arbeitsstand gegenüber einer älteren allgemeinen Pace; eine ausdrücklich neu gesetzte persönliche Trainingsvorgabe hat Vorrang und wird als solche angezeigt. Ein beobachtetes EASY-Tempo wird nicht automatisch zur schnellen Intervall-Zielpace. Keine stillschweigende Überschreibung des Nutzerwerts.
- Robuste Abschnittswerte: Pace aus Zeit/Strecke; HR zeitgewichtet über gültige Intervalle. Pro Lauf ein repräsentativer Wert, über Läufe Median; Unsicherheit/Spannweite anzeigen. Keine Mittelung von Sekunden pro Kilometer über unterschiedlich lange Abschnitte ohne Gewichtung.
- Persönliche Pulsziele erhalten Herkunft: `AGE_ESTIMATE`, `MEASURED_MAX_PERCENT`, `OBSERVED_EASY`, `USER_CALIBRATED_THRESHOLD`. Nur letzteres darf als persönlich kalibrierter Schwellenbereich bezeichnet werden; Eingabe muss Herkunft explizit abfragen, alte Custom-Bounds sind zunächst `USER_DEFINED_UNVERIFIED`.
- Für OBSERVED_EASY: Arbeitsband aus den zeitgewichteten HR-Abschnittsmitteln der drei Lernläufe, zunächst Median ±5 bpm. Ist deren Spannweite >12 bpm oder widersprechen die Angaben (RPE, Messqualität, Kontext), noch kein Zahlenziel ableiten. Diese Toleranzen sind Produktparameter; Band nicht allein durch häufige hohe Messwerte nach oben verschieben. Kein automatischer Maximalpulstest.
- Altersbasierte Grenzen bleiben als grobe Orientierung gekennzeichnet; keine unbegründete neue persönliche 145-bpm-Grenze. Falls Arbeitsband und bestehende Entlastungsregel widersprechen, Band als ungeklärt markieren und Tempo nicht steigern. Regelpriorität explizit testen.
- Freigaben: ab Lauf 1 geeignete ruhige Aufgaben; ab einem passenden Referenzlauf beschreibender Abschnittsvergleich; nach drei geeigneten Lernläufen erster personalisierter Arbeitsstand. Intensivere Aufgaben bleiben unabhängig davon an bisherige `qualityAllowed`-/`intensityReady`-Voraussetzungen gebunden.
- Starttext maximal ein Satz zum Lernstand: „Zwei von drei Referenzläufen vorhanden. Heute sammeln wir einen ruhigen Abschnitt.“ Nach erfolgreichem dritten Lauf konkret sagen, welche Werte jetzt vorliegen. Später „Arbeitsstand aus drei Läufen, zuletzt aktualisiert …“, kein dauerhaftes Kennenlernversprechen.

## 5. Tagesauftrag, Aufwärmen und Pulsfeedback

Bestehenden Einstieg erhalten; im vorhandenen Trainerbereich Auftrag und Mission ankündigen. Ruheauftrag und kontrollierter Tempoauftrag sind unterschiedliche Pläne, nicht bloß unterschiedliche Zufallsaufgaben. Tempoauftrag nur bewusst gewählt oder vor dem Start bestätigt; „Meine Runde“ wird nicht unterwegs überraschend zum HIIT.

Aufwärmen als eigener Zustand: mindestens acht Minuten für ruhige Missionen, mindestens zehn Minuten für Tempoaufgaben, danach 90 Sekunden verwertbare, ununterbrochene Daten. Normaler anfänglicher Pulsanstieg ist kein Verstoß. Kein blindes „Du startest zu schnell“ nach einer festen Zeit.

Frühe Tempokorrektur nur mit Referenz: verlässliche 60-s-Pace mindestens 30 Sekunden lang mehr als 20 s/km schneller als der ausdrücklich vereinbarte Einstieg. Dann „Für den ruhigen Einstieg heute etwas Tempo herausnehmen.“ Der Wortlaut bezieht sich auf den Plan, nicht auf eine bewiesene Gesundheitsgrenze. Bei fehlender Referenz „Nimm dir die ersten Minuten zum Ankommen“, nicht bewerten. Bereits klar erhöhte/steigende Belastung und Beschwerden dürfen unabhängig vom Warm-up Aufmerksamkeit bekommen. Die konkrete bestehende Pulsintervention nicht für die ersten acht Minuten abschalten.

Jede normale Distanzansage verbindet maximal drei Bestandteile: Fortschritt, Puls im Kontext, nächste Handlung. Nicht immer alle Rohzahlen vorlesen. Beispiele sind Textvorlagen, keine realen Messwerte:

- Kalibriertes Ziel: „2,5 Kilometer. Puls 149, noch im heutigen Zielbereich. Halte das Tempo.“ Nur wenn das für diesen Nutzer/Abschnitt tatsächlich zutrifft.
- Vorläufiges Band: „Puls 149, am oberen Rand unseres vorläufigen Arbeitsbereichs. Heute nicht weiter beschleunigen.“ Herkunft am Start erklären; später nur bei Bedarf wiederholen.
- Kein Band: „Puls 149. Dein persönlicher Bereich ist noch offen; im letzten Abschnitt blieb der Puls bei ähnlichem Tempo stabil.“ Ohne belastbaren Trend nur Zahl plus fehlende Einordnung, keine erfundene Stabilität.
- Geplanter Tempoabschnitt: „Der Puls steigt wie bei diesem schnelleren Abschnitt zu erwarten. Noch eine Minute kontrolliert zügig.“ Nicht behaupten, erwartbar bedeute garantiert unbedenklich.

Langsamere Drift zusätzlich erkennen: gültiges 3-Minuten-Fenster gegen ein vorheriges 3-Minuten-Fenster vergleichen, mindestens acht aktive Minuten, mindestens 80 % Datenabdeckung, kein Pause-/Wiederanlauf-/Intervallübergang in den Fenstern. Produktparameter: HR-Anstieg >=8 bpm bei ähnlicher oder langsamerer Geschwindigkeit (Toleranz 3 %), 60 Sekunden anhaltend. Gelände unbekannt => nur vorläufige Aussage, Ursache nicht behaupten. Bei bekanntem Anstieg keine Fitnesswertung. Bestehende kurzfristige deutliche Eskalation bleibt separat.

Bei ruhigem Auftrag: einmal nachvollziehbar reduzieren, nach zwei Minuten erneut bewerten. Maximal zwei ausführliche Erklärungen pro Episode; keine „langsamer“-Endlosschleife. Neue deutliche Verschlechterung ist ein neuer Anlass. Ziel ist kontrollierte Belastung, keine mathematisch waagerechte Pulskurve.

## 6. Missionen: Auswahl und verbindlicher Ablauf

Pro Lauf eine Hauptmission; eine zweite nur, wenn Dauer, Tagesauftrag und Daten passen. Maximal eine intensive Mission, welche mehrere Wiederholungen enthalten kann. Bestehende Begrenzung einer Tempoeinheit in sieben Tagen und mindestens 48 Stunden Abstand beibehalten; jede intensive Mission zählt dafür, auch in einem sonst EASY benannten Lauf. Bei TIRED/UNWELL keine intensiven Missionen und kein Leistungslob gegenüber READY.

Routenende: geschätzte Restzeit aus passender bekannter Strecke und robuster Pace. Aufgabe plus mindestens drei Minuten ruhiger Abschluss müssen hineinpassen, bei intensiven Aufgaben fünf Minuten. Unbekannte Route: nur kurze ruhige Aufgaben; für längere Tempoaufgaben vor Start eine Dauer bestätigen. Keine automatische Rundenverlängerung. Ein Lauf darf immer früher beendet werden.

Auswahl: Eignung filtern, danach gewichtete Auswahl ohne unmittelbare Wiederholung der letzten beiden Hauptmissionen, soweit Alternativen vorhanden. Seed und Auswahlgrund speichern. Lernbedarf und bisherige Schwierigkeit beeinflussen Gewicht; Zufall ist reproduzierbar. Kein harter Anspruch auf zwei Missionen bei 25 Minuten Laufdauer.

Zustände: `PLANNED -> OFFERED -> ACTIVE -> SUCCEEDED | PARTIAL | NOT_MET | UNMEASURABLE | ABORTED`. Zusätzlich `SUSPENDED` bei Pause/temporärem Datenverlust. Abgesagte/unterbrochene Missionen dürfen jederzeit in ruhiges Begleiten übergehen. Nie aus Schweigen eine Zustimmung zu höherer Intensität ableiten.

Aktivierung: Aufgabenbeschreibung erklärt Dauer, Ziel, Rückmeldung und Erfolgskriterium. Startzeit/-distanz/-segment werden unveränderlich gespeichert. Bewertung und Zwischenhinweise laufen unabhängig von Distanzansagen. Ein späterer rollender Kilometer darf keine gescheiterte Aufgabe nachträglich gewinnen lassen.

Bei Pause: keine Zeit/Distanz zur Aufgabe zählen, veraltete Anweisungen entfernen. Nach Fortsetzung sagen, ob fortgeführt oder beendet. Vergleichs-/Gleichmäßigkeitsmission bei Pause UNMEASURABLE, anschließend höchstens eine neue kurze ruhige Aufgabe; keine unsichtbaren Neustarts. Tempoaufgabe nach Pause in Erholung wechseln, nicht automatisch mitten im schnellen Abschnitt weitermachen.

## 7. Katalog mit zehn Aufgaben

Die Dauern sind anfängliche Produktparameter. Gemeinsame Regeln aus Abschnitt 6 gelten immer. Ein Erfolg belohnt die Ausführung, nicht behauptete physiologische Anpassung.

| ID / Name | Ablauf | Feedback und messbarer Erfolg | Zweck / Einschränkung |
|---|---|---|---|
| M01 Gleichmäßigkeits-Kilometer | Nach Warm-up 1 km; feste Grenzen Start / +500 / +1000 m | Nach 500 m erste Pace; Hälfte zwei aktiv begleiten. Erfolg: Halbkilometerdifferenz <=15 s/km und >=80 % gültige Bewegungszeit im vereinbarten Paceband (Startpace ±15 s/km). | Pacing lernen; keine Messung maximaler Leistung. Frühestens Minute 8, bevorzugt 10–12. |
| M02 Rhythmus halten | Drei Minuten im aus den vorherigen zwei ruhigen Minuten abgeleiteten Paceband ±15 s/km | Nach 90 s kurzer Status; Erfolg >=80 % gültiger Zeit im Band, keine längere deutliche Abweichung. | Kurze Alternative für 4-km-Runden. GPS-Qualität erforderlich. |
| M03 Ruhiger Motor | Drei Minuten im vorhandenen kalibrierten oder vorläufigen EASY-HR-Band; keine Aufforderung zum Beschleunigen, wenn HR darunter liegt | Erste Minute einschwingen; Erfolg mindestens 80 % der beiden folgenden Minuten im oder unter dem Band, bei weiterlaufender verwertbarer Bewegung. Herkunft des Bands nennen. | Ruhige Belastung üben. Ohne sinnvolles Band durch M02 ersetzen. Kein Wettkampf um Minimalpuls. |
| M04 Einen Gang zurück | Drei Minuten leicht Tempo herausnehmen; erste Orientierung 20–30 s/km langsamer als vorheriger ruhiger Abschnitt, bei Bedarf gehen | Ein Hinweis, nach zwei Minuten HR-Verlauf prüfen. Tempoanpassung kann erfüllt sein, auch wenn HR noch nicht sinkt; beide Ergebnisse getrennt nennen. | Belastungssteuerung üben, kein versprochener Soforteffekt. Bei fehlendem Puls nur Tempoaufgabe. |
| M05 Referenzstück | Bekannte 500–800 m nach Warm-up ungefähr im Tempo des letzten passenden Referenzstücks (±15 s/km) | Start/Ende fest, Paceabweichung währenddessen korrigieren. Aufgabe erfüllt bei >=80 % Zeit im Band; Pulsdifferenz separat als Messung, nie als Siegbedingung. | Vergleichsdaten sammeln. Keine Jagd auf den vorherigen Bestwert. |
| M06 Mit Reserve ins Ziel | Letzte ca. drei Minuten einer erkannten Runde im ruhigen Rhythmus, kein Endspurt | Ein Hinweis gegen anhaltendes Beschleunigen; Erfolg bei gehaltenem/ruhigerem Tempo und genügend Daten. Beenden bleibt Nutzerentscheidung. | Abschlusskontrolle. Routenende muss belastbar erkannt sein; kein „Zuhause“ allein aus GPS ableiten. |
| M07 Kontrollierte Steigerung | Drei Phasen à zwei Minuten: ruhig, etwas zügiger, wieder ruhig; mittlere Phase zunächst 10–15 s/km schneller als ruhige Referenz, nie Richtung unbewiesener Zielpace zwingen | Phasenwechsel ansagen; Erfolg: vereinbarte Anpassung und Rücknahme, nicht maximaler Puls. | Als intensive Mission zählen. Nur mit Tempoauftrag und Referenztempo. Kein abschließender All-out-Abschnitt. |
| M08 Kurze Steigerungen | Viermal 15 s flüssig beschleunigen, jeweils 75 s locker; nicht sprinten | Zeitansagen und Erholungen; keine präzise GPS-Pace oder HR-Zahl für 15 s jagen. Ausführung nur über gültige Bewegungsdaten und abschließende Selbsteinschätzung bestätigen; sonst „begleitet“, nicht objektiv bestanden. | Intensivfreigabe, vorab bestätigter freier/übersichtlicher Abschnitt. GPS allein erkennt keinen sicheren Weg. Kein behaupteter Muskelaufbau. |
| M09 Wechselspiel | Viermal eine Minute kontrolliert zügig mit 90 s locker dazwischen, insgesamt zehn Minuten einschließlich letzter Erholung | Jede Phase ansagen; schnelle Zielpace aus verträglichem Arbeitsstand, nicht 10-km-Wunschtempo. Erholung hat Vorrang; bei fehlendem Puls keine neue schnelle Wiederholung. | Geplante intensive Mission, keine Überraschung in einer ruhigen Runde. |
| M10 Tempo halten | Dreimal zwei Minuten am aktuellen bestätigten Trainingsziel, jeweils zwei Minuten locker, insgesamt zwölf Minuten | Repräsentative Pace nach der Beschleunigung prüfen; zu schnelles Loslaufen korrigieren. Ergebnis nennt absolvierte Abschnitte, Pace und Erholung, nicht nur Gesamtzeit. | Strukturierte Tempoeinheit. Für 25-min-Runde meist zu lang: nur bei vorab bestätigter Verlängerung/genügend Restzeit. |

M01-Zielpace: robuste Zeit/Strecke-Pace der unmittelbar vorherigen zwei gültigen ruhigen Minuten; vor Start ansagen und für diesen Versuch einfrieren. Bei fehlender stabiler Grundlage Aufgabe verschieben.

M01-Korrekturlogik im Detail: ab 200 m Aufgabenfortschritt mindestens 30 s und 75 m verwertbare Bewegung für laufende Pace verlangen. Abweichung >15 s/km für 20 s => Korrektur, frühestens nach 60 s erneute gleichartige Meldung. Hälfte zwei orientiert sich zusätzlich an Hälfte eins; Band nicht an die gerade zu schnelle Pace nachführen. Bei +30 s/km Beschleunigung klar sagen „Du bist schneller als in der ersten Hälfte; heute geht es ums Halten.“ Ende exakt bei +1000 m interpolieren, nicht erst am nächsten 500-m-Cue. GPS-Sprünge verwerfen; keine Interpolation über Lücken >10 s. Ähnliche Halbmittel allein reichen nicht, wenn Sprint-/Gehwechsel sie erzeugen.

Für M07/M09/M10: Zielpace aus bestehendem Trainer übernehmen, provenance speichern. Fehlt ein verlässlich verträgliches Ausgangstempo, nicht anbieten. Kurze Intervalle primär mit Zeit, Tempo und Belastungsgefühl steuern; HR reagiert verzögert. M09/M10 bei längerem Ausfall von HR in Erholung beenden; keine Bestrafung. Keine der Aufgaben wird als medizinisch validiertes HIIT-Protokoll beworben.

## 8. Gamification ohne falsche Anreize

Drei sichtbare Fortschrittsbereiche in vorhandenen Karten: **Rhythmus, Belastung steuern, Tempo aufbauen**. Zehn Aufgaben als Sammlung, keine neue Hauptnavigation.

- Jede objektiv erfüllte Mission gibt genau einen Missionspunkt; gleiche Punktzahl für ruhige und intensive Aufgaben. Maximal zwei pro Lauf. Datenlücken/Abbruch geben keine Minuswerte; keine täglichen Streaks, die Erholung bestrafen.
- M04 bekommt den Punkt für die nachvollziehbare Tempoanpassung, nicht für eine erzwungene Pulsreaktion. M08 erhält ohne bestätigte Ausführung nur Teilnahme, keinen objektiven Punkt.
- Abzeichen sind transparent: z. B. „Rhythmus gefunden“ nach drei erfolgreichen M01/M02 an unterschiedlichen Tagen, „Reserve behalten“ nach drei M06. Keine automatischen physiologischen Levelbezeichnungen wie „Herzfitness +10“.
- Neue Intensität wird niemals durch XP freigeschaltet. Schwierigkeit nur nach zwei gelungenen geeigneten Durchführungen an unterschiedlichen Tagen, passender RPE (intensiv <=7/10), keiner aktiven Entlastung und ausreichender Erholung. Pro Schritt eine Variable: zunächst Wiederholung ODER Dauer, nicht zugleich schneller und länger. Bestehende Wochenbegrenzung bleibt.
- Lob bezieht sich auf Tatsachen: „Die zwei Hälften lagen sechs Sekunden auseinander – Aufgabe geschafft.“ Bei 30 s Unterschied: „Du hast beschleunigt. Das Gleichmäßigkeitsziel war heute noch nicht erfüllt.“ Freundlich, ohne erfundenen Erfolg.

## 9. Faire Vergleiche und Fortschritt

Keinen Gesamtscore aus Pace/HR dividieren: HR ist kein linearer Energieverbrauch, niedrigere HR kann verschiedene Ursachen haben. Primär zwei Zahlen auf demselben Abschnitt zeigen: Paceänderung in s/km und HR-Änderung in bpm, jeweils mit Ausgangswerten und Referenzdatum.

Zwei Vergleichsebenen:

1. Ganze Runde: Distanz, aktive Zeit, gesamte verstrichene Zeit inklusive Pausen, Durchschnittspace. Bei unterschiedlichen Strecken nur deskriptiv nebeneinanderstellen; kürzere Strecke nicht als schnellere Runde gewinnen lassen. Zielerreichung weiterhin mit vollständiger Zieldistanz und verstrichener Zeit.
2. Belastungsvergleich: dieselben räumlichen Abschnitte und Laufrichtung, gleiche Tagesform, gleiches Laufmedium und kompatibler Trainingsauftrag. Nach Warm-up, nicht unmittelbar nach Pause/Tempoabschnitt. Ruhige Missionen und Tempoaufgaben getrennt. Mindestens 500 m UND drei Minuten je Abschnitt, >=80 % zeitgewichtete HR-Abdeckung, ausreichende GPS-Abdeckung ohne Lücke >10 s. Kein Vergleich aus bloß gleicher Kilometerzahl verschiedener Strecken.

Referenz vor der Bewertung wählen: der letzte passende Lauf, nicht derjenige mit dem günstigsten Unterschied. Tagesform vor Limitierung der Historie filtern. Falls der letzte Lauf nicht passt und ein älterer verwendet wird, dessen Datum ausdrücklich nennen. Keine positive Teilstrecke auswählen und als Ergebnis des ganzen Laufs ausgeben. Mehrere nicht überlappende passende Abschnitte zeit-/distanzgerecht aggregieren; Umfang und Ausnahmen speichern. Unter 500 m/3 min nur Daten anzeigen, keine Effizienzbewertung.

Produktparameter für sprachliche Kategorien: Pace ±10 s/km = ähnlich; HR ±3 bpm = ähnlich, ausdrücklich keine statistische Signifikanzgrenze. Unterschiede immer konkret nennen. Toleranzen konfigurierbar und später anhand Test-Retest-Daten prüfen.

| Ergebnis auf passenden Abschnitten | Formulierung |
|---|---|
| Deutlich schneller, ähnlicher/niedrigerer Puls | „Heute schneller bei ähnlichem/geringerem Puls – ein günstiger Vergleich.“ |
| Ähnliche Pace, deutlich weniger Puls | „Heute ähnliches Tempo mit weniger Puls.“ |
| Schneller und deutlich höherer Puls | „Heute schneller und stärker belastet; daraus allein folgt kein Effizienzgewinn.“ |
| Langsamer, niedrigerer Puls | „Heute ruhiger unterwegs.“ Kann den Tagesauftrag erfüllen. |
| Langsamer und höherer Puls | „Heute bei geringerem Tempo höherer Puls; Ursache aus den Daten offen.“ Nicht „Fitness verloren“. |
| Beide ähnlich | „Auf diesem Abschnitt ähnlich wie zuletzt.“ |
| Unpassende Daten | Konkreten Grund nennen; keine Zahl erfinden. |

Wetter/Wind/Untergrund, Tagesform und Sensorwechsel sind mögliche Störgrößen. Keine neue Wetter-API nötig; optionaler Nachlaufkontext „ungewöhnlich warm/windig, hügelig, beschwerlich“ in vorhandener Nachlaufkarte. Unbekannter Kontext => Vergleich vorläufig, nicht „kontrolliert“. Eine fehlende Angabe darf keine positive Annahme erzeugen.

Längerfristige Anzeige erst bei mindestens drei passenden Referenzläufen: alle Werte anzeigen; wiederholt günstige Vergleiche als „Hinweis auf Fortschritt“, nicht als bewiesene Fitnesssteigerung. Keine scheinpräzise Prozent-Prognose. Ändern sich Kalibrierung oder Protokoll, Vergleichsgrundlage prüfen und Grund speichern; Rohpulswerte nicht rückwirkend umdeuten.

## 10. Zusammenfassung nach dem Cooldown

Sobald Run-Ende lokal gesichert ist: zunächst nur „Lauf gespeichert; ich messe die Erholung“. Nach vollständiger oder abgebrochener Cooldown-Messung genau eine Summary mit maximal ca. 35 Sekunden Audio. Ohne mögliche Cooldown-Messung direkt danach. Audio aus => dennoch gespeicherte Textfassung und Wiedergabe-Button. Jeder gespeicherte Lauf bekommt einen Abschluss, auch ohne Vergleich oder erfolgreich absolvierte Mission.

Reihenfolge: (1) Distanz + aktive Zeit, Pausenzeit nur wenn relevant; (2) Tagesauftrag/Missionsergebnis; (3) bester verfügbarer fairer Vergleich nach vorab gewählter Referenz; (4) gemessener Pulsabfall im Cooldown, falls auswertbar; (5) ein begründeter nächster Schritt. „Bester verfügbarer“ meint Datenqualität, nicht positivstes Ergebnis.

Beispiel mit fiktiven Zahlen: „Vier Kilometer in 25 Minuten aktiver Zeit. Dein gleichmäßiger Kilometer ist gelungen. Auf den vergleichbaren Abschnitten warst du zwölf Sekunden pro Kilometer schneller als am Sonntag, bei zwei Schlägen weniger Puls. In der Minute nach dem Speichern sank dein Puls um 24 Schläge. Beim nächsten ruhigen Lauf halten wir diesen Rhythmus.“

Gemischtes Ergebnis: „Heute zwölf Sekunden schneller, aber acht Schläge mehr Puls. Das war ein intensiverer Lauf; einen Effizienzgewinn kann ich daraus nicht ableiten.“ Bei Nicht-erfüllt die Mission ehrlich benennen; kein Trost-Fitnesslob.

Cooldown: gespeicherte Rohwerte/Score erhalten, in Audio primär Pulsabfall nennen. Den bestehenden persönlichen Score nicht in XP oder medizinische Bewertung übersetzen. Unterscheiden zwischen Belastungsende und Beginn beim Speichern; Verzögerung und Erholungsart speichern (`UNKNOWN`, `WALKING_SELF_REPORTED`, `STANDING_SELF_REPORTED`). Vergleich zur früheren Erholung nur bei passendem Protokoll, sonst ausschließlich Einzelwert. Zum Erfüllen eines Scores niemals abruptes Stehenbleiben fordern. Unterbrochene Messung bekommt keinen 60-s-Wert.

Technik: Summary strukturiert und mit stabiler ID persistieren, bevor Audio startet. Health-Connect-Export darf Audio weder verhindern noch duplizieren. Dienstzustand `FINALIZING_AUDIO` hält Audio/Fokus kurz am Leben; Tracking, Sensor und GPS werden nach bestehendem Abschluss freigegeben. Auf TTS onDone/onError warten, maximal 45 s; dann aufräumen. UI bleibt bedienbar, Stop/Audio-aus bricht Sprache ab. Keine automatische Wiederholung nach Prozessneustart; gespeicherte Summary manuell abspielbar. Bei Speicherversagen nicht fälschlich „gespeichert“ melden. Neuer Lauf beendet veraltete Summary-Sprache.

## 11. Audio-Regeln und kleine UI-Ergänzungen

- Datenauswertung unabhängig von Audio. Jedes Cue trägt runId, missionId, Kategorie, Zustandsversion, Erzeugungszeit, Gültigkeitsende und Begründung.
- Priorität: dringende Belastungs-/Statusänderung, Phasenwechsel/aktive Korrektur, Distanzinfo, allgemeine Motivation. Normale Cues unterbrechen einander nicht; überholte Anweisungen aus der Queue entfernen. Dringende Intervention darf ausdrücklich überholen/unterbrechen; Verhalten testen statt alles FIFO zu lassen.
- Gleiche Korrektur frühestens nach 60 s, allgemeine Hinweise frühestens nach 90 s. Aufgabengrenzen und notwendige Belastungshinweise dürfen früher kommen. Alle fälligen Hinweise eines Zeitpunkts in einer kurzen Ansage bündeln; eine Korrektur verdrängt eine Distanzinfo, die Distanz gilt trotzdem als behandelt.
- Pro Mission mindestens Start, ein sinnvoller Zwischenstand bei Dauer >3 min und eindeutiges Ergebnis. Knappe Ansagen alle 500 m bleiben, bekommen Handlungskontext. Fortschrittslob weiterhin selten; Informationsfluss darf nach dem Lobbudget nicht abbrechen.
- Auf kurzen Runden zweite Mission nur nach erfüllter Eignung. Ohne zweite Aufgabe ausdrücklich „Aufgabe erledigt; den Rest halten wir ruhig“, nicht plötzlich minutenlang verschwinden. Zwischenstände und Ergebnis dürfen nicht auf nächste Distanzmarke warten.
- Bestehende Karten: Lernstand x/3 + konkreter fehlender Baustein; heutiger Auftrag/Zielband mit Herkunft; aktuelle Mission/Fortschritt; Ergebnis mit Referenzdatum, Zahlen und Datenqualität. „Aufgabe überspringen“ und optional „heute zu leicht/passend/zu schwer“ in vorhandenen Flächen. Keine Sprachsteuerung oder AirPods-Tasten voraussetzen; als spätere Erweiterung kennzeichnen.
- Grafik: Achse als Skala beschriften, gemessene Min/Max getrennt; Warm-up, Pause, Mission und Erholung auf Zeitachse markieren. Vergleich nicht allein über verschieden skalierte Charts. Keine neue Startseite.

## 12. Datenmodell und Implementierungsaufteilung

Neue reine Kotlin-Komponenten; Regeln nicht als zusätzlichen großen Textzweig in `Coach.update` anbauen:

- `LearningEvaluator` / `LearningSnapshot`: qualifizierende Run-IDs, Ablehnungsgründe, observedEasyPace, optionale HR-Arbeitsbandquelle, confidence, Profil-/Algorithmusversion.
- `SessionPlan`: Tagesauftrag, Warm-up, Zielquelle, zulässige Intensität, geschätztes Rundenbudget, Seed, maximal zwei Missionen. Snapshot pro Lauf unveränderlich; Live-Anpassungen als Ereignisse.
- `MissionDefinition` + `MissionAttempt`: ID, Version, Phasen, unveränderliche Start-/Endanker, Zielband, Zwischenmetriken, Zustand, Unterbrechungsgrund, Ergebnis, Progression. Fortschrittsdaten deterministisch aus dem Eventstream oder abgesicherten Snapshots.
- `EffortEvaluator`: getrennte Kurzzeit-/Langzeitbeobachtung, Entlastung und Rückmeldung; liefert strukturierte Entscheidung, nicht nur String. Keine neue Zielgrenze aus einzelnen hohen Pulsen.
- `ComparisonEngine` / `ComparisonResult`: Referenz-ID/Datum, Abschnittsanker, Coverage, Zeiten/Distanzen, HR-Differenz, Pace-Differenz, Kategorie, Einschränkungen, Version. `RouteMatcher` liefert Geometrie, nicht endgültige Erfolgsprosa.
- `RunSummary`: strukturierte Fakten, mission results, optionaler Vergleich, Cooldownprotokoll, gerenderter Text, speechStatus und ID. `Narrator` erzeugt Text ausschließlich aus diesen Daten.
- `AudioScheduler`: Zustands-/Gültigkeitsprüfung vor Abspielen, Zusammenführung, Priorität, Ende-Callback.
- `Domain.kt`, `Codec.kt`, `Storage.kt`: additive optionale Felder mit Legacy-Defaults; keine historische Aufgabe nachträglich als erfüllt erfinden. Berechnungen bei Löschung/Aktualisierung einer Referenz invalidieren; Ergebnis-Snapshot als historisch berechnet kennzeichnen. Rohdaten und Health-Connect-Revisionen konsistent erhalten. Alte Pulszonen in gespeicherten Runs bleiben Snapshots; neue Quellenfelder erhalten UNKNOWN/Legacy-Defaults, keine behauptete rückwirkende Kalibrierung.
- `RunService.kt`: Zustandsübergänge, Pause, Review-Abbruch, Cooldown und Audio-Ende integrieren; doppelte Save-Aktionen idempotent. `MainActivity.kt` / `ProfileScreens.kt`: nur notwendige Karten-/Text-/Bedienergänzungen.
- Lange Historien nicht pro Sekunde vollständig scannen; Referenz-/Lerncache bei Run-/Profiländerung. Synthetische Replay-Schnittstelle mit injizierter monotoner Uhr für Missionen und Scheduler.

## 13. Umsetzungsreihenfolge

1. **P0 – Wahrheit und Abschluss:** feste Missionsanker, Zwischenkorrektur, ehrlicher Nicht-erfüllt-Zustand, späterer Start, strukturierter Vergleich, Summary inklusive vollständigem Audio-Lebenszyklus. Zunächst M01/M02/M04/M06. Nach diesem Paket ist das aktuelle Erlebnis bereits deutlich vollständiger.
2. **P1 – Sichtbares Lernen:** getrennte Nutzereingabe/beobachtete Werte, Fortschritt x/3, Quellen/Unsicherheit der Pulsziele, kontextuelle Distanzansagen, langsamere Drift, M03/M05.
3. **P2 – Abwechslung mit Trainingsplan:** Auswahl/Rotation, alle zehn Missionen, geplante intensive Aufgaben mit bestehenden Freigaben und Wochenlast, Abzeichen. Kein zufälliges HIIT vor Abschluss der Intensitäts-/Erholungsregeln.
4. **P3 – Praxistest und Nachjustierung:** echte freigegebene Rohdaten lokal wiedergeben, auf Gerät Audio/Musik/Display-aus/Cooldown prüfen; Zahlenparameter anhand realer Rückmeldungen anpassen. Keine Roh-GPS-/Gesundheitsdaten ins öffentliche Repository. Build, GitHub-Release, Handyinstallation und Firebase-Upload jeweils nur im dann ausdrücklich beauftragten Umfang.

## 14. Abnahmekriterien für den Coding-Agenten

- [ ] Antworten auf alle sechs Feedbackpunkte sind in UI/Audio/Daten sichtbar, nicht nur in Texten beschrieben.
- [ ] Drei beliebige Läufe ohne RPE/HR erfüllen keinen behaupteten persönlichen Lernstand. Drei geeignete Läufe liefern nachvollziehbare Werte auch bei gesetztem usualPace; alte Nutzereingabe bleibt erhalten.
- [ ] Natürlicher Startanstieg erzeugt keine pauschale Bremsansage. Zu schneller Einstieg relativ zum vereinbarten Plan wird begründet, ohne persönliche Herzgrenze zu erfinden.
- [ ] Jede 500-m-Pulsmeldung kann Ziel/Herkunft/Handlung erklären; 149 bpm wird je nach Plan unterschiedlich und bei Unwissen ausdrücklich vorsichtig eingeordnet.
- [ ] M01 mit 6:30 und 6:00 min/km: Korrektur während Hälfte zwei, Ergebnis NOT_MET, kein Erfolgsabzeichen; nachfolgender gleichmäßiger Kilometer ändert diesen Versuch nicht.
- [ ] M01 mit ähnlichen Halbmitteln, aber Sprint-/Gehwechseln besteht nicht allein aufgrund dieser Mittel. Ergebnis exakt an festem Aufgabenende auch bei GPS-Überschreiten einer Marke.
- [ ] Eine Pause, GPS-Sprung, fehlender Puls, Relaunch, Review-Abbruch und frühes Beenden produzieren keine falschen Erfolge oder nachträglichen Tempoaufforderungen.
- [ ] 25-min-/4-km-Replay enthält verständlichen Start, spätere Aufgabe, Zwischenstand, Ergebnis, Weiterbegleitung und Schlussansage; keine willkürliche Pflicht zur zweiten Aufgabe.
- [ ] Gleicher Seed/Historie => gleiche zulässige Auswahl. TIRED/UNWELL und gesperrte Qualitätstage erhalten keine M07–M10. Intensive EASY-Mission zählt zur Wochenlast/Tempo-Sperre.
- [ ] Leistungsresultate decken alle sechs Vergleichskategorien plus fehlende Daten ab. Schneller + höherer Puls ist weder automatischer Sieg noch Niederlage. Unterschiedliche Tagesform/Routen/Richtungen werden nicht vermischt.
- [ ] Zeitgewichtete HR-/Pace-Aggregate funktionieren bei unregelmäßigen Samples und fehlenden Intervallen; keine Doppelzählung überlappender Vergleichsfenster.
- [ ] Summary nach 60 s Cooldown, vorzeitigem Ende, fehlendem Sensor, Audio-aus, TTS-Fehler, Exportfehler und doppeltem Speichern: immer gespeichertes Ergebnis, keine abgeschnittene oder doppelte automatische Ansage.
- [ ] Keine Warteschlangenansage fordert Tempo, wenn Mission mittlerweile beendet, Pause aktiv oder Entlastung vorrangig ist.
- [ ] Alle Legacy-Runs bleiben lesbar; Referenzlöschung/Profiländerung/Prozesswiederherstellung beschädigen weder Lauf noch neue Ergebnisfelder.
- [ ] Tests zuerst gezielte JVM-Replays; danach Android-Instrumentierung für Lebenszyklus/Audio-Ende/Persistenz. Praxistest mit AirPods/Musik separat ausweisen. Keine Aussage „auf dem Handy getestet“, wenn nur Emulator oder Replay lief.

## 15. Fertige Produktentscheidung für die Übergabe

Erst verständlich begleiten und ehrlich auswerten, dann abwechslungsreicher machen. Das Ziel ist ein Coach, der seinen Auftrag erklärt, eine Aufgabe beobachtet, rechtzeitig korrigiert und danach mit konkreten Zahlen sagt, was passiert ist. Der Nutzer gewinnt auch durch einen gut ausgeführten ruhigen Lauf. Höhere Intensität ist eine geplante Trainingsentscheidung, keine Belohnung für Langeweile.
