package de.laufklar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

val Ink=Color(0xFF101512)
val Panel=Color(0xFF1D2620)
val Lime=Color(0xFFD8FA77)
val Muted=Color(0xFFA7B2A7)
val Cream=Color(0xFFF3F4E8)
val Peach=Color(0xFFFFB18B)
val ZoneColors=listOf(Color(0xFF6C786E),Color(0xFF82BAE1),Lime,Color(0xFFF7D676),Peach,Color(0xFFEB7D80))

class MainActivity:ComponentActivity(){
    override fun onStart(){super.onStart();Live.sensorOwnership.visible(true,this)}
    override fun onStop(){Live.sensorOwnership.visible(false,this);if(!Live.sensorOwnership.hasVisiblePreview)Live.sensor.stopScan();super.onStop()}
    override fun onDestroy(){Live.sensorOwnership.preview(false,this);Live.sensorOwnership.visible(false,this);super.onDestroy()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val privacy=intent.action=="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"||intent.action==Intent.ACTION_VIEW_PERMISSION_USAGE
        setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Lime,onPrimary=Ink,background=Ink,surface=Panel,onSurface=Cream,onBackground=Cream,secondary=Peach,secondaryContainer=Color(0xFF33412A),onSecondaryContainer=Lime,primaryContainer=Color(0xFF33412A),onPrimaryContainer=Lime,outline=Muted.copy(alpha=.4f))){Laufklar(privacy)}}
    }
}
@Composable fun Laufklar(initialPrivacy:Boolean){
    val context=LocalContext.current;val repo=remember{context.repo()};val scope=rememberCoroutineScope()
    val profile by repo.profile.collectAsStateWithLifecycle();val runs by repo.runs.collectAsStateWithLifecycle();val ready by repo.ready.collectAsStateWithLifecycle();val storageError by repo.error.collectAsStateWithLifecycle()
    val active by Live.run.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableIntStateOf(0)};var detailId by rememberSaveable{mutableStateOf<String?>(null)}
    var privacy by rememberSaveable{mutableStateOf(initialPrivacy)};var sensorDialog by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)};var requested by remember{mutableStateOf<(() -> Unit)?>(null)}
    var previousActive by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(ready,profile.sensorAddress,profile.sensorName){if(ready){
        Live.sensor.rememberDevice(profile.sensorAddress,profile.sensorName)
        Live.sensorOwnership.device(profile.sensorAddress)
    }}
    LaunchedEffect(ready,profile.healthExport){if(ready&&profile.healthExport)HealthExporter(context).exportPending()}
    val previewWanted=ready&&((tab==0&&active==null&&previousActive==null&&detailId==null&&!privacy)||sensorDialog)
    DisposableEffect(previewWanted,context){
        Live.sensorOwnership.preview(previewWanted,context)
        onDispose{Live.sensorOwnership.preview(false,context)}
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        val callback=requested;requested=null
        callback?.invoke()
        if(grants.values.any{!it})notice="Nicht alle Freigaben erteilt. Die betroffenen Funktionen bleiben eingeschränkt."
    }
    fun request(perms:Array<String>,then:()->Unit){if(perms.all{context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED})then()else{requested=then;permissions.launch(perms)}}
    val healthPermissions=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){granted->
        scope.launch{if(granted.containsAll(HealthExporter.basePermissions)){repo.setProfile(repo.profile.value.copy(healthExport=true));HealthExporter(context).exportPending();notice="Health Connect verbunden. Neue Läufe werden nach dem Cooldown automatisch gespeichert."}else notice="Health Connect: Schreibfreigaben fehlen. Läufe bleiben in Laufklar."}
    }
    fun healthConnect(){if(HealthExporter(context).available()) healthPermissions.launch(HealthExporter.basePermissions + if(repo.profile.value.shareRoute)setOf(HealthExporter.routePermission)else emptySet()) else notice="Health Connect ist auf diesem Gerät nicht verfügbar."}
    LaunchedEffect(active?.id,runs.size){if(active!=null)previousActive=active!!.id else if(previousActive!=null){detailId=previousActive;previousActive=null}}
    fun connectSensor(){request(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)){sensorDialog=true;Live.sensorOwnership.reconnect();Live.sensor.scan()}}
    fun start(mode:RunMode,workout:Workout,readiness:Readiness){
        if(readiness==Readiness.UNWELL){notice="Heute kein Lauftraining bei Krankheit oder Beschwerden. Dein Ziel bleibt bestehen.";return}
        if(storageError!=null){notice=storageError;return}
        if(workout==Workout.INTERVALS&&runs.any{it.workout==Workout.INTERVALS&&it.started>System.currentTimeMillis()-7*24*3600_000L}){notice="In den letzten 7 Tagen gab es eine Tempoeinheit. Wähle heute einen lockeren Lauf.";return}
        val perms=buildList{add(Manifest.permission.POST_NOTIFICATIONS);if(mode==RunMode.OUTDOOR){add(Manifest.permission.ACCESS_FINE_LOCATION);add(Manifest.permission.ACCESS_COARSE_LOCATION)};if(profile.sensorAddress!=null){add(Manifest.permission.BLUETOOTH_CONNECT);add(Manifest.permission.BLUETOOTH_SCAN)}}.toTypedArray()
        request(perms){
            if(mode==RunMode.OUTDOOR&&context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)notice="Für Outdoor-Aufzeichnung bitte präzisen Standort erlauben. Laufband ist ohne GPS möglich."
            else runCatching{context.startForegroundService(Intent(context,RunService::class.java).setAction(RunService.START).putExtra("mode",mode.name).putExtra("workout",workout.name).putExtra("readiness",readiness.name))}.onFailure{notice="Lauf konnte nicht starten: ${it.message}"}
        }
    }
    Scaffold(containerColor=Ink,bottomBar={if(active==null&&detailId==null&&!privacy)NavigationBar(containerColor=Ink,tonalElevation=0.dp){
        listOf("Laufen" to Icons.Outlined.DirectionsRun,"Verlauf" to Icons.Outlined.Route,"Entwicklung" to Icons.Outlined.Insights,"Profil" to Icons.Outlined.Tune).forEachIndexed{i,(label,icon)->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Lime,selectedIconColor=Ink,selectedTextColor=Lime,unselectedIconColor=Muted,unselectedTextColor=Muted))}
    }}){padding->
        Box(Modifier.fillMaxSize().padding(padding)){
            when{
                !ready->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                privacy->PrivacyScreen{privacy=false}
                active!=null->ActiveScreen(active!!,profile,onConnect=::connectSensor)
                detailId!=null->{val r=runs.find{it.id==detailId};if(r==null)LaunchedEffect(detailId){detailId=null}else DetailScreen(r,profile,onBack={detailId=null},onNotice={notice=it})}
                tab==0->Home(profile,runs,onStart=::start,onConnect=::connectSensor,onProfile={tab=3})
                tab==1->History(runs){detailId=it.id}
                tab==2->ProgressScreen(runs,profile)
                else->ProfileScreen(profile,onSave={p->scope.launch{runCatching{repo.setProfile(p)}.onSuccess{notice="Profil gespeichert"}.onFailure{notice="Profil konnte nicht gespeichert werden"}}},onConnect=::connectSensor,onHealth=::healthConnect,onPrivacy={privacy=true})
            }
            storageError?.let{Text(it,color=Peach,modifier=Modifier.align(Alignment.BottomCenter).background(Ink).padding(16.dp))}
        }
    }
    if(notice!=null)AlertDialog(onDismissRequest={notice=null},title={Text("Laufklar")},text={Text(notice!!)},confirmButton={TextButton(onClick={notice=null}){Text("Verstanden")}})
    if(sensorDialog)SensorDialog(onDismiss={sensorDialog=false},onSelect={d->
        scope.launch{repo.setProfile(repo.profile.value.copy(sensorAddress=d.address,sensorName=d.name));Live.sensor.rememberDevice(d.address,d.name);Live.sensorOwnership.choose(d.address)}
    })
}
@Composable fun Page(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(top=18.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)}
@Composable fun Eyebrow(text:String,color:Color=Muted){Text(text.uppercase(Locale.GERMANY),color=color,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold)}
@Composable fun Heading(title:String,subtitle:String?=null){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Eyebrow("Laufklar  /  Dein Lauf zählt");Text(title,fontSize=34.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp);subtitle?.let{Text(it,color=Muted,lineHeight=23.sp)}}}
@Composable fun CardBlock(modifier:Modifier=Modifier,color:Color=Panel,content:@Composable ColumnScope.()->Unit){Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)}
@Composable fun Metric(value:String,label:String,color:Color=Cream,large:Boolean=false){Column{Text(value,fontSize=if(large)44.sp else 28.sp,fontWeight=FontWeight.Medium,color=color,letterSpacing=(-1).sp);Text(label,fontSize=12.sp,color=Muted)}}
@Composable fun RowScope.Stat(value:String,label:String,color:Color=Cream){Box(Modifier.weight(1f)){Metric(value,label,color)}}
@Composable fun FullButton(text:String,icon:ImageVector?=null,enabled:Boolean=true,onClick:()->Unit){Button(onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(18.dp)){icon?.let{Icon(it,null);Spacer(Modifier.width(8.dp))};Text(text,fontWeight=FontWeight.Bold,fontSize=16.sp)}}
@Composable fun Toggle(title:String,description:String?=null,checked:Boolean,onChange:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);description?.let{Text(it,color=Muted,fontSize=12.sp,lineHeight=18.sp)}};Switch(checked,onChange)}}
fun localDate(time:Long)=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
fun dateText(time:Long)=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d. MMM · HH:mm",Locale.GERMANY))
fun weekRuns(runs:List<Run>,offset:Int=0):List<Run>{val end=LocalDate.now().minusDays(offset*7L).plusDays(1);val start=end.minusDays(7);return runs.filter{val d=localDate(it.started);!d.isBefore(start)&&d.isBefore(end)}}
@Composable fun Home(profile:Profile,runs:List<Run>,onStart:(RunMode,Workout,Readiness)->Unit,onConnect:()->Unit,onProfile:()->Unit){
    var mode by rememberSaveable{mutableStateOf(RunMode.OUTDOOR)};var workout by rememberSaveable{mutableStateOf(Workout.EASY)}
    var readiness by rememberSaveable{mutableStateOf(Readiness.READY)}
    val recent=weekRuns(runs)
    val plan=remember(profile,runs,mode,readiness){AdaptiveTrainer.plan(profile,runs,mode,readiness)}
    LaunchedEffect(plan){workout=plan.suggested}
    Column(Modifier.fillMaxSize()){
    Box(Modifier.weight(1f)){Page{
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Eyebrow("LAUFKLAR",Lime);Icon(Icons.Outlined.GraphicEq,null,tint=Lime)}
        Text("Ruhig starten.\nStärker werden.",fontSize=38.sp,lineHeight=42.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1.4).sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            CardBlock(Modifier.weight(1f)){Eyebrow("Letzte 7 Tage");Metric(Metrics.km(recent.sumOf{it.distance}),"Kilometer",Lime);Text("${recent.size} Läufe",color=Muted,fontSize=12.sp)}
            CardBlock(Modifier.weight(1f).clickable(onClick=onProfile)){Eyebrow("Dein Ziel");Metric(Metrics.duration(profile.goalKm*profile.goalPace),"${Metrics.km(profile.goalKm*1000)} km · ${Metrics.pace(profile.goalPace.toDouble())} /km");Text("Dauerhaft dein Ziel",color=Muted,fontSize=12.sp)}
        }
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){RunMode.entries.forEach{m->FilterChip(selected=mode==m,onClick={mode=m},label={Text(m.label)},leadingIcon={Icon(if(m==RunMode.OUTDOOR)Icons.Outlined.Park else Icons.Outlined.FitnessCenter,null,Modifier.size(17.dp))})}}
            CardBlock{
                Eyebrow("Dein Trainer · ${plan.focus.label}",Lime)
                Text(plan.reason,color=Muted,fontSize=13.sp,lineHeight=20.sp)
                Text("Wie fühlst du dich heute?",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Readiness.entries.take(2).forEach{r->FilterChip(selected=readiness==r,onClick={readiness=r},label={Text(r.label,fontSize=12.sp)})}}
                FilterChip(selected=readiness==Readiness.UNWELL,onClick={readiness=Readiness.UNWELL},label={Text(Readiness.UNWELL.label,fontSize=12.sp)})
            }
            Eyebrow(if(plan.restRecommended)"Heute ist Erholung empfohlen"else "Heute laufen")
            Text("Dein Coach begleitet die Runde mit einer kleinen Aufgabe und passenden Vergleichen. Du entscheidest, wann du fertig bist.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
            plan.available().forEach{w->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if(workout==w)Lime.copy(alpha=.10f)else Panel).clickable{workout=w}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                    RadioButton(selected=workout==w,onClick={workout=w});Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){if(w==plan.suggested&&!plan.restRecommended)Text("Heute empfohlen",color=Lime,fontSize=10.sp);Text(w.label,fontWeight=FontWeight.SemiBold);Text(workoutSubtitle(w,plan),color=Muted,fontSize=12.sp)}
                    Text(plan.durationLabel(w),color=if(workout==w)Lime else Muted,fontSize=20.sp)
                }
            }
            CardBlock{Text("So läuft „${workout.label}“",fontWeight=FontWeight.SemiBold,color=Lime);Text(workoutExplanation(workout,plan),color=Muted,fontSize=13.sp,lineHeight=21.sp)}
        }
        SensorCard(onConnect)
        if(mode==RunMode.TREADMILL)CardBlock{Text("Dein Laufband gibt das Tempo vor",fontWeight=FontWeight.SemiBold);Text("Während des Laufs km/h eingeben. Ohne Eingabe laufen Zeit und Puls weiter; Pace und Distanz bleiben offen. Die Enddistanz kannst du nachtragen.",color=Muted,fontSize=13.sp)}
        if(profile.estimated)Text("Pulszonen sind noch geschätzt. Mit ${profile.age} Jahren sagt eine Formel nicht, wo deine persönliche Zone 2 liegt. Gemessene Grenzen kannst du im Profil hinterlegen.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
        TextButton(onClick=onProfile){Text("Pulszonen & Audio einstellen")}
    }}
    Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal=24.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("${workout.label} · ${plan.durationLabel(workout)}",fontSize=12.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
        FullButton("${mode.label} starten",Icons.Outlined.PlayArrow,enabled=readiness!=Readiness.UNWELL){onStart(mode,workout,readiness)}
    }
    }
}
fun workoutSubtitle(w:Workout,p:DailyPlan)=when(w){Workout.EASY->"Deine Strecke · kleine Aufgabe · automatische Vergleiche";Workout.BASELINE->"Vergleichslauf · wird dieselbe Pace leichter?";Workout.LONG->"Runde verlängern · Dauer als Orientierung";Workout.INTERVALS->"Schnelle und lockere Abschnitte im Wechsel"}
fun workoutExplanation(w:Workout,p:DailyPlan)=when(w){
    Workout.EASY->"Fünf Minuten ankommen, dann einen Kilometer möglichst gleichmäßig laufen. Deine Runde bleibt offen. Der Coach erkennt passende Streckenabschnitte wieder, erklärt wichtige Anpassungen und meldet echte kleine Erfolge. Bei steigender Belastung wird die Aufgabe ruhiger."
    Workout.BASELINE->"10 Minuten aufwärmen, 20 Minuten gleichmäßig locker laufen, 5 Minuten auslaufen. Wähle eine flache Strecke oder dasselbe Laufband mit gleicher Steigung. Alle 2–4 Wochen wiederholen: Unter „Entwicklung“ siehst du, ob du bei ähnlichem Puls schneller wirst. Laufe dabei so, dass du ganze Sätze sprechen kannst."
    Workout.LONG->"Etwa ${p.longMinutes} Minuten als Vorschlag, passend zu deinen bisherigen Läufen. Plane dafür eine Erweiterung deiner Runde. Du entscheidest über das Ende; Zeit und Strecke laufen weiter, auch wenn der Vorschlag erreicht ist. Heute zählt Umfang, nicht Tempo."
    Workout.INTERVALS->"10 Minuten aufwärmen, dann ${p.repetitions} zügige Abschnitte à ${Metrics.duration(p.workSeconds.toDouble())} Minuten mit jeweils 2 Minuten lockerer Erholung; am Ende 10 Minuten auslaufen. Orientierung: ${Metrics.pace(p.targetPace.toDouble())} min/km. Puls und Belastungsgefühl gehen vor Tempo. Der Coach sagt jeden Wechsel an."
}
@Composable fun SensorCard(onConnect:()->Unit){
    val status by Live.sensor.status.collectAsStateWithLifecycle();val bpm by Live.bpm.collectAsStateWithLifecycle();var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(Unit){while(true){now=SystemClock.elapsedRealtime();delay(1000)}}
    // The one-second UI tick may precede a newly received Bluetooth sample.
    val fresh=bpm?.takeIf{maxOf(now,SystemClock.elapsedRealtime())-it.second in 0..10_000}
    val connected by Live.sensor.connected.collectAsStateWithLifecycle()
    val displayStatus=if(connected&&fresh==null)"Verbunden · warte auf frischen Puls"else status
    CardBlock{Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.FavoriteBorder,null,tint=Peach);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Fitbit Air",fontWeight=FontWeight.SemiBold);Text(displayStatus,color=Muted,fontSize=12.sp)}
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val live=fresh!=null&&connected
            Box(Modifier.size(9.dp).background(if(live)Color(0xFF62D98B)else Muted.copy(alpha=.35f),CircleShape).semantics{contentDescription=if(live)"Live-Puls aktiv"else "Kein aktueller Live-Puls"})
            Text(fresh?.first?.toString()?:"—",fontSize=28.sp,color=Peach)
        }
    };OutlinedButton(onClick=onConnect,modifier=Modifier.fillMaxWidth()){Text("Pulssensor verbinden")}}
}
@Composable fun SensorDialog(onDismiss:()->Unit,onSelect:(SensorDevice)->Unit){
    val devices by Live.sensor.devices.collectAsStateWithLifecycle();val status by Live.sensor.status.collectAsStateWithLifecycle()
    val saved by Live.sensor.savedDevice.collectAsStateWithLifecycle();val scanStatus by Live.sensor.scanStatus.collectAsStateWithLifecycle()
    DisposableEffect(Unit){onDispose{Live.sensor.stopScan()}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Fitbit Air verbinden")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("In Google Health: Verbindungen → Fitbit Air → Herzfrequenz teilen → Starten. Anschließend hier auswählen und die Android-Pairingabfrage bestätigen.")
        Text(status,color=Lime)
        saved?.let{d->
            Text("Gespeichert: ${d.name}",fontWeight=FontWeight.SemiBold)
            OutlinedButton(onClick={onSelect(d)},modifier=Modifier.fillMaxWidth()){Text("Gespeicherten Sensor verbinden")}
            Text("Dieser Sensor bleibt auswählbar, auch wenn er gerade nicht in der Suche erscheint.",fontSize=12.sp,color=Muted)
            TextButton(onClick={Live.sensorOwnership.disconnect()}){Text("Sensor freigeben")}
        }
        if(scanStatus.isNotBlank())Text(scanStatus,fontSize=12.sp,color=Muted)
        devices.filter{it.address!=saved?.address}.forEach{d->OutlinedButton(onClick={onSelect(d)},modifier=Modifier.fillMaxWidth()){Text("${d.name} · ${d.address.takeLast(5)}")}}
        Text("Auf der Laufstartseite verbindet sich dein gespeicherter Sensor automatisch. Nach dem Lauf und außerhalb der App ohne Aufzeichnung wird die Verbindung freigegeben. Veraltete Pulswerte steuern das Coaching nicht.",fontSize=12.sp,color=Muted)
    }},confirmButton={TextButton(onClick=onDismiss){Text("Fertig")}},dismissButton={TextButton(onClick={Live.sensor.scan()}){Text("Erneut suchen")}})
}
@Composable fun ActiveScreen(run:Run,profile:Profile,onConnect:()->Unit){
    if(run.ended!=null){CooldownScreen(run);return}
    val context=LocalContext.current;val paused by Live.paused.collectAsStateWithLifecycle();val saving by Live.saving.collectAsStateWithLifecycle()
    val autoPaused by Live.autoPaused.collectAsStateWithLifecycle()
    val message by Live.message.collectAsStateWithLifecycle();val gps by Live.gps.collectAsStateWithLifecycle();val speed by Live.beltSpeed.collectAsStateWithLifecycle();val audio by Live.audioStatus.collectAsStateWithLifecycle()
    val reviewing by Live.reviewing.collectAsStateWithLifecycle()
    var editSpeed by remember{mutableStateOf(false)};var speedInput by remember{mutableStateOf("")}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())};LaunchedEffect(Unit){while(true){now=RunService.instance?.timeNow()?:System.currentTimeMillis();delay(1000)}}
    val hr=Metrics.freshHr(run.pulses,maxOf(now,RunService.instance?.timeNow()?:System.currentTimeMillis()))
    val phases=run.phases.ifEmpty{Training.fallback(run.workout)};val phaseIndex=Training.phaseAt(phases,run.activeSeconds)
    val phase=phases.getOrNull(phaseIndex)
    val previous=phases.take(phaseIndex).sumOf{it.duration}
    fun command(action:String){context.startService(Intent(context,RunService::class.java).setAction(action))}
    Page{
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Eyebrow(if(autoPaused)"AUTO-PAUSE"else if(paused)"PAUSE"else "LIVE · ${run.mode.label}",Lime);Text(run.workout.label,color=Muted,fontSize=12.sp)}
        Column{Text(Metrics.km(run.meters),fontSize=84.sp,fontWeight=FontWeight.Medium,letterSpacing=(-4).sp);Text(if(run.mode==RunMode.TREADMILL)"Kilometer · aus km/h geschätzt" else "Kilometer",color=Muted)}
        Row(Modifier.fillMaxWidth()){Stat(Metrics.pace(Metrics.recentPace(run.motion,run.activeSeconds)),"min/km · letzte 60 s");Stat(Metrics.duration(run.activeSeconds),"aktive Zeit");Stat(hr?.toString()?:"—","Puls · bpm",Peach)}
        CardBlock{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Eyebrow(phase?.name?:"Training abgeschlossen",Lime);Text(phase?.let{if(it.duration==0)"Offen"else Metrics.duration((previous+it.duration-run.activeSeconds).coerceAtLeast(0.0))}?:"✓",color=Lime)}
            if(phase!=null&&phase.duration>0)LinearProgressIndicator(progress={((run.activeSeconds-previous)/phase.duration).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=Lime,trackColor=Ink)
            Text(message,fontSize=18.sp,lineHeight=26.sp)
            if(hr==null)Text("Kein frischer, stabiler Puls · keine Aufforderung zur Temposteigerung",color=Peach,fontSize=12.sp)
            else Text("Zone ${profile.zone(hr)} · ${if(profile.estimated)"geschätzte Grenzen"else "deine Grenzen"}",color=Muted,fontSize=12.sp)
        }
        if(run.mode==RunMode.TREADMILL){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                OutlinedIconButton(onClick={RunService.instance?.speed((speed-.5).coerceAtLeast(0.0))},enabled=!paused&&!saving){Icon(Icons.Outlined.Remove,"Tempo minus 0,5 km/h")}
                TextButton(onClick={speedInput=if(speed>0)"%.1f".format(Locale.GERMANY,speed)else "";editSpeed=true}){Text("${"%.1f".format(Locale.GERMANY,speed)} km/h",fontSize=25.sp)}
                OutlinedIconButton(onClick={RunService.instance?.speed((speed+.5).coerceAtMost(25.0))},enabled=!paused&&!saving){Icon(Icons.Outlined.Add,"Tempo plus 0,5 km/h")}
            }
            Text("Strecke = Geschwindigkeit × aktive Zeit. Passe das Tempo bei jeder Änderung am Laufband an; Pausen zählen nicht. Am Ende kannst du die Laufbanddistanz prüfen und korrigieren.",color=Muted,fontSize=12.sp)
        }else{
            Text(gps,color=Muted,fontSize=12.sp)
            if(run.points.isNotEmpty()){
                RouteMap(run.points,profile.onlineMaps,Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(20.dp)),followPosition=true,pulses=run.pulses,zoneBounds=run.zoneBounds)
                RouteZoneLegend()
            }
        }
        if(autoPaused)TextButton(onClick={command(RunService.PAUSE)}){Text("Pausiert bleiben · manuelle Pause")}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedButton(onClick={command(if(paused)RunService.RESUME else RunService.PAUSE)},enabled=!saving,modifier=Modifier.weight(1f).height(56.dp)){Icon(if(paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,null);Text(if(paused)"Weiter" else "Pause")}
            Button(onClick={command(RunService.REVIEW)},enabled=!saving,modifier=Modifier.weight(1f).height(56.dp)){Text(if(saving)"Schließe ab …"else "Beenden")}
        }
        Text(if(profile.audio)audio else "Audio ausgeschaltet",color=Muted,fontSize=12.sp)
        TextButton(onClick=onConnect){Text("Pulsverbindung prüfen")}
    }
    if(reviewing)FinishRunDialog(run,saving,message,onSave={meters->
        context.startService(Intent(context,RunService::class.java).setAction(RunService.FINISH).apply{meters?.let{putExtra(RunService.FINISH_METERS,it)}})
    },onDiscard={command(RunService.DISCARD)},onContinue={command(RunService.CANCEL_REVIEW)})
    if(editSpeed)AlertDialog(onDismissRequest={editSpeed=false},title={Text("Aktuelles Laufbandtempo")},text={Field("km/h (0–25)",speedInput,{speedInput=it})},confirmButton={TextButton(onClick={speedInput.replace(',','.').toDoubleOrNull()?.takeIf{it in 0.0..25.0}?.let{RunService.instance?.speed(it);editSpeed=false}}){Text("Übernehmen")}},dismissButton={TextButton(onClick={editSpeed=false}){Text("Abbrechen")}})
}
@Composable fun FinishRunDialog(run:Run,saving:Boolean,message:String,onSave:(Double?)->Unit,onDiscard:()->Unit,onContinue:()->Unit){
    var distance by rememberSaveable(run.id){mutableStateOf("")}
    val treadmill=run.mode==RunMode.TREADMILL
    val km=distance.trim().replace(',','.').toDoubleOrNull()
    val valid=distance.isBlank()||(km!=null&&km.isFinite()&&km in .01..200.0)
    AlertDialog(onDismissRequest={if(!saving)onContinue()},title={Text("Lauf beenden?")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("${Metrics.km(run.meters)} km${if(treadmill)" geschätzt"else ""} · ${Metrics.duration(run.activeSeconds)} aktive Zeit")
            Text("Die Aufzeichnung ist während dieser Auswahl pausiert.",color=Muted)
            Text("Nach dem Speichern messen wir bei erhöhtem Live-Puls noch 60 Sekunden deinen Cooldown. Laufzeit und Strecke bleiben dabei unverändert.",color=Muted,fontSize=12.sp)
            if(treadmill){
                Text("Welche Strecke zeigt dein Laufband? Leer lassen, um die Schätzung aus Tempo und aktiver Zeit zu übernehmen.")
                OutlinedTextField(distance,{distance=it},label={Text("Enddistanz am Laufband · km")},enabled=!saving,isError=!valid,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                if(!valid)Text("Bitte 0,01 bis 200 km eingeben.",color=Peach)
            }
            Text("Nicht speichern verwirft diesen Lauf auch für den Verlauf und Health Connect.",fontSize=12.sp,color=Muted)
            if(message.startsWith("Abschließen fehlgeschlagen"))Text(message,color=Peach)
        }
    },confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Button(onClick={onSave(if(treadmill&&distance.isNotBlank())km!!*1000 else null)},enabled=!saving&&valid,modifier=Modifier.fillMaxWidth()){
            Text(if(saving)"Schließe ab …"else if(treadmill&&distance.isBlank())"Schätzung speichern"else "Lauf speichern")
        }
        TextButton(onClick=onDiscard,enabled=!saving,modifier=Modifier.fillMaxWidth()){Text("Nicht speichern",color=Peach)}
        TextButton(onClick=onContinue,enabled=!saving,modifier=Modifier.fillMaxWidth()){Text("Zurück zum Lauf")}
    }})
}
@Composable fun CooldownScreen(run:Run){
    val context=LocalContext.current
    val remaining by Live.cooldownSeconds.collectAsStateWithLifecycle()
    val saving by Live.saving.collectAsStateWithLifecycle()
    val message by Live.message.collectAsStateWithLifecycle()
    val liveBpm by Live.bpm.collectAsStateWithLifecycle()
    var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(Unit){while(true){now=SystemClock.elapsedRealtime();delay(1000)}}
    val bpm=liveBpm?.takeIf{maxOf(now,SystemClock.elapsedRealtime())-it.second in 0..10_000}?.first
    Page{
        Heading(if(remaining!=null)"Dein Cooldown."else "Lauf abschließen.","${Metrics.km(run.distance)} km · ${Metrics.duration(run.activeSeconds)} aktive Zeit")
        if(remaining!=null){
            CardBlock{
                Eyebrow("Lauf gespeichert",Lime)
                Row(Modifier.fillMaxWidth()){Stat("${remaining}s","noch messen",Lime);Stat(bpm?.toString()?:"—","Puls · bpm",Peach)}
                LinearProgressIndicator(progress={1f-(remaining?:0)/60f},modifier=Modifier.fillMaxWidth(),color=Lime,trackColor=Ink)
                Text("Die Messung läuft auch bei ausgeschaltetem Bildschirm weiter. Lass den Pulssensor verbunden.",color=Muted)
                if(bpm==null)Text("Warte auf frischen Puls. Ohne Endwert gibt es keinen Score.",color=Peach,fontSize=12.sp)
            }
            OutlinedButton(onClick={context.startService(Intent(context,RunService::class.java).setAction(RunService.STOP_COOLDOWN))},modifier=Modifier.fillMaxWidth()){
                Text("Cooldown vorzeitig beenden")
            }
        }else{
            if(saving){CircularProgressIndicator(color=Lime);Text("Lauf wird abgeschlossen …",color=Muted)}
            else {Text(message,color=Peach);FullButton("Speichern erneut versuchen"){context.startService(Intent(context,RunService::class.java).setAction(RunService.FINISH))}}
        }
        run.cooldown?.let{CooldownResult(it)}
    }
}
@Composable fun CooldownResult(cooldown:Cooldown){
    CardBlock{
        Eyebrow("Cooldown · 60 Sekunden ab Speichern",Lime)
        Row(Modifier.fillMaxWidth()){
            Stat(cooldown.startBpm.toString(),"Puls am Start",Peach)
            Stat(cooldown.endBpm?.toString()?:"—","Puls nach 60 s",Peach)
        }
        when(cooldown.state){
            CooldownState.MEASURING->Text("Messung läuft …",color=Muted)
            CooldownState.INTERRUPTED->Text("Messung vorzeitig beendet oder unterbrochen. Kein 60-Sekunden-Score.",color=Muted)
            CooldownState.COMPLETE->{
                val drop=cooldown.dropBpm
                if(drop==null)Text("Zu wenige frische Pulswerte am Ende der Minute. Kein verlässlicher Score.",color=Muted)
                else Text(if(drop>=0)"Pulsabfall: $drop bpm"else "Pulsanstieg: ${-drop} bpm",fontSize=20.sp)
                cooldown.score?.let{Metric("$it / 100","persönlicher Cooldown-Score",Lime)}
                cooldown.restingRangeSeconds?.let{Text("Ruhepuls + 10 bpm für mindestens 3 Sekunden erreicht: nach $it Sekunden.",color=Muted,fontSize=12.sp)}
            }
        }
        Text(if(cooldown.restingHr==null)"Für einen persönlichen Score hinterlege deinen gemessenen Ruhepuls im Profil. Der Pulsabfall wird auch ohne Ruhepuls gespeichert."
            else "Ruhepuls: ${cooldown.restingHr} bpm. Der Score zeigt, wie viel Prozent des Abstands zum Ruhepuls in 60 Sekunden abgebaut wurden (0–100). Ein persönlicher Vergleichswert, keine medizinische Bewertung.",color=Muted,fontSize=12.sp)
    }
}
@Composable fun History(runs:List<Run>,onOpen:(Run)->Unit){Page{
    Heading("Deine Läufe.","Jede Runde zählt. Alles bleibt auf deinem Telefon.")
    if(runs.isEmpty())EmptyState(Icons.Outlined.Route,"Hier beginnt deine Geschichte","Nach deinem ersten Lauf findest du hier Strecke, Puls, Karte und deine Notizen.")
    else runs.groupBy{localDate(it.started).with(java.time.DayOfWeek.MONDAY)}.forEach{(week,items)->
        Eyebrow("Woche ab ${week.format(DateTimeFormatter.ofPattern("dd.MM."))} · ${Metrics.km(items.sumOf{it.distance})} km")
        items.forEach{r->RunRow(r){onOpen(r)}}
    }
}}
@Composable fun RunRow(run:Run,onOpen:()->Unit){CardBlock(Modifier.clickable(onClick=onOpen)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Eyebrow(run.mode.label);Icon(Icons.Outlined.NorthEast,null,tint=Lime,modifier=Modifier.size(18.dp))}
    Text(run.workout.label,fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text(dateText(run.started),color=Muted,fontSize=12.sp)
    Row(Modifier.fillMaxWidth()){Stat(Metrics.km(run.distance),"km",Lime);Stat(Metrics.pace(run.pace),"min/km");Stat(run.averageHr?.toString()?:"—","Ø bpm")}
    run.cooldown?.score?.let{Text("Cooldown $it / 100 · Pulsabfall ${run.cooldown.dropBpm} bpm",color=Muted,fontSize=12.sp)}
}}
@Composable fun EmptyState(icon:ImageVector,title:String,body:String){CardBlock{Icon(icon,null,tint=Lime,modifier=Modifier.size(36.dp));Text(title,fontSize=22.sp,fontWeight=FontWeight.SemiBold);Text(body,color=Muted,lineHeight=23.sp)}}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit,numeric:Boolean=true){OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(14.dp),keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text))}

@Composable fun DetailScreen(run:Run,profile:Profile,onBack:()->Unit,onNotice:(String)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val repo=context.repo()
    var edit by remember{mutableStateOf(false)};var remove by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
    var km by remember(run.id){mutableStateOf(if(run.distance>0)"%.2f".format(Locale.GERMANY,run.distance/1000)else "")}
    var rpe by remember(run.id){mutableStateOf(run.rpe?.toString()?:"")};var note by remember(run.id){mutableStateOf(run.note)};var error by remember{mutableStateOf<String?>(null)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(Codec.run(run).toString(2).toByteArray())}?:error("Datei nicht geöffnet")}}.onSuccess{onNotice("JSON-Datei exportiert. Diese Datei enthält unverschlüsselte Laufdaten.")}.onFailure{onNotice("Export fehlgeschlagen")}}}
    val gpx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")){uri->if(uri!=null)scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(gpx(run).toByteArray())}?:error("Datei nicht geöffnet")}}.onSuccess{onNotice("GPS-Track exportiert")}.onFailure{onNotice("GPX-Export fehlgeschlagen")}}}
    Page{
        TextButton(onClick=onBack,contentPadding=PaddingValues(0.dp)){Icon(Icons.Outlined.ArrowBack,null);Spacer(Modifier.width(6.dp));Text("Alle Läufe")}
        Heading(run.workout.label,dateText(run.started))
        if(run.recovered)Text("Unterbrochene Aufzeichnung gerettet. Werte reichen bis zum letzten Sicherungspunkt.",color=Peach)
        Row(Modifier.fillMaxWidth()){Stat(Metrics.km(run.distance),"Kilometer",Lime);Stat(Metrics.duration(run.activeSeconds),"aktive Zeit");Stat(Metrics.pace(run.pace),"Ø min/km")}
        if(run.mode==RunMode.TREADMILL)Text(if(run.correctedMeters==null)"Distanz aus eingegebenem Laufbandtempo geschätzt · Endwert bitte prüfen"else "Distanz manuell vom Laufband übernommen",color=Muted,fontSize=12.sp)
        if(run.points.isNotEmpty()){
            RouteMap(run.points,profile.onlineMaps,Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp)),pulses=run.pulses,zoneBounds=run.zoneBounds)
            RouteZoneLegend()
            Text("© OpenStreetMap-Mitwirkende · Pausen und GPS-Lücken sind getrennte Abschnitte",color=Muted,fontSize=11.sp)
        }
        CardBlock{
            Eyebrow("Dein Pulsverlauf")
            Row(Modifier.fillMaxWidth()){Stat(run.averageHr?.toString()?:"—","Ø bpm",Peach);Stat(run.pulses.maxOfOrNull{it.bpm}?.toString()?:"—","höchster Messwert")}
            if(run.pulses.size>1)PulseChart(run)else Text("Kein Puls aufgezeichnet. Vor dem nächsten Lauf Fitbit Air verbinden.",color=Muted)
            val zones=Metrics.zoneSeconds(run);val known=zones.sum()
            if(known>0){
                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))){zones.forEachIndexed{i,v->if(v>0)Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(ZoneColors[i]))}}
                zones.forEachIndexed{i,v->if(v>0)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(i==0)"Unter Zone 1"else "Zone $i",color=ZoneColors[i],fontSize=12.sp);Text("${Metrics.duration(v)} · ${(v/known*100).roundToInt()} %",fontSize=12.sp)}}
                Text("Pulsabdeckung: ${(known/run.activeSeconds*100).coerceIn(0.0,100.0).roundToInt()} % der aktiven Zeit. Lücken zählen nicht als Zone 1.",color=Muted,fontSize=11.sp)
            }
            if(run.estimatedZones)Text("Zonen beruhen auf einer Altersformel, nicht auf einem Leistungstest.",color=Muted,fontSize=11.sp)
        }
        run.cooldown?.let{CooldownResult(it)}
        CardBlock{
            Eyebrow("Trainer-Notiz")
            Text(run.coachReason.ifBlank{"Freier Lauf"},color=Muted,fontSize=13.sp)
            if(run.coachMission.isNotBlank())Text(run.coachMission,color=Lime,fontSize=14.sp)
            if(run.coachOutcome.isNotBlank())Text(run.coachOutcome,color=Muted,fontSize=13.sp)
            Text(if(run.openEnded)"Offene Runde · du bestimmst das Ende"else "${(run.plannedSeconds?:run.phases.sumOf{it.duration})/60} Minuten geplant",color=Muted,fontSize=12.sp)
            run.coachEvents.filter{it.kind in setOf(CueKind.EFFORT,CueKind.RECOVERY,CueKind.PROGRESS)}.takeLast(3).forEach{
                Text("${Metrics.duration(it.elapsed)} · ${it.text}",color=Muted,fontSize=12.sp)
            }
        }
        CardBlock{Eyebrow("Wie hat es sich angefühlt?");Text(run.rpe?.let{"Belastung $it / 10"}?:"Noch keine Einschätzung");if(run.note.isNotBlank())Text(run.note,color=Muted);OutlinedButton(onClick={edit=true},modifier=Modifier.fillMaxWidth()){Text(if(run.mode==RunMode.TREADMILL)"Distanz & Belastung ergänzen"else "Belastung & Notiz ergänzen")}}
        CardBlock{
            Eyebrow("Health Connect");Text(run.exportStatus,color=Muted,fontSize=13.sp)
            if(run.exportedRevision in 1 until run.revision)Text("Änderungen noch nicht geteilt",color=Peach,fontSize=12.sp)
            if(run.exportedRevision<run.revision)FullButton(if(busy)"Wird geteilt …"else if(profile.healthExport)"Übertragung erneut versuchen"else "In Health Connect speichern",enabled=!busy){busy=true;scope.launch{try{onNotice(HealthExporter(context).export(run))}finally{busy=false}}}
            Text("Andere Apps erhalten Zugriff nur mit deiner Freigabe in Health Connect.",color=Muted,fontSize=12.sp)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick={export.launch("laufklar-${localDate(run.started)}.json")},modifier=Modifier.weight(1f)){Text("JSON exportieren")};if(run.points.isNotEmpty())OutlinedButton(onClick={gpx.launch("laufklar-${localDate(run.started)}.gpx")},modifier=Modifier.weight(1f)){Text("GPX exportieren")}}
        TextButton(onClick={remove=true}){Text("Lauf lokal löschen",color=Peach)}
    }
    if(edit)AlertDialog(onDismissRequest={edit=false},title={Text("Lauf ergänzen")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(run.mode==RunMode.TREADMILL)Field("Enddistanz am Laufband · km",km,{km=it})
        Field("Belastung 1–10 (optional)",rpe,{rpe=it});Field("Notiz: Wetter, Steigung, Tagesform …",note,{note=it},false);error?.let{Text(it,color=Peach)}
    }},confirmButton={TextButton(onClick={
        val distance=km.replace(',','.').toDoubleOrNull();val effort=rpe.toIntOrNull()
        when{run.mode==RunMode.TREADMILL&&(distance==null||!distance.isFinite()||distance !in .01..200.0)->error="Bitte 0,01 bis 200 km eingeben."
            rpe.isNotBlank()&&(effort==null||effort !in 1..10)->error="Belastung zwischen 1 und 10 eingeben."
            else->scope.launch{val updated=run.copy(correctedMeters=if(run.mode==RunMode.TREADMILL)distance!!*1000 else null,rpe=effort,note=note.take(2000),revision=run.revision+1);repo.save(updated);edit=false;if(profile.healthExport)onNotice(HealthExporter(context).export(updated))}
        }
    }){Text("Speichern")}},dismissButton={TextButton(onClick={edit=false}){Text("Abbrechen")}})
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("Lauf lokal löschen?")},text={Text("Die lokale Aufzeichnung wird entfernt. Bereits in Health Connect geteilte Daten und exportierte Dateien bleiben dort erhalten; du kannst sie in Health Connect beziehungsweise im Dateimanager löschen.")},confirmButton={TextButton(onClick={scope.launch{repo.delete(run);remove=false;onBack()}}){Text("Lokal löschen",color=Peach)}},dismissButton={TextButton(onClick={remove=false}){Text("Behalten")}})
}
fun gpx(run:Run):String=buildString{
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"Laufklar\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Laufklar</name>")
    run.points.groupBy{it.segment}.values.forEach{part->append("<trkseg>");part.forEach{append("<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"><time>${Instant.ofEpochMilli(it.time)}</time></trkpt>")};append("</trkseg>")};append("</trk></gpx>")
}
@Composable fun PulseChart(run:Run){
    val minHr=(run.pulses.minOf{it.bpm}-10).coerceAtLeast(30);val maxHr=run.pulses.maxOf{it.bpm}+10
    Canvas(Modifier.fillMaxWidth().height(120.dp)){
        repeat(3){n->val y=size.height*n/2;drawLine(Muted.copy(alpha=.12f),Offset(0f,y),Offset(size.width,y))}
        val path=Path();var previous:Pulse?=null
        run.pulses.forEach{p->val x=(p.elapsed/run.activeSeconds.coerceAtLeast(1.0)*size.width).toFloat();val y=size.height*(1-(p.bpm-minHr).toFloat()/(maxHr-minHr));if(previous==null||p.time-previous!!.time>10_000)path.moveTo(x,y)else path.lineTo(x,y);previous=p}
        drawPath(path,Peach,style=Stroke(width=2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("$minHr–$maxHr bpm",color=Muted,fontSize=11.sp);Text("${Metrics.duration(run.activeSeconds)} min",color=Muted,fontSize=11.sp)}
}
