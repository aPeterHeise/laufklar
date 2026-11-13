package de.laufklar.app

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.osmdroid.views.MapView
import java.time.Instant
import java.io.File
import kotlin.math.abs

class TrackingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready(){compose.waitUntil(10000){compose.activity.repo().ready.value}}
    private fun shell(command:String){ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun command(action:String){compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(action))}}
    private fun start():Run{
        shell("pm grant de.laufklar.app android.permission.POST_NOTIFICATIONS")
        compose.runOnUiThread{compose.activity.startForegroundService(Intent(compose.activity,RunService::class.java).setAction(RunService.START).putExtra("mode",RunMode.TREADMILL.name))}
        compose.waitUntil(10000){Live.run.value!=null}
        assertEquals(0.0,Live.beltSpeed.value,.001)
        compose.runOnUiThread{RunService.instance!!.speed(10.0)}
        compose.waitUntil(10000){(Live.run.value?.meters?:0.0)>5}
        return Live.run.value!!
    }
    private fun finishCleanup(){
        if(Live.run.value!=null){command(RunService.DISCARD);compose.waitUntil(10000){Live.run.value==null}}
        compose.waitUntil(10000){RunService.instance==null}
    }
    private fun notifications()=compose.activity.getSystemService(NotificationManager::class.java)
    private fun range(run:Run)=TimeRangeFilter.between(Instant.ofEpochMilli(run.started-1000),Instant.now().plusSeconds(1))
    private fun grantHealth(){HealthExporter.basePermissions.forEach{shell("pm grant de.laufklar.app $it")}}
    private fun screenshot(name:String){
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->
            File(compose.activity.externalCacheDir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    private fun map(view:View):MapView?=if(view is MapView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{map(view.getChildAt(it))}else null

    @Test fun liveMapFollowsNewPositionsAndCanBeRecentered(){
        ready()
        val first=TrackPoint(1000,1.0,52.52,13.405,4.0,0)
        val points=mutableStateOf(listOf(first))
        compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{RouteMap(points.value,false,Modifier.fillMaxSize(),followPosition=true)}}}
        compose.waitUntil(10000){map(compose.activity.window.decorView)!=null}
        val view=map(compose.activity.window.decorView)!!
        fun at(p:TrackPoint)=abs(view.mapCenter.latitude-p.lat)<.00005&&abs(view.mapCenter.longitude-p.lon)<.00005
        compose.waitUntil(10000){at(first)}
        val zoom=view.zoomLevelDouble
        val second=first.copy(time=2000,lat=52.525,lon=13.415)
        compose.runOnUiThread{points.value=points.value+second}
        compose.waitUntil(10000){at(second)}
        assertEquals(zoom,view.zoomLevelDouble,.001)
        // Dragging lets the runner inspect the route without the next GPS fix snapping back.
        compose.onRoot().performTouchInput{swipe(center,center+Offset(160f,0f),400)}
        compose.onNodeWithContentDescription("Position folgen").assertExists()
        SystemClock.sleep(700)
        val manualLat=view.mapCenter.latitude;val manualLon=view.mapCenter.longitude
        val third=first.copy(time=3000,lat=52.53,lon=13.425)
        compose.runOnUiThread{points.value=points.value+third}
        compose.waitForIdle();SystemClock.sleep(700)
        assertEquals(manualLat,view.mapCenter.latitude,.00001);assertEquals(manualLon,view.mapCenter.longitude,.00001)
        compose.onNodeWithContentDescription("Position folgen").performClick()
        compose.waitUntil(10000){at(third)}
        assertEquals(zoom,view.zoomLevelDouble,.001)
    }

    @Test fun reviewCanResumeOrDiscardWithoutRecoveryHealthExportOrNotification(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value
        grantHealth()
        try{
            runBlocking{repo.setProfile(original.copy(audio=false,healthExport=true))}
            val run=start()
            compose.waitUntil(10000){notifications().activeNotifications.any{it.id==71}}
            val n=notifications().activeNotifications.single{it.id==71}.notification
            assertEquals(R.drawable.ic_stat_running,n.smallIcon.resId)
            val channel=notifications().getNotificationChannel(n.channelId)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT,channel.importance);assertNull(channel.sound)
            screenshot("tracking-active")
            compose.onNodeWithText("Beenden").performScrollTo().performClick()
            compose.waitUntil(10000){Live.reviewing.value&&Live.paused.value}
            screenshot("tracking-review")
            val paused=Live.run.value!!
            SystemClock.sleep(1200)
            assertEquals(paused.meters,Live.run.value!!.meters,.001)
            assertEquals(paused.activeSeconds,Live.run.value!!.activeSeconds,.001)
            compose.onNodeWithText("Zurück zum Lauf").performClick()
            compose.waitUntil(10000){!Live.paused.value&&!Live.reviewing.value}
            command(RunService.PAUSE);compose.waitUntil(10000){Live.paused.value}
            command(RunService.REVIEW);compose.onNodeWithText("Zurück zum Lauf").performClick()
            compose.waitUntil(10000){!Live.reviewing.value};assertTrue(Live.paused.value)
            command(RunService.REVIEW)
            compose.onNodeWithText("Enddistanz am Laufband · km").performTextInput("2,5")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("2,5").assertExists()
            assertTrue(notifications().activeNotifications.any{it.id==71})
            compose.onNodeWithText("Nicht speichern").performClick()
            compose.waitUntil(10000){Live.run.value==null&&RunService.instance==null}
            assertTrue(repo.runs.value.none{it.id==run.id})
            assertNull(Vault(compose.activity).read("active"))
            val reopened=Repository(compose.activity)
            compose.waitUntil(10000){reopened.ready.value}
            assertTrue(reopened.runs.value.none{it.id==run.id});reopened.scope.cancel()
            assertTrue(notifications().activeNotifications.none{it.id==71})
            screenshot("tracking-finished")
            runBlocking{
                val sessions=HealthConnectClient.getOrCreate(compose.activity).readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,range(run),dataOriginFilter=setOf(DataOrigin(compose.activity.packageName)))).records
                assertTrue(sessions.none{it.metadata.clientRecordId=="laufklar:${run.id}:session"})
            }
        }finally{finishCleanup();runBlocking{repo.setProfile(original)}}
    }

    @Test fun treadmillCorrectionIsSavedBeforeFirstHealthConnectExport(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value
        var testRun:Run?=null
        grantHealth()
        try{
            runBlocking{repo.setProfile(original.copy(audio=false,healthExport=true))}
            val run=start();testRun=run
            compose.onNodeWithText("Beenden").performScrollTo().performClick()
            compose.waitUntil(10000){Live.reviewing.value}
            val reviewTime=System.currentTimeMillis()
            compose.onNodeWithText("Enddistanz am Laufband · km").performTextReplacement("0")
            compose.onNodeWithText("Lauf speichern").assertIsNotEnabled()
            compose.onNodeWithText("Enddistanz am Laufband · km").performTextReplacement("1,23")
            SystemClock.sleep(1200)
            compose.onNodeWithText("Lauf speichern").performClick()
            compose.waitUntil(15000){Live.run.value==null}
            val saved=repo.runs.value.single{it.id==run.id}
            assertEquals(1230.0,saved.distance,.001)
            assertTrue(saved.ended!!<=reviewTime+100)
            assertEquals("In Health Connect gespeichert",saved.exportStatus)
            runBlocking{
                val client=HealthConnectClient.getOrCreate(compose.activity)
                val distances=client.readRecords(ReadRecordsRequest(DistanceRecord::class,range(run),dataOriginFilter=setOf(DataOrigin(compose.activity.packageName)))).records.filter{it.metadata.clientRecordId=="laufklar:${run.id}:distance"}
                assertEquals(1,distances.size);assertEquals(1230.0,distances.single().distance.inMeters,.001)
                val sessions=client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,range(run),dataOriginFilter=setOf(DataOrigin(compose.activity.packageName)))).records.filter{it.metadata.clientRecordId=="laufklar:${run.id}:session"}
                assertEquals(1,sessions.size)
            }
            compose.waitUntil(10000){notifications().activeNotifications.none{it.id==71}}
        }finally{
            finishCleanup()
            runBlocking{
                testRun?.let{r->
                    val client=HealthConnectClient.getOrCreate(compose.activity)
                    client.deleteRecords(ExerciseSessionRecord::class,emptyList(),listOf("laufklar:${r.id}:session"))
                    client.deleteRecords(DistanceRecord::class,emptyList(),listOf("laufklar:${r.id}:distance"))
                    client.deleteRecords(SpeedRecord::class,emptyList(),listOf("laufklar:${r.id}:speed"))
                    repo.delete(r)
                };repo.setProfile(original)
            }
        }
    }

    @Test fun treadmillEstimateCanBeAcceptedAndNextRunStartsWithNoSpeed(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value
        var saved:Run?=null
        try{
            runBlocking{repo.setProfile(original.copy(audio=false,healthExport=false))}
            val run=start()
            command(RunService.REVIEW);compose.waitUntil(10000){Live.reviewing.value}
            val estimate=Live.run.value!!.meters
            compose.onNodeWithText("Schätzung speichern").performClick()
            compose.waitUntil(10000){Live.run.value==null&&RunService.instance==null}
            saved=repo.runs.value.single{it.id==run.id}
            assertNull(saved.correctedMeters);assertEquals(estimate,saved.distance,.001)
            start() // Includes zero-speed assertion before setting the new speed.
        }finally{finishCleanup();runBlocking{saved?.let{repo.delete(it)};repo.setProfile(original)}}
    }
}
