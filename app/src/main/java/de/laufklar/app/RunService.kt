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
import kotlin.math.max

object Live {
    val run=MutableStateFlow<Run?>(null)
    val paused=MutableStateFlow(false)
    val autoPaused=MutableStateFlow(false)
    val cooldownSeconds=MutableStateFlow<Int?>(null)
    val message=MutableStateFlow("Bereit für deinen nächsten Lauf")
    val gps=MutableStateFlow("GPS bereit")
    val bpm=MutableStateFlow<Pair<Int,Long>?>(null)
    val beltSpeed=MutableStateFlow(0.0)
    val saving=MutableStateFlow(false)
    val reviewing=MutableStateFlow(false)
    val audioStatus=MutableStateFlow("Offline-Stimme wird vorbereitet")
    lateinit var sensor:HeartRateSensor
    lateinit var sensorOwnership:SensorOwnership
    fun initialize(context:android.content.Context){
        sensor=HeartRateSensor(context,sample={bpm.value=it to SystemClock.elapsedRealtime();RunService.instance?.pulse(it)},signalLost={bpm.value=null})
        sensorOwnership=SensorOwnership(connect={sensor.connect(it)},release={sensor.close()})
    }
}
class AudioCoach(context:android.content.Context) {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener{}.build()
    private var ready=false
    private val handler=Handler(Looper.getMainLooper())
    private val pending=ArrayDeque<String>()
    private var activeId:String?=null
    private var sequence=0L
    private var closed=false
    private var hasFocus=false
    private var tts:TextToSpeech?=null
    private val retry=Runnable{playNext()}
    init{
        tts=TextToSpeech(context){status->
            handler.post{
            if(closed)return@post
            val engine=tts
            if(status==TextToSpeech.SUCCESS&&engine!=null){
                val voice=engine.voices?.firstOrNull{it.locale.language=="de"&&!it.isNetworkConnectionRequired&&!it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}
                if(voice!=null&&!closed){engine.voice=voice;engine.setAudioAttributes(attributes);engine.setSpeechRate(1.08f);ready=true;Live.audioStatus.value="Deutsche Offline-Stimme bereit";playNext()}
                else Live.audioStatus.value="Deutsche Offline-Stimme fehlt. In Android › Sprachausgabe installieren."
            }else Live.audioStatus.value="Sprachausgabe nicht verfügbar"
            }
        }
        tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String?){}
            override fun onDone(id:String?){completed(id)}
            @Deprecated("Android callback") override fun onError(id:String?){completed(id)}
            override fun onError(id:String?,errorCode:Int){completed(id)}
            override fun onStop(id:String?,interrupted:Boolean){completed(id)}
        })
    }
    fun speak(text:String){if(closed)return;pending.addLast(text);playNext()}
    private fun playNext(){
        if(!ready||closed||activeId!=null)return
        if(pending.isEmpty()){releaseFocus();return}
        if(!hasFocus){
            hasFocus=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if(!hasFocus){handler.removeCallbacks(retry);handler.postDelayed(retry,1000);return}
        }
        val text=pending.removeFirst().replace(Regex("(\\d{1,2}):(\\d{2})")){"${it.groupValues[1]} Minuten ${it.groupValues[2]} Sekunden pro Kilometer"}
        val id="coach-${++sequence}";activeId=id
        if(tts?.speak(text,TextToSpeech.QUEUE_ADD,null,id)!=TextToSpeech.SUCCESS)completed(id)
    }
    private fun completed(id:String?){handler.post{if(id!=null&&id==activeId){activeId=null;playNext()}}}
    private fun releaseFocus(){if(hasFocus){manager.abandonAudioFocusRequest(focus);hasFocus=false}}
    fun stop(){pending.clear();activeId=null;handler.removeCallbacks(retry);tts?.stop();releaseFocus()}
    fun close(){closed=true;stop();tts?.shutdown()}
}
class RunService:Service(),LocationListener {
    companion object {
        var instance:RunService?=null;private set
        const val START="start";const val PAUSE="pause";const val RESUME="resume";const val FINISH="finish"
        const val REVIEW="review";const val CANCEL_REVIEW="cancel_review";const val DISCARD="discard"
        const val STOP_COOLDOWN="stop_cooldown"
        const val FINISH_METERS="finish_meters"
        const val CHANNEL="running_status"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var audio:AudioCoach
    private lateinit var profile:Profile
    private lateinit var locations:LocationManager
    private var timer:Job?=null
    private var checkpointJob:Job?=null
    private var wake:PowerManager.WakeLock?=null
    private var lastTick=0L
    private var startTick=0L
    private var startWall=0L
    private var pauseWall:Long?=null
    private var reviewEnd:Long?=null
    private var reviewWasPaused=false
    private var reviewWasAutoPaused=false
    private var segment=0
    private var previousPoint:TrackPoint?=null
    private var previousFix:TrackPoint?=null
    private val autoPause=AutoPauseDetector()
    private var lastCheckpoint=0L
    private val coach=Coach()
    private var finishing=false
    private var stopCooldown=false
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate();instance=this;locations=getSystemService(LocationManager::class.java);audio=AudioCoach(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Aktive Laufaufzeichnung",NotificationManager.IMPORTANCE_DEFAULT).apply{
            description="Statussymbol während eines aktiven oder pausierten Laufs"
            setSound(null,null);enableVibration(false);setShowBadge(false)
        })
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            START->start(intent);PAUSE->pause();RESUME->resume();REVIEW->review();CANCEL_REVIEW->cancelReview();DISCARD->finish(discard=true)
            FINISH->finish(correctedMeters=if(intent.hasExtra(FINISH_METERS))intent.getDoubleExtra(FINISH_METERS,Double.NaN)else null)
            STOP_COOLDOWN->stopCooldown=true
        }
        return START_NOT_STICKY
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action=if(Live.paused.value)RESUME else PAUSE
        val pause=PendingIntent.getService(this,1,Intent(this,RunService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val r=Live.run.value
        val cooldown=Live.cooldownSeconds.value
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_running).setContentTitle(if(cooldown!=null)"Cooldown wird gemessen"else if(r?.ended!=null||Live.reviewing.value)"Lauf abschließen"else if(Live.autoPaused.value)"Lauf automatisch pausiert"else if(Live.paused.value)"Lauf pausiert" else "Laufklar zeichnet auf")
            .setContentText(cooldown?.let{"Noch $it Sekunden · Pulsverbindung bleibt aktiv"}?:r?.let{"${Metrics.km(it.meters)} km · ${Metrics.duration(it.activeSeconds)}"}?:"Lauf wird vorbereitet")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null,if(r?.ended!=null)"Cooldown ansehen"else if(Live.reviewing.value)"Lauf prüfen"else if(Live.paused.value)"Fortsetzen" else "Pause",if(Live.reviewing.value||r?.ended!=null)open else pause).build()).build()
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
        val readiness=runCatching { Readiness.valueOf(intent.getStringExtra("readiness")?:"READY") }.getOrDefault(Readiness.READY)
        val plan=AdaptiveTrainer.plan(profile,repo().runs.value,mode,readiness)
        if(readiness==Readiness.UNWELL){Live.message.value=plan.reason;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return}
        if(workout !in plan.available())workout=Workout.EASY
        startTick=SystemClock.elapsedRealtime();lastTick=startTick;startWall=System.currentTimeMillis();lastCheckpoint=0
        Live.sensor.rememberDevice(profile.sensorAddress,profile.sensorName)
        Live.sensorOwnership.device(profile.sensorAddress);Live.sensorOwnership.recording(true)
        Live.run.value=Run(started=startWall,mode=mode,workout=workout,zoneBounds=profile.bounds,estimatedZones=profile.estimated,phases=plan.phases(workout),targetPace=plan.targetPace,coachReason=plan.reason,readiness=readiness.name)
        Live.paused.value=false;Live.autoPaused.value=false;Live.cooldownSeconds.value=null;Live.saving.value=false;Live.reviewing.value=false;Live.beltSpeed.value=0.0;finishing=false;stopCooldown=false;autoPause.reset();previousFix=null;coach.reset(0.0,profile.cueMeters)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"laufklar:run").apply{acquire((6*60*60+120)*1000L)}
        if(mode==RunMode.OUTDOOR)startGps()
        startTimer()
    }
    private fun startTimer(){
        timer=scope.launch{
            while(isActive){
                tick()
                if(SystemClock.elapsedRealtime()-lastCheckpoint>=5000){
                    try{Live.run.value?.let{repo().checkpoint(it,reviewEnd?:wall())};lastCheckpoint=SystemClock.elapsedRealtime()}
                    catch(e:CancellationException){throw e}
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
        if(Live.paused.value||finishing||Live.run.value?.ended!=null)return
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
        if(finishing||Live.reviewing.value||Live.run.value?.ended!=null)return
        var r=Live.run.value?:return
        if(r.mode!=RunMode.OUTDOOR)return
        val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1e9
        if(age !in 0.0..5.0||!location.hasAccuracy()){autoPause.reset();return}
        val fix=TrackPoint(wall(),r.activeSeconds,location.latitude,location.longitude,location.accuracy.toDouble(),segment)
        if(!Metrics.acceptedGps(previousFix,fix)){autoPause.reset();Live.gps.value="GPS ungenau · Punkt verworfen";return}
        previousFix=fix
        val measuredSpeed=location.speed.toDouble().takeIf{location.hasSpeed()&&location.hasSpeedAccuracy()&&location.speedAccuracyMetersPerSecond in 0f..1f&&location.accuracy<=15f&&it in 0.0..9.0}
        if(profile.autoPause&&(!Live.paused.value||Live.autoPaused.value)){
            when(autoPause.sample(measuredSpeed,location.elapsedRealtimeNanos/1_000_000,Live.autoPaused.value)){
                AutoPauseDetector.Action.PAUSE->pause(automatic=true)
                AutoPauseDetector.Action.RESUME->resume(automatic=true)
                null->Unit
            }
        }
        Live.gps.value="GPS ±${location.accuracy.toInt()} m"
        if(Live.paused.value)return
        r=Live.run.value?:return
        val p=fix.copy(elapsed=r.activeSeconds,segment=segment)
        if(!Metrics.acceptedGps(previousPoint,p))return
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
        val speed=measuredSpeed ?: if(distance>0&&previous!=null) distance/((point.time-previous.time)/1000.0) else null
        Live.run.value=r.copy(meters=r.meters+distance,points=r.points+point,motion=r.motion+Motion(point.time,r.activeSeconds,r.meters+distance,speed))
        Live.gps.value="GPS ±${location.accuracy.toInt()} m"
    }
    override fun onProviderDisabled(provider:String){Live.gps.value="GPS ausgeschaltet · Strecke wird nicht ergänzt";segment++;previousPoint=null;previousFix=null;autoPause.reset()}
    override fun onProviderEnabled(provider:String){Live.gps.value="Warte auf GPS …"}
    fun pulse(bpm:Int){
        val r=Live.run.value?:return
        val now=wall()
        val cooldown=r.cooldown
        if(cooldown?.state==CooldownState.MEASURING){
            if(now-cooldown.started in 0..Cooldown.DURATION_MS&&cooldown.pulses.lastOrNull()?.let{now-it.time<800}!=true)
                Live.run.value=r.copy(cooldown=cooldown.copy(pulses=cooldown.pulses+CooldownPulse(now,bpm)))
            return
        }
        if(Live.paused.value||finishing||r.ended!=null)return
        if(r.pulses.lastOrNull()?.let{now-it.time<800}==true)return
        Live.run.value=r.copy(pulses=r.pulses+Pulse(now,r.activeSeconds,bpm))
    }
    fun speed(kmh:Double){if(!finishing&&Live.run.value?.ended==null&&kmh.isFinite()&&kmh in 0.0..25.0){tick();Live.beltSpeed.value=kmh}}
    private fun pause(automatic:Boolean=false){
        if(Live.run.value==null||finishing||Live.run.value?.ended!=null)return
        if(Live.paused.value){
            if(!automatic){Live.autoPaused.value=false;autoPause.reset();audio.stop();Live.message.value="Pausiert · Zeit und Strecke stehen";getSystemService(NotificationManager::class.java).notify(71,notification())}
            return
        }
        tick();Live.paused.value=true;Live.autoPaused.value=automatic;autoPause.reset();pauseWall=wall();segment++;previousPoint=null
        if(!automatic)audio.stop()
        Live.message.value=if(automatic)"Automatisch pausiert. Bei Bewegung geht es weiter."else "Pausiert · Zeit und Strecke stehen"
        if(automatic&&profile.audio)audio.speak("Lauf automatisch pausiert. Bei Bewegung geht es weiter.")
        val snapshot=Live.run.value!!;val time=wall()
        checkpointJob?.cancel();checkpointJob=scope.launch{runCatching{repo().checkpoint(snapshot,time)}}
        getSystemService(NotificationManager::class.java).notify(71,notification())
    }
    private fun resume(automatic:Boolean=false){
        if(Live.run.value==null||!Live.paused.value||finishing||Live.reviewing.value||Live.run.value?.ended!=null)return
        Live.reviewing.value=false;reviewEnd=null;Live.sensorOwnership.recording(true)
        val r=Live.run.value!!;val now=wall()
        pauseWall?.takeIf{it<now}?.let{Live.run.value=r.copy(pauses=r.pauses+PauseSpan(it,now))}
        pauseWall=null;lastTick=SystemClock.elapsedRealtime();Live.paused.value=false;Live.autoPaused.value=false;autoPause.reset()
        Live.message.value=if(automatic)"Lauf automatisch fortgesetzt. Locker anlaufen."else "Weiter geht’s. Locker anlaufen."
        if(automatic&&profile.audio)audio.speak(Live.message.value)
        if(r.mode==RunMode.OUTDOOR)startGps()
        getSystemService(NotificationManager::class.java).notify(71,notification())
    }
    private fun review(){
        if(Live.run.value==null||finishing||Live.reviewing.value||Live.run.value?.ended!=null)return
        reviewWasPaused=Live.paused.value;reviewWasAutoPaused=Live.autoPaused.value;pause();reviewEnd=wall();Live.reviewing.value=true
        getSystemService(NotificationManager::class.java).notify(71,notification())
    }
    private fun cancelReview(){
        if(!Live.reviewing.value||finishing)return
        Live.reviewing.value=false;reviewEnd=null
        if(!reviewWasPaused)resume()else {Live.autoPaused.value=reviewWasAutoPaused;autoPause.reset();if(reviewWasAutoPaused)Live.message.value="Automatisch pausiert. Bei Bewegung geht es weiter.";getSystemService(NotificationManager::class.java).notify(71,notification())}
    }
    private fun finish(correctedMeters:Double?=null,discard:Boolean=false){
        if(finishing||Live.run.value==null)return
        if(correctedMeters!=null&&(Live.run.value!!.mode!=RunMode.TREADMILL||!correctedMeters.isFinite()||correctedMeters !in 10.0..200000.0)){
            Live.message.value="Bitte eine Laufbanddistanz zwischen 0,01 und 200 km eingeben.";return
        }
        tick();finishing=true;Live.saving.value=true;timer?.cancel();locations.removeUpdates(this);audio.stop();autoPause.reset()
        val old=Live.run.value!!;val end=(old.ended?:reviewEnd?:wall()).coerceAtLeast(old.started+1)
        val rest=repo().profile.value.restingHr
        val currentBpm=Live.bpm.value?.takeIf{SystemClock.elapsedRealtime()-it.second in 0..5_000}?.first
        val cooldown=old.cooldown?:if(!discard&&old.ended==null&&currentBpm!=null&&(rest==null||currentBpm>rest))Cooldown(wall(),currentBpm,rest)else null
        val run=old.copy(ended=end,correctedMeters=correctedMeters?:old.correctedMeters,cooldown=cooldown,
            exportStatus=if(old.ended==null&&repo().profile.value.healthExport)"Ausstehend: Automatische Übergabe nach dem Cooldown"else old.exportStatus,
            pauses=if(old.ended!=null)old.pauses else old.pauses+listOfNotNull(pauseWall?.takeIf{it<end}?.let{PauseSpan(it,end)}))
        Live.run.value=run;Live.paused.value=true;Live.autoPaused.value=false;Live.reviewing.value=false;stopCooldown=false
        if(discard||cooldown?.state!=CooldownState.MEASURING)Live.sensorOwnership.recording(false)
        scope.launch{
            try{
                timer?.join();checkpointJob?.cancelAndJoin()
                if(discard){repo().discardCheckpoint(run.id);repo().delete(run)}else{
                    // Persist the completed run immediately; recovery samples never extend its time or distance.
                    repo().save(run,true)
                    if(cooldown?.state==CooldownState.MEASURING){
                        Live.saving.value=false
                        Live.message.value="Lauf gespeichert. Dein Puls wird noch 60 Sekunden für den Cooldown gemessen."
                        if(profile.audio)audio.speak("Lauf gespeichert. Ich messe deinen Cooldown für sechzig Sekunden weiter.")
                        var lastSaved=wall()
                        while(!stopCooldown&&wall()-cooldown.started<Cooldown.DURATION_MS){
                            val remaining=((Cooldown.DURATION_MS-(wall()-cooldown.started)+999)/1000).toInt().coerceIn(0,60)
                            if(Live.cooldownSeconds.value!=remaining){Live.cooldownSeconds.value=remaining;getSystemService(NotificationManager::class.java).notify(71,notification())}
                            if(wall()-lastSaved>=5_000){repo().save(Live.run.value!!);lastSaved=wall()}
                            delay(250)
                        }
                        val latest=Live.run.value!!
                        val complete=wall()-cooldown.started>=Cooldown.DURATION_MS
                        Live.run.value=latest.copy(cooldown=latest.cooldown!!.copy(
                            ended=if(complete)cooldown.started+Cooldown.DURATION_MS else wall(),
                            state=if(complete)CooldownState.COMPLETE else CooldownState.INTERRUPTED),revision=latest.revision+1)
                    }
                    Live.cooldownSeconds.value=null;Live.saving.value=true;Live.sensorOwnership.recording(false)
                    val completed=Live.run.value!!
                    repo().save(completed,true)
                    if(repo().profile.value.healthExport)HealthExporter(this@RunService).export(completed)
                }
                Live.run.value=null;Live.paused.value=false;Live.autoPaused.value=false;Live.reviewing.value=false;Live.cooldownSeconds.value=null;Live.beltSpeed.value=0.0
                Live.message.value=if(discard)"Lauf nicht gespeichert"else "Lauf gespeichert"
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                val latest=Live.run.value
                if(latest?.cooldown?.state==CooldownState.MEASURING)Live.run.value=latest.copy(
                    cooldown=latest.cooldown.copy(state=CooldownState.INTERRUPTED,ended=wall()),revision=latest.revision+1)
                finishing=false;Live.cooldownSeconds.value=null;Live.sensorOwnership.recording(false)
                Live.message.value="Abschließen fehlgeschlagen. Die Aufzeichnung bleibt geöffnet; bitte erneut speichern."
                getSystemService(NotificationManager::class.java).notify(71,notification())
            }
            finally{Live.saving.value=false}
        }
    }
    override fun onDestroy(){Live.sensorOwnership.recording(false);timer?.cancel();checkpointJob?.cancel();scope.cancel();locations.removeUpdates(this);audio.close();Live.cooldownSeconds.value=null;if(wake?.isHeld==true)wake?.release();instance=null;super.onDestroy()}
}
