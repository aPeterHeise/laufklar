package de.laufklar.app

import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadinessAndPauseTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready(){compose.waitUntil(10_000){compose.activity.repo().ready.value}}
    private fun shell(command:String){ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun command(action:String){compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(action))}}
    private fun start(mode:RunMode,readiness:Readiness=Readiness.READY){
        shell("pm grant de.laufklar.app android.permission.POST_NOTIFICATIONS")
        compose.runOnUiThread{compose.activity.startForegroundService(Intent(compose.activity,RunService::class.java)
            .setAction(RunService.START).putExtra("mode",mode.name).putExtra("readiness",readiness.name))}
        compose.waitUntil(10_000){Live.run.value!=null}
    }
    private fun cleanup(){
        if(Live.run.value!=null){command(RunService.DISCARD);compose.waitUntil(10_000){Live.run.value==null}}
        compose.waitUntil(10_000){RunService.instance==null}
    }

    @Test fun healthNoteIsBelowMainChoicesAndDoesNotDisableStart(){
        ready();var selected:Readiness?=null
        compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{Home(Profile(),emptyList(),{_,_,r->selected=r},{},{})}}}
        compose.onNodeWithText("Krank / Beschwerden").assertDoesNotExist()
        compose.onNodeWithText("Gesundheitlich angeschlagen").performScrollTo()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Bei Krankheit oder Beschwerden solltest du nicht laufen.",substring=true).assertExists()
        compose.onNodeWithText("Draußen starten").assertIsEnabled().performClick()
        assertEquals(Readiness.UNWELL,selected)
    }

    @Test fun serviceRecordsUnwellRunWithReadinessPreserved(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value
        try{
            runBlocking{repo.setProfile(original.copy(audio=false,healthExport=false))}
            start(RunMode.TREADMILL,Readiness.UNWELL)
            compose.waitUntil(10_000){Live.run.value!!.activeSeconds>=1}
            assertEquals("UNWELL",Live.run.value!!.readiness)
            assertEquals("UNWELL",Codec.run(Codec.run(Live.run.value!!)).readiness)
            assertFalse(Live.paused.value)
        }finally{cleanup();runBlocking{repo.setProfile(original)}}
    }

    @Test fun trafficLightPauseResumesQuicklyAndIgnoresDelayedOrBatchedStops(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value
        fun disableProvider(){compose.runOnUiThread{
            compose.activity.getSystemService(LocationManager::class.java).removeUpdates(RunService.instance!!)
            RunService.instance!!.onProviderDisabled(LocationManager.GPS_PROVIDER)
        }}
        fun fix(speed:Float,ageMs:Long=0)=Location(LocationManager.GPS_PROVIDER).apply{
            latitude=52.0;longitude=13.0;accuracy=4f;this.speed=speed;speedAccuracyMetersPerSecond=.1f
            time=System.currentTimeMillis()-ageMs;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()-ageMs*1_000_000
        }
        fun send(speed:Float,ageMs:Long=0){compose.runOnUiThread{RunService.instance!!.onLocationChanged(fix(speed,ageMs))}}
        try{
            shell("pm grant de.laufklar.app android.permission.ACCESS_COARSE_LOCATION")
            shell("pm grant de.laufklar.app android.permission.ACCESS_FINE_LOCATION")
            runBlocking{repo.setProfile(original.copy(audio=false,healthExport=false,autoPause=true))}
            start(RunMode.OUTDOOR);disableProvider()
            val before=SystemClock.elapsedRealtime()
            send(0f);SystemClock.sleep(1_100);send(0f)
            assertFalse(Live.paused.value)
            SystemClock.sleep(1_100);send(0f)
            assertTrue(Live.autoPaused.value);assertTrue(Live.paused.value)
            assertTrue(SystemClock.elapsedRealtime()-before<4_000)
            send(2f);SystemClock.sleep(1_100);send(2f)
            assertFalse(Live.paused.value);assertFalse(Live.autoPaused.value);disableProvider()
            assertEquals(1,Live.run.value!!.pauses.size)

            // A later delivery of old stopped samples cannot pause the running user.
            repeat(3){SystemClock.sleep(1_100);send(0f,2_000);assertFalse(Live.paused.value)}
            send(0f);SystemClock.sleep(1_100);send(0f);SystemClock.sleep(1_200)
            compose.runOnUiThread{RunService.instance!!.onLocationChanged(listOf(fix(0f,50),fix(2f)))}
            assertFalse(Live.paused.value)

            command(RunService.PAUSE);compose.waitUntil(10_000){Live.paused.value}
            assertFalse(Live.autoPaused.value)
            send(2f);SystemClock.sleep(1_100);send(2f)
            assertTrue(Live.paused.value);assertFalse(Live.autoPaused.value)
        }finally{cleanup();runBlocking{repo.setProfile(original)}}
    }
}
