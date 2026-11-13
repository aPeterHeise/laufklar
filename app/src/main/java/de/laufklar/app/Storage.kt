package de.laufklar.app

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LaufklarApp : Application() {
    lateinit var repository: Repository
    override fun onCreate() { super.onCreate(); repository = Repository(this); Live.initialize(this) }
}
fun Context.repo() = (applicationContext as LaufklarApp).repository

object Codec {
    fun profile(p: Profile) = JSONObject().apply {
        put("age", p.age); put("weight", p.weight); put("sex", p.sex); put("max", p.measuredMax); put("rest", p.restingHr)
        put("reserve", p.reserveZones); put("bounds", p.customBounds?.let { JSONArray(it) }); put("audio", p.audio); put("cue", p.cueMeters)
        put("health", p.healthExport); put("route", p.shareRoute); put("maps", p.onlineMaps); put("ready", p.intensityReady)
        put("goalKm", p.goalKm); put("goalPace", p.goalPace); put("frequency", p.runsPerWeek); put("pace", p.usualPace ?: JSONObject.NULL); put("address", p.sensorAddress); put("sensor", p.sensorName)
    }
    fun profile(j: JSONObject) = Profile(
        age=j.optInt("age",38), weight=j.optDouble("weight",83.0), sex=j.optString("sex","Männlich"),
        measuredMax=if(j.has("max")) j.getInt("max") else null, restingHr=if(j.has("rest")) j.getInt("rest") else null,
        reserveZones=j.optBoolean("reserve"), customBounds=j.optJSONArray("bounds")?.let { a -> List(a.length()) { a.getInt(it) } },
        audio=j.optBoolean("audio",true), cueMeters=j.optInt("cue",500).coerceIn(100,2000), healthExport=j.optBoolean("health"),
        shareRoute=j.optBoolean("route"), onlineMaps=j.optBoolean("maps",true), intensityReady=j.optBoolean("ready"),
        goalKm=j.optDouble("goalKm",10.0), goalPace=j.optInt("goalPace",300), runsPerWeek=j.optInt("frequency",3), usualPace=if(j.has("pace")&&j.isNull("pace"))null else j.optInt("pace",345), sensorAddress=j.optString("address").takeIf{it.isNotBlank()}, sensorName=j.optString("sensor").takeIf{it.isNotBlank()}
    )
    fun run(r: Run) = JSONObject().apply {
        put("id",r.id); put("start",r.started); put("end",r.ended); put("mode",r.mode.name); put("workout",r.workout.name)
        put("seconds",r.activeSeconds); put("meters",r.meters); put("corrected",r.correctedMeters); put("bounds",JSONArray(r.zoneBounds)); put("estimated",r.estimatedZones)
        put("points",JSONArray(r.points.map{JSONArray(listOf(it.time,it.elapsed,it.lat,it.lon,it.accuracy,it.segment))}))
        put("pulses",JSONArray(r.pulses.map{JSONArray(listOf(it.time,it.elapsed,it.bpm))}))
        put("motion",JSONArray(r.motion.map{JSONArray(listOf(it.time,it.elapsed,it.meters,it.speed))}))
        put("pauses",JSONArray(r.pauses.map{JSONArray(listOf(it.from,it.until))}))
        put("phases",JSONArray(r.phases.map{JSONArray(listOf(it.name,it.duration,it.intense))}));put("targetPace",r.targetPace);put("reason",r.coachReason);put("readiness",r.readiness)
        put("rpe",r.rpe); put("note",r.note); put("export",r.exportStatus); put("revision",r.revision); put("exportedRevision",r.exportedRevision); put("recovered",r.recovered)
    }
    private fun <T> array(j:JSONObject,key:String, map:(JSONArray)->T):List<T> = j.optJSONArray(key)?.let{a->List(a.length()){map(a.getJSONArray(it))}} ?: emptyList()
    fun run(j: JSONObject) = Run(
        id=j.getString("id"),started=j.getLong("start"),ended=if(j.has("end"))j.getLong("end")else null,
        mode=RunMode.valueOf(j.getString("mode")),workout=Workout.valueOf(j.getString("workout")),activeSeconds=j.getDouble("seconds"),meters=j.getDouble("meters"),correctedMeters=if(j.has("corrected"))j.getDouble("corrected")else null,
        zoneBounds=j.getJSONArray("bounds").let{a->List(a.length()){a.getInt(it)}}, estimatedZones=j.optBoolean("estimated",true),
        points=array(j,"points"){TrackPoint(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getInt(5))},
        pulses=array(j,"pulses"){Pulse(it.getLong(0),it.getDouble(1),it.getInt(2))},motion=array(j,"motion"){Motion(it.getLong(0),it.getDouble(1),it.getDouble(2),if(it.isNull(3))null else it.getDouble(3))},
        pauses=array(j,"pauses"){PauseSpan(it.getLong(0),it.getLong(1))},rpe=if(j.has("rpe"))j.getInt("rpe")else null,note=j.optString("note"),exportStatus=j.optString("export","Noch nicht geteilt"),revision=j.optLong("revision",1),exportedRevision=j.optLong("exportedRevision",0),recovered=j.optBoolean("recovered"), phases=array(j,"phases"){Phase(it.getString(0),it.getInt(1),it.getBoolean(2))},targetPace=j.optInt("targetPace",345),coachReason=j.optString("reason"),readiness=j.optString("readiness","READY")
    )
}
class Vault(context:Context) {
    private val directory = File(context.noBackupFilesDir,"vault").apply{mkdirs()}
    private val key: SecretKey by lazy {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("laufklar-v1",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder("laufklar-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun names()=directory.listFiles()?.filter{it.extension=="vault"}?.map{it.nameWithoutExtension}?:emptyList()
    fun read(name:String):JSONObject? {
        val f=AtomicFile(File(directory,"$name.vault")); if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists()) return null
        val bytes=f.readFully(); require(bytes.size>13)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    fun write(name:String,json:JSONObject){
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key)
        val bytes=cipher.iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val file=AtomicFile(File(directory,"$name.vault")); val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun delete(name:String)=AtomicFile(File(directory,"$name.vault")).delete()
}
class Repository(context:Context) {
    private val vault=Vault(context)
    private val mutex=Mutex()
    val profile=MutableStateFlow(Profile())
    val runs=MutableStateFlow<List<Run>>(emptyList())
    val ready=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    init{scope.launch{withContext(Dispatchers.IO){
        mutex.withLock{
            try{
                profile.value=vault.read("profile")?.let(Codec::profile)?:Profile()
                val loaded=vault.names().filter{it.startsWith("run-")}.mapNotNull{name->
                    try{vault.read(name)?.let(Codec::run)}catch(e:Exception){error.value="Ein Lauf konnte nicht gelesen werden. Originaldatei bleibt erhalten.";null}
                }.toMutableList()
                vault.read("active")?.let{j->
                    val r=Codec.run(j).copy(ended=j.getLong("checkpoint"), recovered=true, note="Aufzeichnung unterbrochen. Bis zum letzten Sicherungspunkt gerettet.")
                    if(loaded.none{it.id==r.id}){vault.write("run-${r.id}",Codec.run(r));loaded.add(r)}
                    vault.delete("active")
                }
                runs.value=loaded.sortedByDescending{it.started}
            }catch(e:Exception){error.value="Lokaler Speicher konnte nicht geöffnet werden. Bitte App-Daten nicht löschen."}
            ready.value=true
        }
    }}}
    suspend fun setProfile(p:Profile)=withContext(Dispatchers.IO){mutex.withLock{require(p.validate()==null);vault.write("profile",Codec.profile(p));profile.value=p}}
    suspend fun checkpoint(run:Run, now:Long)=withContext(Dispatchers.IO){mutex.withLock{vault.write("active",Codec.run(run).put("checkpoint",now))}}
    suspend fun save(run:Run, finish:Boolean=false)=withContext(Dispatchers.IO){mutex.withLock{
        vault.write("run-${run.id}",Codec.run(run));if(finish)vault.delete("active")
        runs.value=(runs.value.filterNot{it.id==run.id}+run).sortedByDescending{it.started}
    }}
    suspend fun delete(run:Run)=withContext(Dispatchers.IO){mutex.withLock{vault.delete("run-${run.id}");runs.value=runs.value.filterNot{it.id==run.id}}}
    suspend fun discardCheckpoint(runId:String)=withContext(Dispatchers.IO){mutex.withLock{
        if(vault.read("active")?.optString("id")==runId)vault.delete("active")
    }}
}
