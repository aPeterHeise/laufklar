package de.laufklar.app

import android.content.Intent
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

class CoachExperienceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun ready(){compose.waitUntil(10_000){compose.activity.repo().ready.value}}
    private fun command(action:String){compose.runOnUiThread{compose.activity.startService(Intent(compose.activity,RunService::class.java).setAction(action))}}

    @Test fun normalRoundStaysOpenAfterThirtyMinutes(){
        ready()
        val plan=AdaptiveTrainer.plan(Profile(),emptyList())
        val run=Run(started=System.currentTimeMillis()-1800_000,mode=RunMode.TREADMILL,workout=Workout.EASY,
            activeSeconds=1800.0,meters=4000.0,zoneBounds=Profile().bounds,phases=plan.phases(Workout.EASY),openEnded=true)
        compose.activityRule.scenario.onActivity{it.setContent{MaterialTheme{ActiveScreen(run,Profile()){}}}}
        compose.onNodeWithText("Offen").assertIsDisplayed()
        compose.onNodeWithText("DEINE RUNDE").assertIsDisplayed()
        compose.onNodeWithText("TRAINING ABGESCHLOSSEN").assertDoesNotExist()
    }

    @Test fun cooldownKeepsSensorOwnershipAndPersistsWithoutExtendingRun(){
        ready();val repo=compose.activity.repo();val original=repo.profile.value;val originalBpm=Live.bpm.value
        var runId:String?=null
        try{
            runBlocking{repo.setProfile(Profile(audio=false,healthExport=false,restingHr=60))}
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant de.laufklar.app android.permission.POST_NOTIFICATIONS").close()
            compose.runOnUiThread{compose.activity.startForegroundService(Intent(compose.activity,RunService::class.java).setAction(RunService.START).putExtra("mode",RunMode.TREADMILL.name))}
            compose.waitUntil(10_000){Live.run.value!=null}
            runId=Live.run.value!!.id
            compose.waitUntil(10_000){Live.run.value!!.activeSeconds>=1}
            compose.runOnUiThread{Live.bpm.value=160 to SystemClock.elapsedRealtime()}
            command(RunService.FINISH)
            compose.waitUntil(10_000){Live.cooldownSeconds.value!=null}
            val finished=Live.run.value!!
            assertNotNull(finished.ended);assertTrue(repo.runs.value.any{it.id==runId})
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000){Live.cooldownSeconds.value!=null}
            compose.onNodeWithText("Dein Cooldown.").assertIsDisplayed()
            val until=SystemClock.elapsedRealtime()+65_000
            while(Live.cooldownSeconds.value!=null&&SystemClock.elapsedRealtime()<until){
                compose.runOnUiThread{Live.bpm.value=120 to SystemClock.elapsedRealtime();RunService.instance?.pulse(120)}
                SystemClock.sleep(1_000)
            }
            compose.waitUntil(10_000){Live.run.value==null}
            val saved=repo.runs.value.single{it.id==runId}
            assertEquals(finished.ended,saved.ended);assertEquals(finished.activeSeconds,saved.activeSeconds,.01)
            assertEquals(CooldownState.COMPLETE,saved.cooldown!!.state);assertEquals(40,saved.cooldown.score)
            assertTrue(saved.pulses.none{it.time>saved.ended!!})
            assertTrue(saved.cooldown.pulses.size>=50)
            assertEquals(saved,Codec.run(Codec.run(saved)))
        }finally{
            if(Live.cooldownSeconds.value!=null){command(RunService.STOP_COOLDOWN);compose.waitUntil(10_000){Live.run.value==null}}
            if(Live.run.value!=null){command(RunService.DISCARD);compose.waitUntil(10_000){Live.run.value==null}}
            runBlocking{repo.runs.value.find{it.id==runId}?.let{repo.delete(it)};repo.setProfile(original)}
            Live.bpm.value=originalBpm
        }
    }
}
