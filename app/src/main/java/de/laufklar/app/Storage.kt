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
                    try{vault.read(name)?.let(Codec::run)?.let { run ->
                        val cooldown=run.cooldown
                        if(cooldown?.state==CooldownState.MEASURING){
                            val recovered=run.copy(cooldown=cooldown.copy(state=CooldownState.INTERRUPTED,ended=cooldown.pulses.lastOrNull()?.time?:cooldown.started),revision=run.revision+1)
                            vault.write(name,Codec.run(recovered));recovered
                        }else run
                    }}catch(e:Exception){error.value="Ein Lauf konnte nicht gelesen werden. Originaldatei bleibt erhalten.";null}
                }.toMutableList()
                vault.read("active")?.let{j->
                    val r=Codec.run(j).copy(ended=j.getLong("checkpoint"), recovered=true, note="Aufzeichnung unterbrochen. Bis zum letzten Sicherungspunkt gerettet.")
                    if(loaded.none{it.id==r.id}){vault.write("run-${r.id}",Codec.run(r));loaded.add(r)}
                    vault.delete("active")
                }
                runs.value=loaded.map { run ->
                    if (run.ended == null || run.summary != null || run.sessionPlan == null) return@map run
                    val interrupted = run.copy(missionAttempts = run.missionAttempts.map { attempt ->
                        if (attempt.state.terminal) attempt else attempt.copy(
                            state = if (attempt.startedAt == null) MissionState.ABORTED else MissionState.PARTIAL,
                            endedAt = run.activeSeconds, endedMeters = run.meters,
                            result = "Die Aufzeichnung endete vor der vollständigen Auswertung.")
                    })
                    val finished = interrupted.copy(summary = SummaryBuilder.build(interrupted, loaded))
                    vault.write("run-${run.id}",Codec.run(finished))
                    finished
                }.sortedByDescending{it.started}
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
    suspend fun markSummaryPlayed(runId: String, summaryId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = runs.value.find { it.id == runId } ?: return@withLock
            val summary = current.summary?.takeIf { it.id == summaryId } ?: return@withLock
            val updated = current.copy(summary = summary.copy(speech = SummarySpeech.PLAYED))
            vault.write("run-$runId", Codec.run(updated))
            runs.value = runs.value.map { if (it.id == runId) updated else it }
        }
    }
    suspend fun delete(run:Run)=withContext(Dispatchers.IO){mutex.withLock{vault.delete("run-${run.id}");runs.value=runs.value.filterNot{it.id==run.id}}}
    suspend fun exportResult(id:String,revision:Long,status:String,success:Boolean)=withContext(Dispatchers.IO){mutex.withLock{
        val current=runs.value.find{it.id==id}?:return@withLock
        val updated=current.copy(exportStatus=if(current.revision==revision)status else "Änderungen noch nicht geteilt",
            exportedRevision=if(success)maxOf(current.exportedRevision,revision)else current.exportedRevision)
        vault.write("run-$id",Codec.run(updated));runs.value=runs.value.map{if(it.id==id)updated else it}
    }}
    suspend fun discardCheckpoint(runId:String)=withContext(Dispatchers.IO){mutex.withLock{
        if(vault.read("active")?.optString("id")==runId)vault.delete("active")
    }}
}
