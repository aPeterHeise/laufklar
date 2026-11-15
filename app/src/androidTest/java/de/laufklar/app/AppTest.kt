package de.laufklar.app

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun awaitReady(){compose.waitUntil(10000){compose.activity.repo().ready.value}}
    @Test fun profileAcceptsMinutePaceUnknownPaceAndSexSelection(){
        awaitReady();val repo=compose.activity.repo();val original=repo.profile.value
        try{
            runBlocking{repo.setProfile(Profile())}
            compose.onNodeWithText("Profil",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Bisherige Pace · min/km (z. B. 5:30)").performScrollTo().performTextReplacement("5:30")
            compose.onNodeWithText("Männlich").performScrollTo().performClick()
            compose.onNodeWithText("Weiblich").performClick()
            compose.onNodeWithText("Laufziel entdecken").performScrollTo().performClick()
            compose.onNodeWithText("Als dauerhaftes Laufziel übernehmen").performClick()
            compose.waitUntil(10000){repo.profile.value.sex=="Weiblich"&&repo.profile.value.goalPace==360}
            assertEquals(330,repo.profile.value.usualPace)
            compose.onNodeWithText("Verstanden").performClick()
            compose.onNodeWithText("Weiß ich noch nicht").performScrollTo().performClick()
            compose.onNodeWithText("Profil speichern").performScrollTo().performClick()
            compose.waitUntil(10000){repo.profile.value.usualPace==null}
        }finally{runBlocking{repo.setProfile(original)}}
    }
    @Test fun freshPulseDoesNotBlinkBetweenHomeUiTicks(){
        awaitReady();val before=Live.bpm.value;val wasConnected=Live.sensor.connected.value
        try{
            compose.mainClock.autoAdvance=false
            compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{SensorCard{}}}}
            compose.mainClock.advanceTimeByFrame()
            repeat(4){index->
                SystemClock.sleep(40)
                compose.runOnUiThread{Live.sensor.connected.value=true;Live.bpm.value=(73+index) to SystemClock.elapsedRealtime()}
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText((73+index).toString()).assertIsDisplayed()
                compose.onNodeWithContentDescription("Live-Puls aktiv").assertIsDisplayed()
            }
            compose.runOnUiThread{Live.bpm.value=76 to SystemClock.elapsedRealtime()-11_000}
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Kein aktueller Live-Puls").assertIsDisplayed()
            compose.onNodeWithText("—").assertIsDisplayed()
        }finally{Live.bpm.value=before;Live.sensor.connected.value=wasConnected;compose.mainClock.autoAdvance=true}
    }
    @Test fun savedSensorIsSelectableWithoutAdvertising(){
        awaitReady();val before=Live.sensor.savedDevice.value;val devicesBefore=Live.sensor.devices.value
        val saved=SensorDevice("00:11:22:33:44:55","Gespeichertes Fitbit Air",0)
        var selected:SensorDevice?=null
        try{
            compose.runOnUiThread{Live.sensor.savedDevice.value=saved;Live.sensor.devices.value=emptyList()}
            compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{SensorDialog(onDismiss={},onSelect={selected=it})}}}
            compose.onNodeWithText("Gespeichert: Gespeichertes Fitbit Air").assertIsDisplayed()
            compose.onNodeWithText("Gespeicherten Sensor verbinden").performScrollTo().performClick()
            assertEquals(saved,selected)
        }finally{Live.sensor.savedDevice.value=before;Live.sensor.devices.value=devicesBefore}
    }
    @Test fun freshPulseDoesNotBlinkBetweenActiveRunUiTicks(){
        awaitReady()
        val state=mutableStateOf(Run(started=System.currentTimeMillis()-60_000,mode=RunMode.TREADMILL,workout=Workout.EASY,activeSeconds=60.0,zoneBounds=Profile().bounds))
        try{
            compose.mainClock.autoAdvance=false
            compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{ActiveScreen(state.value,Profile()){}}}}
            compose.mainClock.advanceTimeByFrame()
            repeat(4){index->
                SystemClock.sleep(40);val time=System.currentTimeMillis();val hr=76+index
                compose.runOnUiThread{state.value=state.value.copy(pulses=listOf(Pulse(time-2000,58.0,hr),Pulse(time-1000,59.0,hr),Pulse(time,60.0,hr)))}
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText(hr.toString()).assertIsDisplayed()
            }
        }finally{compose.mainClock.autoAdvance=true}
    }
    @Test fun navigationAndPrivacyAreReachable(){
        awaitReady()
        compose.onNodeWithText("Ruhig starten.\nStärker werden.").assertIsDisplayed()
        compose.onNodeWithText("Verlauf",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Deine Läufe.").assertIsDisplayed()
        compose.onNodeWithText("Entwicklung",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Fortschritt,\nder sich aufbaut.").assertIsDisplayed()
        compose.onNodeWithText("Profil",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Dein Profil.").assertIsDisplayed()
        compose.onNodeWithText("Datenschutz & Forschungsgrundlagen").performScrollTo().performClick()
        compose.onNodeWithText("Deine Daten.\nBei dir.").assertIsDisplayed()
    }
    @Test fun researchGoalCanBeAdoptedAndChanged() {
        awaitReady()
        val repo = compose.activity.repo()
        val original = repo.profile.value
        try {
            runBlocking { repo.setProfile(Profile()) }
            compose.onNodeWithText("Profil", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Laufziel entdecken").performScrollTo().performClick()
            compose.onNode(hasText("110 %") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("4:39").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Als dauerhaftes Laufziel übernehmen").performClick()
            compose.waitUntil(10_000) { repo.profile.value.goalPace == 279 }
            assertEquals(10.0, repo.profile.value.goalKm, .001)
        } finally {
            runBlocking { repo.setProfile(original) }
        }
    }
    @Test fun vaultEncryptsAndRoundTripsWithoutPlainText(){
        awaitReady();val context=compose.activity;val vault=Vault(context)
        val marker="sensitive-laufklar-test-83kg"
        val json=org.json.JSONObject().put("note",marker)
        vault.write("test-private",json)
        val file=File(context.noBackupFilesDir,"vault/test-private.vault")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
        assertEquals(marker,vault.read("test-private")!!.getString("note"));vault.delete("test-private");assertNull(vault.read("test-private"))
    }
    @Test fun treadmillServicePausesPersistsAndSurvivesActivityRecreation(){
        awaitReady();val context=compose.activity
        runBlocking{context.repo().setProfile(context.repo().profile.value.copy(audio=false,healthExport=false))}
        instrumentation.uiAutomation.executeShellCommand("pm grant de.laufklar.app android.permission.POST_NOTIFICATIONS").close()
        compose.runOnUiThread{context.startForegroundService(Intent(context,RunService::class.java).setAction(RunService.START).putExtra("mode",RunMode.TREADMILL.name).putExtra("workout",Workout.EASY.name))}
        compose.waitUntil(10000){Live.run.value!=null}
        compose.runOnUiThread{RunService.instance!!.speed(10.0)}
        compose.waitUntil(10000){(Live.run.value?.meters?:0.0)>5}
        val id=Live.run.value!!.id
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000){Live.run.value?.id==id}
        compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(RunService.PAUSE))}
        compose.waitUntil(10000){Live.paused.value}
        val paused=Live.run.value!!
        SystemClock.sleep(1500)
        assertEquals(paused.activeSeconds,Live.run.value!!.activeSeconds,.05)
        assertEquals(paused.meters,Live.run.value!!.meters,.05)
        compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(RunService.RESUME))}
        compose.waitUntil(10000){!Live.paused.value}
        compose.waitUntil(10000){Live.run.value!!.activeSeconds>paused.activeSeconds+1}
        compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(RunService.FINISH))}
        compose.waitUntil(10000){Live.run.value==null}
        val saved=compose.activity.repo().runs.value.first{it.id==id}
        assertTrue(saved.meters>5);assertEquals(1,saved.pauses.size);assertNotNull(saved.ended)
        assertEquals(1,compose.activity.repo().runs.value.count{it.id==id})
        runBlocking{compose.activity.repo().delete(saved)}
    }
    @Test fun healthConnectWritesAndCorrectionsAreIdempotent(){
        awaitReady();val context=compose.activity;val repo=context.repo()
        fun shell(cmd:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}}
        val permissions=HealthExporter.basePermissions+HealthExporter.routePermission
        permissions.forEach{shell("pm grant de.laufklar.app $it")}
        val start=System.currentTimeMillis()-3600000
        val run=Run(started=start,ended=start+600000,mode=RunMode.OUTDOOR,workout=Workout.EASY,activeSeconds=590.0,meters=1000.0,zoneBounds=Profile().bounds,
            points=listOf(TrackPoint(start+1000,1.0,52.0,13.0,4.0,0),TrackPoint(start+3000,3.0,52.0001,13.0001,4.0,0)),
            pulses=listOf(Pulse(start+1000,1.0,110),Pulse(start+2000,2.0,112)),motion=listOf(Motion(start+1000,1.0,0.0,2.5),Motion(start+2000,2.0,2.5,2.5)),pauses=listOf(PauseSpan(start+30000,start+40000)))
        runBlocking{
            val old=repo.profile.value;repo.setProfile(old.copy(shareRoute=true));repo.save(run)
            val exporter=HealthExporter(context)
            assertEquals("In Health Connect gespeichert",exporter.export(run));assertEquals("In Health Connect gespeichert",exporter.export(run))
            val client=androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
            val range=androidx.health.connect.client.time.TimeRangeFilter.between(java.time.Instant.ofEpochMilli(start-1000),java.time.Instant.ofEpochMilli(start+601000))
            val sessions=client.readRecords(androidx.health.connect.client.request.ReadRecordsRequest(androidx.health.connect.client.records.ExerciseSessionRecord::class,range,dataOriginFilter=setOf(androidx.health.connect.client.records.metadata.DataOrigin(context.packageName)))).records.filter{it.metadata.clientRecordId=="laufklar:${run.id}:session"}
            assertEquals(1,sessions.size);assertEquals(1,sessions.single().segments.size)
            assertTrue(sessions.single().exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data)
            val changed=run.copy(correctedMeters=1100.0,revision=2);repo.save(changed);assertEquals("In Health Connect gespeichert",exporter.export(changed))
            val distances=client.readRecords(androidx.health.connect.client.request.ReadRecordsRequest(androidx.health.connect.client.records.DistanceRecord::class,range,dataOriginFilter=setOf(androidx.health.connect.client.records.metadata.DataOrigin(context.packageName)))).records.filter{it.metadata.clientRecordId=="laufklar:${run.id}:distance"}
            assertEquals(1,distances.size);assertEquals(1100.0,distances.single().distance.inMeters,.001)
            client.deleteRecords(androidx.health.connect.client.records.ExerciseSessionRecord::class,emptyList(),listOf("laufklar:${run.id}:session"))
            client.deleteRecords(androidx.health.connect.client.records.DistanceRecord::class,emptyList(),listOf("laufklar:${run.id}:distance"))
            client.deleteRecords(androidx.health.connect.client.records.HeartRateRecord::class,emptyList(),listOf("laufklar:${run.id}:hr"))
            client.deleteRecords(androidx.health.connect.client.records.SpeedRecord::class,emptyList(),listOf("laufklar:${run.id}:speed"))
            repo.delete(changed);repo.setProfile(old)
        }
    }
    @Test fun interruptedCheckpointIsRecoveredExactlyOnce(){
        awaitReady();val context=compose.activity
        val run=Run(started=1000,mode=RunMode.TREADMILL,workout=Workout.EASY,activeSeconds=120.0,meters=300.0,zoneBounds=Profile().bounds)
        runBlocking{context.repo().checkpoint(run,121000)}
        val recoveredRepo=Repository(context)
        compose.waitUntil(10000){recoveredRepo.ready.value}
        val saved=recoveredRepo.runs.value.single{it.id==run.id};assertTrue(saved.recovered);assertEquals(121000L,saved.ended)
        val again=Repository(context);compose.waitUntil(10000){again.ready.value};assertEquals(1,again.runs.value.count{it.id==run.id})
        runBlocking{again.delete(saved)}
    }
}
