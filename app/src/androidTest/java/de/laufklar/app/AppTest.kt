package de.laufklar.app

import android.content.Intent
import android.os.SystemClock
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
