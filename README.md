# Laufklar

**Laufklar** ist eine schlanke, datenschutzfreundliche Android-Lauf-App (Kotlin & Jetpack Compose), die Outdoor- und Laufbandläufe lokal auf dem Gerät aufzeichnet, strukturiertes Training unterstützt und optional mit Android Health Connect synchronisiert.

## Funktionen

- **Outdoor- & Laufband-Modus**:
  - **Draußen**: GPS-Routenaufzeichnung mit Genauigkeits- und Ausreißerfilter sowie Kartendarstellung über OpenStreetMap (`osmdroid`, Online-Karten optional deaktivierbar).
  - **Laufband**: Zeit- und Phasen-Tracking ohne GPS inklusive nachträglicher Distanzkorrektur.
- **Strukturierte Trainingsformate**:
  - *Locker laufen*
  - *Standortbestimmung*
  - *Länger locker* (progression über 12 Planwochen)
  - *Tempo aufbauen* (Intervalltraining, optional aktivierbar, mit 7-Tage-Schutz vor Überlastung)
- **Pulszonen & BLE-Sensoren**:
  - Anbindung externer Bluetooth-Low-Energy-Pulssensoren (Standard-GATT Heart Rate Service `0x180D`).
  - Berechnung der Pulszonen wahlweise über Alter (`208 - 0,7 × Alter`), gemessenen Maximalpuls, Herzfrequenzreserve (mit Ruhepuls) oder benutzerdefinierte Zonengrenzen.
- **Offline-Audio-Coaching**:
  - Sprachansagen über Android Text-to-Speech (TTS) bei Phasenwechseln und in konfigurierbaren Distanzintervallen.
- **Health Connect (optional)**:
  - Lokaler Export abgeschlossener Läufe (Trainingseinheit, Herzfrequenz, Distanz, Geschwindigkeit und optional Route) an Android Health Connect.
- **Datenschutz & lokale Speicherung**:
  - Alle Profildaten und Läufe werden ausschließlich lokal gespeichert und per **Android Keystore (AES-GCM)** verschlüsselt (`allowBackup="false"`, `usesCleartextTraffic="false"`).

## Voraussetzungen & Tech-Stack

- **Android**: `minSdk 34` (Android 14+), `compileSdk 36`, `targetSdk 36`
- **Sprache & UI**: Kotlin `2.1.20`, Jetpack Compose (Material 3), Java `17`
- **Build-System**: Gradle `8.11.1` (Wrapper enthalten), Android Gradle Plugin `8.9.2`
- **Wichtige Abhängigkeiten**:
  - `androidx.health.connect:connect-client`
  - `org.osmdroid:osmdroid-android`
  - `kotlinx-coroutines-android`

## Projektstruktur

```text
app/src/main/java/de/laufklar/app/
├── Domain.kt           # Datenmodelle (Profile, Run, Workout), Trainingslogik & Metriken
├── HealthExporter.kt   # Export zu Android Health Connect
├── HeartRateSensor.kt  # BLE-Herzfrequenzsensor-Scanner & GATT-Client
├── MainActivity.kt     # Haupt-UI & Navigation (Laufen, Verlauf, Entwicklung, Profil)
├── ProfileScreens.kt   # Profil-, Pulszonen-, Sensor- & Datenschutzeinstellungen
├── RouteMap.kt         # OpenStreetMap-Routenansicht (osmdroid)
├── RunService.kt       # Foreground Service für Tracking, Timer & TTS-Coaching
└── Storage.kt          # AES-GCM-verschlüsselte lokale Persistenz
```

## Lokal bauen & testen

1. **Repository klonen** und in das Projektverzeichnis wechseln:
   ```bash
   git clone <REPO-URL>
   cd laufklar
   ```
2. **Android SDK konfigurieren**: Stelle sicher, dass `ANDROID_HOME` gesetzt ist oder eine lokale `local.properties` mit `sdk.dir=/pfad/zum/android/sdk` existiert (wird von Git ignoriert).
3. **Unit-Tests ausführen**:
   ```bash
   ./gradlew test
   ```
4. **Debug-APK bauen**:
   ```bash
   ./gradlew assembleDebug
   ```

