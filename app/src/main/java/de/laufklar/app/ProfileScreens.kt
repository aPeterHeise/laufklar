package de.laufklar.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

@Composable fun ProgressScreen(runs:List<Run>,profile:Profile){
    val weeks=(3 downTo 0).map{weekRuns(runs,it)};val maxDistance=weeks.maxOf{it.sumOf{r->r.distance}}.coerceAtLeast(1000.0)
    val benchmarks=runs.filter{it.workout==Workout.BASELINE}.mapNotNull{r->Metrics.benchmark(r)?.let{Triple(r,it.first,it.second)}}
    Page{
        Heading("Fortschritt,\nder sich aufbaut.","Dein Maßstab bist du selbst. Vergleiche ähnliche Strecken, Temperaturen und Tagesformen.")
        CardBlock{
            Eyebrow("Vier Wochen in Bewegung")
            Row(Modifier.fillMaxWidth().height(150.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Bottom){weeks.forEachIndexed{i,w->
                val meters=w.sumOf{it.distance}
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(Metrics.km(meters),fontSize=12.sp,color=if(i==3)Lime else Muted)
                    Box(Modifier.fillMaxWidth().height((meters/maxDistance*95).coerceAtLeast(4.0).dp).clip(RoundedCornerShape(8.dp)).background(if(i==3)Lime else Color(0xFF56664D)))
                    Text(if(i==3)"7 Tage"else "−${3-i} Wo.",color=Muted,fontSize=11.sp)
                }
            }}
            Text("Rollierende 7-Tage-Zeiträume · Kilometer",color=Muted,fontSize=11.sp)
        }
        CardBlock{
            Eyebrow("Dein Weg zu 10 km in 50:00",Lime)
            Text("Woche ${profile.planWeek} von 12",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
            Text(when(profile.planWeek){in 1..3->"Zuerst Routine und lockere Ausdauer. Zwei entspannte Läufe pro Woche, optional ein kurzer dritter. Eine Standortbestimmung ersetzt einen lockeren Lauf.";in 4..8->"Locker bleibt die Basis. Wenn die Arzt-Rückfrage geklärt ist und du dich gut erholst, kann eine kontrollierte Tempoeinheit einen lockeren Lauf ersetzen.";else->"Längere lockere Läufe und höchstens eine Tempoeinheit pro Woche. Das Zieltempo 5:00 ist eine Richtung; ein 50-Minuten-Lauf ist noch keine Zusage."},color=Muted,lineHeight=23.sp)
            Text("Mindestens ein freier Tag zwischen Läufen. Bei ungewöhnlicher Müdigkeit, Krankheit oder Schmerzen reduzieren oder pausieren. Wochen nur weiterstellen, wenn die aktuelle Belastung gut vertragen wird.",fontSize=12.sp,color=Muted,lineHeight=19.sp)
        }
        CardBlock{
            Eyebrow("Standortbestimmung")
            Text("Gleicher Puls. Mehr Tempo?",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
            Text("Wähle eine flache Strecke oder dasselbe Laufband mit gleicher Steigung. 10 Minuten locker aufwärmen, dann 20 Minuten gleichmäßig und in ganzen Sätzen sprechend laufen. Kein Maximaltest. Alle 2–4 Wochen unter ähnlichen Bedingungen wiederholen.",color=Muted,lineHeight=23.sp)
            if(benchmarks.isEmpty())Text("Noch kein auswertbarer Vergleich. Benötigt werden 20 gleichmäßige Minuten nach dem Aufwärmen und mindestens 80 % Pulsabdeckung.",color=Lime,fontSize=13.sp)
            benchmarks.take(6).forEach{(r,pace,hr)->
                HorizontalDivider(color=Muted.copy(alpha=.2f));Text(dateText(r.started),fontSize=12.sp,color=Muted)
                Row(Modifier.fillMaxWidth()){Stat(Metrics.pace(pace),"min/km nach Warm-up",Lime);Stat(hr.roundToInt().toString(),"Ø bpm")}
                Metrics.drift(r)?.let{drift->Text("Puls/Tempo-Verhältnis, zweite Hälfte: ${"%+.1f".format(Locale.GERMANY,drift)} %. Beschreibender Wert; keine Gut-/Schlecht-Grenze.",color=Muted,fontSize=12.sp)}
            }
            if(benchmarks.size>=2){val a=benchmarks[0];val b=benchmarks[1]
                if(a.first.mode==b.first.mode&&abs(a.third-b.third)<=5){val delta=b.second-a.second;Text("Letzte zwei vergleichbare Läufe: ${abs(delta).roundToInt()} s/km ${if(delta>=0)"schneller"else "langsamer"}, bei ähnlichem Durchschnittspuls (±5 bpm). Ein einzelner Vergleich ist noch kein Trend.",color=Lime,fontSize=13.sp)}
                else Text("Die letzten Vergleichsläufe unterscheiden sich im Puls oder Modus. Daraus wird kein Fortschrittsurteil berechnet.",color=Muted,fontSize=12.sp)
            }
        }
        CardBlock{Eyebrow("Was deine Werte nicht sagen");Text("Es gibt keinen universellen Soll-Puls bei einer bestimmten Pace. Hitze, Steigung, Stress, Schlaf, Alkohol, Medikamente und Messfehler können den Puls verändern. 83 kg und männlich bestimmen deine Trainingszonen nicht.",color=Muted,lineHeight=23.sp);Text("Die App schätzt weder VO₂max noch eine Diagnose. Deine ärztliche Aussage zur Zone 2 solltest du vor intensiveren Einheiten konkretisieren lassen.",color=Peach,fontSize=13.sp)}
    }
}
@Composable fun ProfileScreen(profile:Profile,onSave:(Profile)->Unit,onConnect:()->Unit,onHealth:()->Unit,onPrivacy:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var age by remember(profile){mutableStateOf(profile.age.toString())};var weight by remember(profile){mutableStateOf(profile.weight.toString())}
    var sex by remember(profile){mutableStateOf(profile.sex)};var maximum by remember(profile){mutableStateOf(profile.measuredMax?.toString()?:"")};var resting by remember(profile){mutableStateOf(profile.restingHr?.toString()?:"")}
    var reserve by remember(profile){mutableStateOf(profile.reserveZones)};var custom by remember(profile){mutableStateOf(profile.customBounds?.joinToString(", ")?:"")}
    var audio by remember(profile){mutableStateOf(profile.audio)};var cue by remember(profile){mutableIntStateOf(profile.cueMeters)};var maps by remember(profile){mutableStateOf(profile.onlineMaps)}
    var health by remember(profile){mutableStateOf(profile.healthExport)};var route by remember(profile){mutableStateOf(profile.shareRoute)};var ready by remember(profile){mutableStateOf(profile.intensityReady)}
    var week by remember(profile){mutableIntStateOf(profile.planWeek)};var pace by remember(profile){mutableStateOf(profile.usualPace.toString())};var error by remember{mutableStateOf<String?>(null)}
    val speech=remember{AudioCoach(context)};DisposableEffect(Unit){onDispose{speech.close()}}
    fun draft():Profile?{
        val a=age.toIntOrNull();val w=weight.replace(',','.').toDoubleOrNull();val max=maximum.toIntOrNull();val rest=resting.toIntOrNull();val p=pace.toIntOrNull()
        val bounds=if(custom.isBlank())null else custom.split(',',';').map{it.trim().toIntOrNull()}
        if(a==null||w==null||p==null||(maximum.isNotBlank()&&max==null)||(resting.isNotBlank()&&rest==null)||(bounds!=null&&bounds.any{it==null})){error="Bitte gültige Zahlen eingeben. Zonengrenzen mit Komma trennen.";return null}
        val draft=profile.copy(age=a,weight=w,sex=sex,measuredMax=max,restingHr=rest,reserveZones=reserve,customBounds=bounds?.filterNotNull(),audio=audio,cueMeters=cue,onlineMaps=maps,healthExport=health,shareRoute=route,intensityReady=ready,planWeek=week,usualPace=p)
        error=draft.validate();return draft.takeIf{error==null}
    }
    Page{
        Heading("Dein Profil.","Deine Grenzen. Dein Rhythmus.")
        CardBlock{
            Eyebrow("Ausgangspunkt")
            Field("Alter in Jahren",age,{age=it});Field("Gewicht in kg",weight,{weight=it})
            Field("Geschlecht (frei editierbar)",sex,{sex=it},false)
            Field("Bisherige Pace · Sekunden/km",pace,{pace=it})
            Text("345 Sekunden = 5:45 min/km. Geschlecht und Gewicht dienen der Dokumentation, nicht als Fitnessurteil.",color=Muted,fontSize=12.sp)
        }
        CardBlock{
            Eyebrow("Pulszonen")
            Text(if(profile.estimated)"Vorläufige Schätzung"else "Persönliche Grenzen",fontSize=23.sp,fontWeight=FontWeight.SemiBold)
            Text("Aktuell: ${if(profile.customBounds!=null)"manuell"else if(profile.reserveZones)"Herzfrequenzreserve"else "% des Maximalpulses"}. ${if(profile.estimated)"Maximalpuls per 208 − 0,7 × Alter: ${profile.maxHr} bpm."else ""}",color=Muted,fontSize=12.sp)
            (1..5).forEach{zone->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Zone $zone",color=ZoneColors[zone]);Text("${profile.bounds[zone-1]}–${profile.bounds[zone]-if(zone==5)0 else 1} bpm",color=Cream)}}
            Text("Das ist eine konventionelle Fünf-Zonen-Einteilung (50–60–70–80–90–100 %). Sie lokalisiert keine individuellen Laktat- oder Atemschwellen. Zone 2 bei verschiedenen Anbietern kann etwas anderes bedeuten.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
            Field("Gemessener Maximalpuls · optional",maximum,{maximum=it});Field("Ruhepuls · optional",resting,{resting=it})
            Toggle("Herzfrequenzreserve verwenden","Nur mit gemessenem Ruhepuls. Andere Grenzen als %HFmax.",reserve,{reserve=it})
            Field("6 persönliche Zonengrenzen · optional",custom,{custom=it},false)
            Text("Format: untere Grenze Z1, Beginn Z2, Z3, Z4, Z5, obere Grenze Z5. Beispiel: 90, 110, 130, 150, 165, 185. Manuelle Grenzen haben Vorrang. Gemessene Werte bitte aus dem Befund übernehmen; keinen Maximaltest auf eigene Faust.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
        }
        CardBlock{
            Eyebrow("Audio-Coach")
            Toggle("Ansagen während des Laufs","Pace, Gesamtstrecke, Puls und nächster Trainingsschritt",audio,{audio=it})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(250,500,1000).forEach{m->FilterChip(selected=cue==m,onClick={cue=m},label={Text("$m m")})}}
            Text("Bei länger erhöhtem Puls kommt ein Hinweis bereits vor der nächsten Distanzansage. Intervallwechsel erfolgen nach Zeit. Ohne frischen Puls fordert der Coach keine Temposteigerung.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
            OutlinedButton(onClick={speech.speak("Laufklar ist bereit. Ein Kilometer. Pace sechs Minuten pro Kilometer. Locker bleiben und in ganzen Sätzen sprechen.")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.VolumeUp,null);Spacer(Modifier.width(8.dp));Text("Ansage testen")}
            TextButton(onClick={context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}){Text("Android-Sprachausgabe öffnen")}
            Text("Benötigt eine installierte deutsche Offline-Stimme. Musik wird für Ansagen kurz abgesenkt, wenn der Player Audiofokus unterstützt.",color=Muted,fontSize=11.sp)
        }
        CardBlock{
            Eyebrow("Training entwickeln")
            Text("Planwoche $week / 12",fontSize=22.sp)
            Slider(value=week.toFloat(),onValueChange={week=it.roundToInt()},valueRange=1f..12f,steps=10)
            Text("Zwei Läufe sind die Basis; ein dritter ist optional. Erst weiterstellen, wenn du die aktuelle Woche gut verträgst. Kein automatischer Sprung auf mehr Umfang.",color=Muted,fontSize=12.sp)
            Toggle("Tempoeinheiten freischalten","Meine Arzt-Rückfrage ist geklärt. Lockere Läufe sind gut verträglich, ich bin beschwerdefrei und möchte kontrollierte Intervalle ergänzen.",ready,{ready=it})
            Text("Höchstens eine Tempoeinheit pro Woche; mindestens 48 Stunden Abstand. Bei Krankheit, Hangover oder ungewöhnlicher Erschöpfung heute keine Tempoeinheit.",color=Muted,fontSize=12.sp)
        }
        CardBlock{
            Eyebrow("Verbindungen")
            Text(profile.sensorName?:"Fitbit Air noch nicht ausgewählt",color=Muted)
            OutlinedButton(onClick=onConnect,modifier=Modifier.fillMaxWidth()){Text("Fitbit Air verbinden")}
            Toggle("Health Connect nach dem Lauf","Übergabe auf diesem Telefon; kein Konto bei Laufklar",health,{health=it})
            Toggle("GPS-Route mit übergeben","Zusätzliche Health-Connect-Freigabe erforderlich",route,{route=it})
            FullButton("Health Connect verbinden"){draft()?.let{p->scope.launch{context.repo().setProfile(p);onHealth()}}}
            OutlinedButton(onClick={context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))},modifier=Modifier.fillMaxWidth()){Text("Health-Connect-Freigaben öffnen")}
            Toggle("Online-Straßenkarte","OpenStreetMap erhält IP-Adresse und angefragte Kartenausschnitte. Pulsdaten werden nicht übertragen.",maps,{maps=it})
        }
        error?.let{Text(it,color=Peach)}
        FullButton("Profil speichern",Icons.Outlined.Check){draft()?.let(onSave)}
        TextButton(onClick=onPrivacy){Text("Datenschutz & Forschungsgrundlagen")}
    }
}
@Composable fun PrivacyScreen(onBack:()->Unit){
    val context=LocalContext.current
    fun link(url:String){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
    Page{
        TextButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,null);Text("Zurück")}
        Heading("Deine Daten.\nBei dir.")
        CardBlock{
            Eyebrow("Datenschutz")
            Text("Laufklar speichert dein Profil, GPS-Tracks, Zeit, Strecke, Pulsverlauf und Notizen ausschließlich im privaten App-Speicher. Die Dateien sind mit einem gerätegebundenen Android-Keystore-Schlüssel verschlüsselt. Android-Cloud-Backup und Geräteübertragung sind ausgeschlossen.",color=Muted,lineHeight=23.sp)
            Text("Live-Puls kommt direkt über das Bluetooth Heart Rate Profile vom Fitbit Air. Kein Fitbit-Login, kein Cloud-Abruf, keine Analyse- oder Werbedienste. Google Health / Fitbit kann unabhängig davon eigene Gerätedaten synchronisieren; das steuert Laufklar nicht.",color=Muted,lineHeight=23.sp)
            Text("Internet wird für die optionale OpenStreetMap-Karte verwendet. Der Anbieter sieht IP-Adresse und Kartenausschnitte. Es werden keine Pulswerte oder Laufdateien hochgeladen. Kartenkacheln liegen im App-Cache. Offline-Sprachausgabe bleibt lokal.",color=Muted,lineHeight=23.sp)
            Text("Health Connect ist eine getrennte, lokale Android-Datenablage. Laufklar fordert ausschließlich Schreibrechte für Training, Strecke, Puls und Geschwindigkeit sowie optional die Route an. Andere Apps lesen nur nach deiner Freigabe; sie können selbst Cloud-Synchronisation anbieten. Deren Einstellungen gelten unabhängig von Laufklar.",color=Muted,lineHeight=23.sp)
            Text("Lokales Löschen entfernt den Lauf aus Laufklar, nicht aus Health Connect und nicht aus exportierten Dateien. JSON und GPX werden nur nach deiner Dateiauswahl exportiert und sind dort unverschlüsselt. Deinstallieren löscht den lokalen Verlauf. Es gibt noch keinen Import für diese Archivdateien.",color=Muted,lineHeight=23.sp)
        }
        CardBlock{
            Eyebrow("Was das Coaching kann")
            Text("Pulszonen sind Trainingshilfen. Die App stellt keine Diagnose und kann deine ärztliche Untersuchung nicht ersetzen. Bei Brustschmerz, Ohnmachtsgefühl oder ungewöhnlicher Atemnot das Training beenden und medizinisch abklären lassen.",color=Muted,lineHeight=23.sp)
            Text("Die Formel 208 − 0,7 × Alter schätzt Gruppenmittelwerte. Individuelle Abweichungen können groß sein. Bei 38 Jahren ergibt sie rund 181 bpm, keine persönliche Leistungsgrenze. Die 50–100-%-Einteilung ist eine gewählte Fünf-Zonen-Konvention.",color=Muted,lineHeight=23.sp)
            Text("Lockere Einheiten haben Vorrang. Der Sprechtest unterstützt die Einschätzung, ist aber keine präzise Schwellenmessung. Studien zu Intensitätsverteilungen unterstützen viel niedrigintensives Training; sie begründen weder einen starren 80/20-Zwang noch einen garantierten 12-Wochen-Erfolg für dich.",color=Muted,lineHeight=23.sp)
            Text("Das Coaching verwendet Regeln auf dem Telefon: Median des letzten 15-Sekunden-Pulsfensters, mindestens 3 Werte, kein Puls älter als 10 Sekunden. Nach mindestens 15 Sekunden über dem Zielbereich wird mit mindestens 60 Sekunden Abstand zum Reduzieren geraten. Diese Zeitfenster sind Produktentscheidungen, kein klinisch validierter Algorithmus.",color=Muted,lineHeight=23.sp)
        }
        CardBlock{
            Eyebrow("Quellen · recherchiert 13.11.2026")
            listOf(
                "Google: Fitbit Air Live-Puls teilen" to "https://support.google.com/googlehealth/answer/14236705?hl=de",
                "Android: Training mit Health Connect" to "https://developer.android.com/health-and-fitness/health-connect/experiences/workouts",
                "Tanaka et al.: Maximalpuls-Schätzung" to "https://pubmed.ncbi.nlm.nih.gov/11153730/",
                "HERITAGE: Fehler von Altersformeln" to "https://pmc.ncbi.nlm.nih.gov/articles/PMC3935487/",
                "CDC: Sprechtest & Intensität" to "https://www.cdc.gov/physical-activity-basics/measuring/",
                "Muñoz et al.: Training bei Freizeitläufern" to "https://pubmed.ncbi.nlm.nih.gov/23752040/",
                "OpenStreetMap: Kartennutzung" to "https://operations.osmfoundation.org/policies/tiles/"
            ).forEach{(label,url)->TextButton(onClick={link(url)},contentPadding=PaddingValues(0.dp)){Text(label)}}
        }
        Text("Laufklar 0.1 · persönliche Vorschau · Kotlin & Jetpack Compose · Karten: osmdroid (Apache 2.0), © OpenStreetMap-Mitwirkende (ODbL).",color=Muted,fontSize=11.sp)
    }
}
