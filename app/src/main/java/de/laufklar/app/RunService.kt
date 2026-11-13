package de.laufklar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.max

object Live {
    val run=MutableStateFlow<Run?>(null)
    val paused=MutableStateFlow(false)
    val message=MutableStateFlow("Bereit für deinen nächsten Lauf")
    val gps=MutableStateFlow("GPS bereit")
    val bpm=MutableStateFlow<Pair<Int,Long>?>(null)
    val beltSpeed=MutableStateFlow(0.0)
    val saving=MutableStateFlow(false)
    val audioStatus=MutableStateFlow("Offline-Stimme wird vorbereitet")
    lateinit var sensor:HeartRateSensor
    fun initialize(context:android.content.Context){sensor=HeartRateSensor(context){bpm.value=it to SystemClock.elapsedRealtime();RunService.instance?.pulse(it)}}
}
class AudioCoach(context:android.content.Context) {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener{}.build()
    private var ready=false
    private var tts:TextToSpeech?=null
    init{
        tts=TextToSpeech(context){status->
            val engine=tts
            if(status==TextToSpeech.SUCCESS&&engine!=null){
                val voice=engine.voices?.firstOrNull{it.locale.language=="de"&&!it.isNetworkConnectionRequired&&!it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}
                if(voice!=null){engine.voice=voice;engine.setAudioAttributes(attributes);engine.setSpeechRate(.95f);ready=true;Live.audioStatus.value="Deutsche Offline-Stimme bereit"}
                else Live.audioStatus.value="Deutsche Offline-Stimme fehlt. In Android › Sprachausgabe installieren."
            }else Live.audioStatus.value="Sprachausgabe nicht verfügbar"
        }
        tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String?){}
            override fun onDone(id:String?){manager.abandonAudioFocusRequest(focus)}
            @Deprecated("Android callback") override fun onError(id:String?){manager.abandonAudioFocusRequest(focus)}
        })
    }
    fun speak(text:String){if(!ready)return;if(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"coach-${SystemClock.elapsedRealtime()}")}}
    fun close(){tts?.stop();tts?.shutdown();manager.abandonAudioFocusRequest(focus)}
}
class RunService:Service(),LocationListener {
    companion object {
        var instance:RunService?=null;private set
        const val START="start";const val PAUSE="pause";const val RESUME="resume";const val FINISH="finish"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var audio:AudioCoach
    private lateinit var profile:Profile
    private lateinit var locations:LocationManager
    private var timer:Job?=null
    private var wake:PowerManager.WakeLock?=null
    private var lastTick=0L
    private var startTick=0L
    private var startWall=0L
    private var pauseWall:Long?=null
    private var segment=0
    private var previousPoint:TrackPoint?=null
    private var lastCheckpoint=0L
    private val coach=Coach()
    private var finishing=false
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate();instance=this;locations=getSystemService(LocationManager::class.java);audio=AudioCoach(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("running","Laufaufzeichnung",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){START->start(intent);PAUSE->pause();RESUME->resume();FINISH->finish()}
        return START_NOT_STICKY
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action=if(Live.paused.value)RESUME else PAUSE
        val pause=PendingIntent.getService(this,1,Intent(this,RunService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val r=Live.run.value
        return Notification.Builder(this,"running").setSmallIcon(R.drawable.ic_launcher).setContentTitle(if(Live.paused.value)"Lauf pausiert" else "Laufklar zeichnet auf")
            .setContentText(r?.let{"${Metrics.km(it.meters)} km · ${Metrics.duration(it.activeSeconds)}"}?:"Lauf wird vorbereitet")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null,if(Live.paused.value)"Fortsetzen" else "Pause",pause).build()).build()
    }
    @SuppressLint("WakelockTimeout")
    private fun start(intent:Intent){
        if(Live.run.value!=null)return
        val mode=RunMode.valueOf(intent.getStringExtra("mode")?:RunMode.OUTDOOR.name)
        var type=if(mode==RunMode.OUTDOOR)ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED) type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try{startForeground(71,notification(),type)}catch(e:Exception){Live.message.value="Aufzeichnung konnte nicht starten. Standort-/Bluetooth-Berechtigung prüfen.";stopSelf();return}
        profile=repo().profile.value
        var workout=Workout.valueOf(intent.getStringExtra("workout")?:Workout.EASY.name)
        if(workout==Workout.INTERVALS&&!profile.intensityReady)workout=Workout.EASY
        startTick=SystemClock.elapsedRealtime();lastTick=startTick;startWall=System.currentTimeMillis();lastCheckpoint=0
        Live.run.value=Run(started=startWall,mode=mode,workout=workout,zoneBounds=profile.bounds,estimatedZones=profile.estimated)
        Live.paused.value=false;Live.saving.value=false;finishing=false;coach.reset(0.0,profile.cueMeters)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"laufklar:run").apply{acquire(6*60*60*1000L)}
        profile.sensorAddress?.let{if(!Live.sensor.connected.value)Live.sensor.connect(it)}
        if(mode==RunMode.OUTDOOR)startGps()
        timer=scope.launch{
            while(isActive){
                tick()
                if(SystemClock.elapsedRealtime()-lastCheckpoint>=5000){
                    try{Live.run.value?.let{repo().checkpoint(it,wall())};lastCheckpoint=SystemClock.elapsedRealtime()}
                    catch(e:Exception){Live.message.value="Speichern fehlgeschlagen. Aufzeichnung pausiert; Speicherplatz prüfen.";pause()}
                    getSystemService(NotificationManager::class.java).notify(71,notification())
                }
                if(SystemClock.elapsedRealtime()-startTick>6*60*60*1000L){finish();break}
                delay(1000)
            }
        }
    }
    private fun wall()=startWall+(SystemClock.elapsedRealtime()-startTick)
    fun timeNow()=wall()
    private fun tick(){
        val now=SystemClock.elapsedRealtime();val dt=(now-lastTick)/1000.0;lastTick=now
        if(Live.paused.value||finishing)return
        val old=Live.run.value?:return
        var r=old.copy(activeSeconds=old.activeSeconds+dt)
        if(r.mode==RunMode.TREADMILL){
            val speed=Live.beltSpeed.value/3.6
            r=r.copy(meters=r.meters+dt*speed,motion=r.motion+Motion(wall(),r.activeSeconds,r.meters+dt*speed,speed.takeIf{it>0}))
        }
        Live.run.value=r
        coach.update(r,profile,wall())?.let{Live.message.value=it;if(profile.audio)audio.speak(it)}
    }
    @SuppressLint("MissingPermission")
    private fun startGps(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){Live.gps.value="Präziser Standort fehlt";return}
        try{Live.gps.value=if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER))"Warte auf gutes GPS …" else "GPS ausgeschaltet · in Android aktivieren"
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this,Looper.getMainLooper())
        }catch(e:Exception){Live.gps.value="GPS konnte nicht gestartet werden"}
    }
    override fun onLocationChanged(location:Location){
        if(Live.paused.value||finishing)return
        val r=Live.run.value?:return
        if(r.mode!=RunMode.OUTDOOR)return
        val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1e9
        if(age !in 0.0..5.0)return
        val p=TrackPoint(wall(),r.activeSeconds,location.latitude,location.longitude,location.accuracy.toDouble(),segment)
        if(!Metrics.acceptedGps(previousPoint,p)){Live.gps.value="GPS ungenau · Punkt verworfen";return}
        val previous=previousPoint
        var distance=0.0
        var point=p
        if(previous!=null&&previous.segment==segment){
            val dt=(p.time-previous.time)/1000.0
            if(dt>15){segment++;point=p.copy(segment=segment)}
            else{
                val movement=Metrics.distance(previous,p)
                // Ignore tiny stationary jitter without discarding the anchor for slow walking.
                if(movement<max(3.0, minOf(p.accuracy,previous.accuracy)*.25)){Live.gps.value="GPS ±${location.accuracy.toInt()} m";return}
                distance=movement
            }
        }
        previousPoint=point
        val speed=if(location.hasSpeed()&&location.speedAccuracyMetersPerSecond<=1.5f&&location.speed in 0f..9f)location.speed.toDouble()
            else if(distance>0&&previous!=null) distance/((point.time-previous.time)/1000.0) else null
        Live.run.value=r.copy(meters=r.meters+distance,points=r.points+point,motion=r.motion+Motion(point.time,r.activeSeconds,r.meters+distance,speed))
        Live.gps.value="GPS ±${location.accuracy.toInt()} m"
    }
    override fun onProviderDisabled(provider:String){Live.gps.value="GPS ausgeschaltet · Strecke wird nicht ergänzt";segment++;previousPoint=null}
    override fun onProviderEnabled(provider:String){Live.gps.value="Warte auf GPS …"}
    fun pulse(bpm:Int){
        if(Live.paused.value||finishing)return
        val r=Live.run.value?:return
        val now=wall()
        if(r.pulses.lastOrNull()?.let{now-it.time<800}==true)return
        Live.run.value=r.copy(pulses=r.pulses+Pulse(now,r.activeSeconds,bpm))
    }
    fun speed(kmh:Double){if(kmh.isFinite()&&kmh in 0.0..25.0){tick();Live.beltSpeed.value=kmh}}
    private fun pause(){if(Live.run.value==null||Live.paused.value||finishing)return;tick();Live.paused.value=true;pauseWall=wall();segment++;previousPoint=null;Live.message.value="Pausiert · Zeit und Strecke stehen";scope.launch{runCatching{repo().checkpoint(Live.run.value!!,wall())}};getSystemService(NotificationManager::class.java).notify(71,notification())}
    private fun resume(){if(Live.run.value==null||!Live.paused.value||finishing)return;val r=Live.run.value!!;pauseWall?.let{Live.run.value=r.copy(pauses=r.pauses+PauseSpan(it,wall()))};pauseWall=null;lastTick=SystemClock.elapsedRealtime();Live.paused.value=false;Live.message.value="Weiter geht’s. Locker anlaufen.";getSystemService(NotificationManager::class.java).notify(71,notification())}
    private fun finish(){
        if(finishing||Live.run.value==null)return
        tick();finishing=true;Live.saving.value=true;timer?.cancel();locations.removeUpdates(this)
        val old=Live.run.value!!;val end=wall().coerceAtLeast(old.started+1)
        val run=old.copy(ended=end,pauses=old.pauses+listOfNotNull(pauseWall?.let{PauseSpan(it,end)}))
        scope.launch{
            try{
                repo().save(run,true)
                if(profile.healthExport) runCatching { HealthExporter(this@RunService).export(run) }
                Live.run.value=null;Live.paused.value=false;Live.message.value="Lauf gespeichert"
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }catch(e:Exception){finishing=false;Live.paused.value=true;Live.message.value="Speichern fehlgeschlagen. Lauf bleibt geöffnet; bitte erneut speichern."}
            finally{Live.saving.value=false}
        }
    }
    override fun onDestroy(){timer?.cancel();scope.cancel();locations.removeUpdates(this);audio.close();if(wake?.isHeld==true)wake?.release();instance=null;super.onDestroy()}
}
