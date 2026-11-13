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
    val plan=remember(profile,runs){AdaptiveTrainer.plan(profile,runs)}
    val benchmarks=runs.mapNotNull{r->Metrics.benchmark(r)?.let{Triple(r,it.first,it.second)}}
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
            Eyebrow("Dein dauerhaftes Ziel",Lime)
            Text("${Metrics.km(profile.goalKm*1000)} km in ${Metrics.duration(profile.goalKm*profile.goalPace)}",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
            Text(plan.focus.label,color=Lime,fontWeight=FontWeight.SemiBold)
            Text(plan.reason,color=Muted,lineHeight=23.sp)
            Text("Der Trainer bewertet nach jedem Lauf neu: regelmäßige lockere Einheiten, passende längere Läufe und gelegentlich kontrolliertes Tempo. Nach Pausen oder hoher Belastung geht es zurück zum Wiedereinstieg. Es gibt kein Enddatum.",color=Muted,lineHeight=23.sp)
            Text("Deine normale Runde bleibt offen. Für längere Läufe: etwa ${plan.longMinutes} Minuten als Orientierung."+if(plan.startingPaceKnown)" Tempoabschnitte bei Freigabe: ${plan.repetitions} × ${plan.workSeconds/60.0} Minuten, etwa ${Metrics.pace(plan.targetPace.toDouble())} min/km."else " Dein Ausgangstempo lerne ich erst aus deinen lockeren Läufen.",color=Lime,fontSize=13.sp)
            Text("Ohne vollständige Tempoeinheit, Belastungsangabe und brauchbare Puls-/Tempodaten wird nicht automatisch schneller gefordert. Verbesserungen ändern jeweils nur eine Belastungsgröße. Eine Zielerreichung wird anhand der gesamten verstrichenen Zeit inklusive Pausen geprüft, getrennt nach Outdoor/Laufband.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
        }
        CardBlock{
            Eyebrow("Automatisch vergleichen")
            Text("Gleicher Puls. Mehr Tempo?",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
            Text("Auf bekannten Wegen vergleicht der Coach passende Abschnitte in derselben Richtung. Mehr Tempo bei ähnlichem Puls oder weniger Puls bei ähnlichem Tempo werden sichtbar. Ein einzelner Lauf beweist noch keinen Fitnessfortschritt; Wetter und Tagesform spielen mit.",color=Muted,lineHeight=23.sp)
            runs.sortedByDescending{it.started}.filter{r->r.coachEvents.any{it.kind==CueKind.PROGRESS}}.take(3).forEach{r->
                Text(dateText(r.started),color=Muted,fontSize=12.sp)
                r.coachEvents.filter{it.kind==CueKind.PROGRESS}.take(2).forEach{Text(it.text,color=Lime,fontSize=13.sp)}
            }
            if(benchmarks.isEmpty())Text("Für längere Tempo-/Pulsvergleiche fehlen noch 20 gleichmäßige Minuten nach dem Aufwärmen mit guter Pulsabdeckung. Kürzere Streckenvergleiche entstehen bereits während deiner normalen Runde.",color=Lime,fontSize=13.sp)
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
        CardBlock{Eyebrow("Wie deine Werte helfen");Text("Alter und Geschlecht bestimmen die Vergleichsgruppe für deine Studien-Zielvorschläge. Deine tatsächlichen Läufe, dein Puls und dein Belastungsgefühl steuern das Training. Gewicht allein liefert keine persönliche Soll-Pace oder Pulszone. Hitze, Steigung, Schlaf und Messfehler können den Puls zusätzlich verändern.",color=Muted,lineHeight=23.sp);Text("Die App schätzt weder VO₂max noch eine Diagnose. Deine ärztliche Aussage zur Zone 2 solltest du vor intensiveren Einheiten konkretisieren lassen.",color=Peach,fontSize=13.sp)}
    }
}
@Composable fun ProfileScreen(profile:Profile,onSave:(Profile)->Unit,onConnect:()->Unit,onHealth:()->Unit,onPrivacy:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var age by remember(profile){mutableStateOf(profile.age.toString())};var weight by remember(profile){mutableStateOf(profile.weight.toString())}
    var sex by remember(profile){mutableStateOf(profile.sex)};var maximum by remember(profile){mutableStateOf(profile.measuredMax?.toString()?:"")};var resting by remember(profile){mutableStateOf(profile.restingHr?.toString()?:"")}
    var reserve by remember(profile){mutableStateOf(profile.reserveZones)};var custom by remember(profile){mutableStateOf(profile.customBounds?.joinToString(", ")?:"")}
    var audio by remember(profile){mutableStateOf(profile.audio)};var cue by remember(profile){mutableIntStateOf(profile.cueMeters)};var maps by remember(profile){mutableStateOf(profile.onlineMaps)}
    var health by remember(profile){mutableStateOf(profile.healthExport)};var route by remember(profile){mutableStateOf(profile.shareRoute)};var ready by remember(profile){mutableStateOf(profile.intensityReady)}
    var autoPause by remember(profile){mutableStateOf(profile.autoPause)}
    var experienceKm by remember(profile){mutableStateOf(profile.distanceExperienceKm?.toString()?:"")}
    var goalKm by remember(profile){mutableStateOf(profile.goalKm.toString())};var goalMinutes by remember(profile){mutableStateOf((profile.goalKm*profile.goalPace/60).toString())};var frequency by remember(profile){mutableIntStateOf(profile.runsPerWeek)};var pace by remember(profile){mutableStateOf(profile.usualPace?.let{Metrics.pace(it.toDouble())}?:"")};var paceKnown by remember(profile){mutableStateOf(profile.usualPace!=null)};var error by remember{mutableStateOf<String?>(null)}
    var goalPicker by remember{mutableStateOf<Profile?>(null)}
    var resetGoalReference by remember{mutableStateOf(false)}
    val speech=remember{AudioCoach(context)};DisposableEffect(Unit){onDispose{speech.close()}}
    val audioStatus by Live.audioStatus.collectAsState()
    fun draft():Profile?{
        val goal=goalKm.replace(',','.').toDoubleOrNull();val minutes=goalMinutes.replace(',','.').toDoubleOrNull()
        val experience=experienceKm.replace(',','.').toDoubleOrNull()
        if(experienceKm.isNotBlank()&&experience==null){error="Bereits gelaufene Kilometer bitte als Zahl eingeben.";return null}
        val a=age.toIntOrNull();val w=weight.replace(',','.').toDoubleOrNull();val max=maximum.toIntOrNull();val rest=resting.toIntOrNull();val p=if(paceKnown)Metrics.parsePace(pace)else null
        if(paceKnown&&p==null){error="Pace bitte als Minuten:Sekunden eingeben, z. B. 5:30 (zwischen 3:00 und 20:00), oder „Weiß ich noch nicht“ wählen.";return null}
        val bounds=if(custom.isBlank())null else custom.split(',',';').map{it.trim().toIntOrNull()}
        if(goal==null||!goal.isFinite()||goal<=0||minutes==null||!minutes.isFinite()||minutes<=0||a==null||w==null||(maximum.isNotBlank()&&max==null)||(resting.isNotBlank()&&rest==null)||(bounds!=null&&bounds.any{it==null})){error="Bitte gültige Zahlen eingeben. Zonengrenzen mit Komma trennen.";return null}
        val draft=profile.copy(age=a,weight=w,sex=sex,measuredMax=max,restingHr=rest,reserveZones=reserve,customBounds=bounds?.filterNotNull(),audio=audio,cueMeters=cue,onlineMaps=maps,healthExport=health,shareRoute=route,intensityReady=ready,goalKm=goal,goalPace=(minutes*60/goal).roundToInt(),runsPerWeek=frequency,usualPace=p,autoPause=autoPause,distanceExperienceKm=experience)
        error=draft.validate();return draft.takeIf{error==null}
    }
    Page{
        Heading("Dein Profil.","Deine Grenzen. Dein Rhythmus.")
        CardBlock{
            Eyebrow("Ein Ziel, das zu dir passt",Lime)
            Text("${Metrics.km(profile.goalKm*1000)} km in ${Metrics.duration(profile.goalKm*profile.goalPace)}",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
            Text("Wähle eine Orientierung aus der Forschung und mach dein eigenes Ziel daraus.",color=Muted)
            FullButton("Laufziel entdecken",Icons.Outlined.Explore){draft()?.let{resetGoalReference=it.age!=profile.age||it.sex!=profile.sex;goalPicker=it}}
        }
        CardBlock{
            Eyebrow("Ausgangspunkt")
            Field("Alter in Jahren",age,{age=it});Field("Gewicht in kg",weight,{weight=it})
            SexPicker(sex){sex=it}
            Text("Alter und Geschlecht bestimmen deine Vergleichsgruppe für die Zielvorschläge aus der Studie. Ein neues Ziel übernimmst du bewusst über „Laufziel entdecken“.",color=Muted,fontSize=12.sp)
            ResearchNorms.reference(age.toIntOrNull()?:profile.age,sex)?.let{reference->Text("Deine Studienreferenz: ${Metrics.pace(reference.first)} min/km · n=${reference.second}. Bestpace der Vergleichsgruppe, keine Vorgabe für lockere Läufe.",color=Lime,fontSize=12.sp)}
            Text("Gewicht ist eine ergänzende Profilangabe. Wir berechnen daraus keine Soll-Pace oder Pulszone: Dafür lernen wir aus deinen Läufen bzw. verwenden gemessene Pulsgrenzen.",color=Muted,fontSize=12.sp)
            Field("Schon am Stück gelaufen · km (optional)",experienceKm,{experienceKm=it})
            Text("Wenn du die Zieldistanz schon laufen kannst, richtet sich das Coaching auf dein Tempo. Diese Angabe ist noch kein Nachweis einer Zielzeit.",color=Muted,fontSize=12.sp)
            Text("Deine bisherige Pace",fontWeight=FontWeight.SemiBold)
            Text("Pace ist die Zeit für einen Kilometer. 5:30 bedeutet 5 Minuten und 30 Sekunden pro Kilometer. Kleiner heißt schneller.",color=Muted,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=paceKnown,onClick={paceKnown=true},label={Text("Pace angeben")})
                FilterChip(selected=!paceKnown,onClick={paceKnown=false},label={Text("Weiß ich noch nicht")})
            }
            if(paceKnown)Field("Bisherige Pace · min/km (z. B. 5:30)",pace,{pace=it},false)
            else Text("Starte locker nach Sprechgefühl und Puls. Aus drei gut vertragenen Läufen mit Strecke und Belastungsgefühl lernt der Coach dein Ausgangstempo. Bis dahin gibt es keine feste Pace-Vorgabe.",color=Lime,fontSize=12.sp)
        }
        CardBlock{
            Eyebrow("Pulszonen")
            Text(if(profile.estimated)"Vorläufige Schätzung"else "Persönliche Grenzen",fontSize=23.sp,fontWeight=FontWeight.SemiBold)
            Text("Aktuell: ${if(profile.customBounds!=null)"manuell"else if(profile.reserveZones)"Herzfrequenzreserve"else "% des Maximalpulses"}. ${if(profile.estimated)"Maximalpuls per 208 − 0,7 × Alter: ${profile.maxHr} bpm."else ""}",color=Muted,fontSize=12.sp)
            (1..5).forEach{zone->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Zone $zone",color=ZoneColors[zone]);Text("${profile.bounds[zone-1]}–${profile.bounds[zone]-if(zone==5)0 else 1} bpm",color=Cream)}}
            Text("Das ist eine konventionelle Fünf-Zonen-Einteilung (50–60–70–80–90–100 %). Sie lokalisiert keine individuellen Laktat- oder Atemschwellen. Zone 2 bei verschiedenen Anbietern kann etwas anderes bedeuten.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
            Field("Gemessener Maximalpuls · optional",maximum,{maximum=it});Field("Ruhepuls · optional",resting,{resting=it})
            Text("Dein gemessener Ruhepuls dient auch als Bezug für den persönlichen Cooldown-Score nach dem Lauf.",color=Muted,fontSize=12.sp)
            Toggle("Herzfrequenzreserve verwenden","Nur mit gemessenem Ruhepuls. Andere Grenzen als %HFmax.",reserve,{reserve=it})
            Field("6 persönliche Zonengrenzen · optional",custom,{custom=it},false)
            Text("Format: untere Grenze Z1, Beginn Z2, Z3, Z4, Z5, obere Grenze Z5. Beispiel: 90, 110, 130, 150, 165, 185. Manuelle Grenzen haben Vorrang. Gemessene Werte bitte aus dem Befund übernehmen; keinen Maximaltest auf eigene Faust.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
        }
        CardBlock{
            Eyebrow("Audio-Coach")
            Toggle("Outdoor-Läufe automatisch pausieren","Bei etwa 8 Sekunden Stillstand pausieren, nach 3 Sekunden Bewegung fortsetzen. Mit Ansage bei aktiviertem Audio. Manuelle Pausen bleiben bestehen.",autoPause,{autoPause=it})
            Toggle("Ansagen während des Laufs","Pace, Gesamtstrecke, Puls und nächster Trainingsschritt",audio,{audio=it})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(250,500,1000).forEach{m->FilterChip(selected=cue==m,onClick={cue=m},label={Text("$m m")})}}
            Text("Distanzinfos und Coaching sind getrennt. Der Coach erklärt eine nötige Entlastung höchstens zweimal ausführlicher und bestätigt sinkenden Puls. Geschätzte Zonen werden nicht als gemessene persönliche Grenze behandelt. Ohne frischen Puls keine Temposteigerung.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
            OutlinedButton(onClick={speech.speak("Laufklar ist bereit. Ein Kilometer. Pace sechs Minuten pro Kilometer. Locker bleiben und in ganzen Sätzen sprechen.")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.VolumeUp,null);Spacer(Modifier.width(8.dp));Text("Ansage testen")}
            Text(audioStatus,color=Muted,fontSize=12.sp)
            TextButton(onClick={context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}){Text("Android-Sprachausgabe öffnen")}
            Text("Benötigt eine installierte deutsche Offline-Stimme. Musik wird für Ansagen kurz abgesenkt, wenn der Player Audiofokus unterstützt.",color=Muted,fontSize=11.sp)
        }
        CardBlock{
            Eyebrow("Dein dauerhaftes Trainingsziel")
            Field("Zieldistanz · km",goalKm,{goalKm=it})
            Field("Zielzeit · Minuten",goalMinutes,{goalMinutes=it})
            Text("Dein Standard: 10 km in 50 Minuten. Das Ziel bleibt bestehen, auch nach Erreichen oder Trainingspausen.",color=Muted,fontSize=12.sp)
            Text("${frequency} mögliche Lauftage pro Woche",fontWeight=FontWeight.SemiBold)
            Slider(value=frequency.toFloat(),onValueChange={frequency=it.roundToInt()},valueRange=2f..5f,steps=2)
            Text("Das ist deine verfügbare Häufigkeit, keine Pflicht. Erholung und zuletzt verträglicher Umfang gehen vor.",color=Muted,fontSize=12.sp)
            Text("Forschung als Orientierung",fontWeight=FontWeight.SemiBold)
            Text(ResearchNorms.hint(age.toIntOrNull()?:profile.age,sex),color=Muted,fontSize=12.sp,lineHeight=19.sp)
            TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://pmc.ncbi.nlm.nih.gov/articles/PMC8304551/")))}){Text("Vergleichsstudie ansehen")}
            Text("Fitnessforschung verwendet häufig gemessene VO₂peak mit alters- und geschlechtsspezifischen Referenzen (FRIEND, aktualisiert 2022). Eine allgemeine Pflichtzeit für gesunde Menschen lässt sich daraus nicht ableiten. Wir passen den Trainer an deine eigenen Daten an.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
            Toggle("Tempoeinheiten freischalten","Meine Arzt-Rückfrage ist geklärt. Lockere Läufe sind gut verträglich, ich bin beschwerdefrei und möchte kontrollierte Intervalle ergänzen.",ready,{ready=it})
            Text("Höchstens eine Tempoeinheit pro Woche; mindestens 48 Stunden Abstand. Bei Krankheit, Hangover oder ungewöhnlicher Erschöpfung heute keine Tempoeinheit.",color=Muted,fontSize=12.sp)
        }
        CardBlock{
            Eyebrow("Verbindungen")
            Text(profile.sensorName?:"Fitbit Air noch nicht ausgewählt",color=Muted)
            OutlinedButton(onClick=onConnect,modifier=Modifier.fillMaxWidth()){Text("Fitbit Air verbinden")}
            Toggle("Automatisch in Health Connect speichern","Nach dem Speichern und gegebenenfalls dem Cooldown. Einmalige Schreibfreigabe über „Health Connect verbinden“ nötig.",health,{health=it})
            Toggle("GPS-Route mit übergeben","Zusätzliche Health-Connect-Freigabe erforderlich",route,{route=it})
            FullButton("Health Connect verbinden"){draft()?.let{p->scope.launch{context.repo().setProfile(p);onHealth()}}}
            OutlinedButton(onClick={context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))},modifier=Modifier.fillMaxWidth()){Text("Health-Connect-Freigaben öffnen")}
            Toggle("Online-Straßenkarte","OpenStreetMap erhält IP-Adresse und angefragte Kartenausschnitte. Pulsdaten werden nicht übertragen.",maps,{maps=it})
        }
        error?.let{Text(it,color=Peach)}
        FullButton("Profil speichern",Icons.Outlined.Check){draft()?.let(onSave)}
        TextButton(onClick=onPrivacy){Text("Datenschutz & Forschungsgrundlagen")}
    }
    goalPicker?.let{draft->GoalPicker(draft,onDismiss={goalPicker=null},onAccept={p->onSave(p);goalPicker=null},useReferenceInitially=resetGoalReference)}
}
@Composable fun SexPicker(value:String,onChange:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Geschlecht",fontWeight=FontWeight.SemiBold)
        Box{
            OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(value);Icon(Icons.Outlined.ArrowDropDown,"Geschlecht auswählen")}
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                listOf("Männlich","Weiblich").forEach{choice->DropdownMenuItem(text={Text(choice)},onClick={onChange(choice);expanded=false})}
            }
        }
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
            Text("Lockere Einheiten haben Vorrang. Der Sprechtest unterstützt die Einschätzung, ist aber keine präzise Schwellenmessung. Studien zu Intensitätsverteilungen unterstützen viel niedrigintensives Training; sie begründen weder einen starren 80/20-Zwang noch einen garantierten Zeithorizont für dich.",color=Muted,lineHeight=23.sp)
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
                "Laufpace nach Alter und Geschlecht" to "https://pmc.ncbi.nlm.nih.gov/articles/PMC8304551/",
                "FRIEND: aktualisierte Fitnessreferenzen 2022" to "https://doi.org/10.1016/j.mayocp.2021.08.020",
                "OpenStreetMap: Kartennutzung" to "https://operations.osmfoundation.org/policies/tiles/"
            ).forEach{(label,url)->TextButton(onClick={link(url)},contentPadding=PaddingValues(0.dp)){Text(label)}}
        }
        Text("Laufklar 0.1 · persönliche Vorschau · Kotlin & Jetpack Compose · Karten: osmdroid (Apache 2.0), © OpenStreetMap-Mitwirkende (ODbL).",color=Muted,fontSize=11.sp)
    }
}
