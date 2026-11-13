package de.laufklar.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class HealthExporter(private val context:Context) {
    companion object {
        private val mutex=Mutex()
        val basePermissions=setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),HealthPermission.getWritePermission(SpeedRecord::class)
        )
        const val routePermission="android.permission.health.WRITE_EXERCISE_ROUTE"
    }
    fun available()=HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE
    suspend fun exportPending(){
        val repo=context.repo()
        if(!repo.profile.value.healthExport||!available())return
        for(run in repo.runs.value.filter{it.ended!=null&&it.exportedRevision<it.revision&&it.cooldown?.state!=CooldownState.MEASURING&&(it.exportStatus.startsWith("Ausstehend:")||it.exportedRevision>0)}){
            if(!repo.profile.value.healthExport)return
            if(Live.run.value?.id!=run.id){
                try{export(run)}catch(e:CancellationException){throw e}catch(e:Exception){return}
            }
        }
    }
    suspend fun export(original:Run):String=mutex.withLock {
        val repo=context.repo()
        val run=repo.runs.value.find{it.id==original.id}?:return@withLock "Lauf nicht mehr vorhanden"
        if(run.ended==null)return@withLock "Lauf noch aktiv"
        if(run.cooldown?.state==CooldownState.MEASURING)return@withLock "Cooldown wird noch gemessen"
        if(run.exportedRevision>=run.revision)return@withLock run.exportStatus
        var status:String
        try{
            check(available()){ "Health Connect ist nicht verfügbar" }
            val client=HealthConnectClient.getOrCreate(context)
            val grants=client.permissionController.getGrantedPermissions()
            check(grants.containsAll(basePermissions)){"Schreibfreigaben fehlen. Unter Profil › Health Connect verbinden."}
            val start=Instant.ofEpochMilli(run.started);val end=Instant.ofEpochMilli(run.ended)
            val zone=ZoneId.systemDefault();val startOffset=zone.rules.getOffset(start);val endOffset=zone.rules.getOffset(end)
            fun meta(part:String,manual:Boolean=false):Metadata {
                val id="laufklar:${run.id}:$part"
                return if(manual) Metadata.manualEntry(clientRecordId=id,clientRecordVersion=run.revision)
                else Metadata.activelyRecorded(clientRecordId=id,clientRecordVersion=run.revision,device=Device(type=Device.TYPE_PHONE))
            }
            val includeRoute=repo.profile.value.shareRoute && routePermission in grants
            val route=run.points.filter{it.time>=run.started&&it.time<run.ended}.takeIf{includeRoute&&it.isNotEmpty()}?.let{points->
                ExerciseRoute(points.map{ExerciseRoute.Location(time=Instant.ofEpochMilli(it.time),latitude=it.lat,longitude=it.lon,horizontalAccuracy=Length.meters(it.accuracy))})
            }
            val records=mutableListOf<Record>(ExerciseSessionRecord(
                startTime=start,startZoneOffset=startOffset,endTime=end,endZoneOffset=endOffset,
                exerciseType=if(run.mode==RunMode.TREADMILL)ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title="${run.workout.label} · Laufklar",notes="Aktive Zeit: ${Metrics.duration(run.activeSeconds)}. ${if(run.mode==RunMode.TREADMILL)"Laufbanddistanz manuell / aus Eingabe geschätzt."else "GPS-Aufzeichnung."} ${run.cooldown?.let{c->c.dropBpm?.let{"Pulsabfall nach 60 Sekunden ab Speichern: $it bpm. ${c.score?.let{s->"Persönlicher Cooldown-Score: $s/100 (Anteil der Annäherung an den Ruhepuls)."}?:""}"}?:"Cooldown ohne auswertbaren 60-Sekunden-Endwert."}?:""} ${run.note}",
                exerciseRoute=route,segments=run.pauses.filter{it.until>it.from}.map{ExerciseSegment(Instant.ofEpochMilli(it.from),Instant.ofEpochMilli(it.until),ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE)},metadata=meta("session")
            ))
            if(run.distance>0)records.add(DistanceRecord(start,startOffset,end,endOffset,Length.meters(run.distance),meta("distance",run.mode==RunMode.TREADMILL)))
            val pulses=run.pulses.filter{it.time>=run.started&&it.time<run.ended}
            if(pulses.isNotEmpty())records.add(HeartRateRecord(start,startOffset,end,endOffset,pulses.map{HeartRateRecord.Sample(Instant.ofEpochMilli(it.time),it.bpm.toLong())},Metadata.activelyRecorded(clientRecordId="laufklar:${run.id}:hr",clientRecordVersion=run.revision,device=Device(type=Device.TYPE_FITNESS_BAND,manufacturer="Google",model=repo.profile.value.sensorName?:"Bluetooth Heart Rate"))))
            run.cooldown?.let{c->
                val samples=c.pulses.distinctBy{it.time}.sortedBy{it.time}
                if(samples.isNotEmpty()){
                    val recoveryStart=Instant.ofEpochMilli(c.started);val recoveryEnd=Instant.ofEpochMilli(samples.last().time+1)
                    records.add(HeartRateRecord(recoveryStart,zone.rules.getOffset(recoveryStart),recoveryEnd,zone.rules.getOffset(recoveryEnd),
                        samples.map{HeartRateRecord.Sample(Instant.ofEpochMilli(it.time),it.bpm.toLong())},
                        Metadata.activelyRecorded(clientRecordId="laufklar:${run.id}:cooldown-hr",clientRecordVersion=run.revision,device=Device(type=Device.TYPE_FITNESS_BAND,model=repo.profile.value.sensorName?:"Bluetooth Heart Rate"))))
                }
            }
            // Corrected treadmill totals cannot make the entered speed trace a measured signal.
            val speeds=run.motion.filter{it.time>=run.started&&it.time<run.ended&&it.speed!=null&&it.speed>=0}.distinctBy{it.time}
            if(speeds.isNotEmpty())records.add(SpeedRecord(start,startOffset,end,endOffset,speeds.map{SpeedRecord.Sample(Instant.ofEpochMilli(it.time),Velocity.metersPerSecond(it.speed!!))},meta("speed",run.mode==RunMode.TREADMILL)))
            client.insertRecords(records)
            status=if(repo.profile.value.shareRoute&&!includeRoute&&run.points.isNotEmpty())"Geteilt · Route nicht freigegeben"else "In Health Connect gespeichert"
            repo.exportResult(run.id,run.revision,status,success=true)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            status=when(e){is SecurityException->"Health-Connect-Freigabe fehlt oder wurde entzogen";else->e.message?.take(150)?:"Export fehlgeschlagen · erneut versuchen"}
            repo.exportResult(run.id,run.revision,"Ausstehend: $status",success=false)
        };status
    }
}
