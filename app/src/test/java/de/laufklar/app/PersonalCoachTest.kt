package de.laufklar.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PersonalCoachTest {
    private val profile = Profile()
    private fun trace(seconds: Int, hr: (Int) -> Int = {145}, pace: Double = 400.0, start: Long = 1_000_000) = Run(
        started=start,ended=start+seconds*1000L,mode=RunMode.OUTDOOR,workout=Workout.EASY,zoneBounds=profile.bounds,
        activeSeconds=seconds.toDouble(),meters=seconds*1000/pace,openEnded=true,phases=listOf(Phase("Ankommen",300),Phase("Deine Runde",0)),
        pulses=(0..seconds).map{Pulse(start+it*1000L,it.toDouble(),hr(it))},
        motion=(0..seconds).map{Motion(start+it*1000L,it.toDouble(),it*1000/pace,1000/pace)}
    )
    private fun until(full: Run, seconds: Int): Run = full.copy(ended=null,activeSeconds=seconds.toDouble(),meters=full.motion.last{it.elapsed<=seconds}.meters,
        pulses=full.pulses.takeWhile{it.elapsed<=seconds},motion=full.motion.takeWhile{it.elapsed<=seconds},points=full.points.takeWhile{it.elapsed<=seconds})
    private fun replay(full: Run, coach: Coach = Coach(), p: Profile = profile): Coach {
        for(second in 0..full.activeSeconds.toInt()) coach.update(until(full,second),p,full.started+second*1000L)
        return coach
    }
    private fun square(start: Long, pace: Double = 420.0, bpm: Int = 145, length: Int = 4000): Run {
        fun xy(meters: Int): Pair<Double,Double> = when {
            meters<=1000 -> meters.toDouble() to 0.0
            meters<=2000 -> 1000.0 to (meters-1000.0)
            meters<=3000 -> (3000.0-meters) to 1000.0
            else -> 0.0 to (4000.0-meters)
        }
        val points=(0..length step 10).map{m->val(x,y)=xy(m);val elapsed=m*pace/1000;TrackPoint(start+(elapsed*1000).toLong(),elapsed,52+y/111195,13+x/(111195*cos(Math.toRadians(52.0))),5.0,0)}
        return trace((length*pace/1000).toInt(),{bpm},pace,start).copy(points=points)
    }

    @Test fun ageEstimateDoesNotNagThroughoutComfortableNormalRun() {
        val coach=replay(trace(1800))
        assertTrue(coach.events.none{it.kind==CueKind.EFFORT})
        assertTrue(coach.events.filter{it.kind==CueKind.DISTANCE}.none{it.text.contains("langsamer")||it.text.contains("sprechen")})
        assertTrue(coach.events.none{it.text.contains("Trainingsteil abgeschlossen")||it.text.contains("auslaufen")})
        assertEquals(1,Training.phaseAt(listOf(Phase("Ankommen",300),Phase("Runde",0)),10_000.0))
        assertTrue(coach.events.count{it.kind==CueKind.PROGRESS}<=2)
    }
    @Test fun sustainedHighPulseGetsTwoConcreteExplanationsInsteadOfEveryMinute() {
        val coach=replay(trace(1800,{170}))
        val warnings=coach.events.filter{it.kind==CueKind.EFFORT}
        assertEquals(2,warnings.size)
        assertTrue(warnings[0].text.contains("162"))
        assertTrue(warnings[0].text.contains("geschätzt"))
        assertTrue(warnings[1].elapsed-warnings[0].elapsed>=180)
        assertTrue(coach.events.none{it.kind==CueKind.PROGRESS})
    }
    @Test fun furtherRiseStillGetsAttentionAfterBudgetAndNeverPraiseForHighPulse() {
        val coach=replay(trace(1500,{if(it<1000)165 else 176}))
        assertTrue(coach.events.any{it.kind==CueKind.EFFORT&&it.elapsed>1000&&it.text.contains("steigt weiter")})
        assertTrue(coach.events.none{it.kind==CueKind.PROGRESS})
    }
    @Test fun actualRecoveryIsAcknowledgedAndTaskChanges() {
        val full=trace(1400,{if(it<600)168 else 148})
        val coach=replay(full)
        assertEquals(1,coach.events.count{it.kind==CueKind.RECOVERY})
        assertTrue(coach.events.first{it.kind==CueKind.RECOVERY}.elapsed>=620)
        val saved=coach.finish(full)
        assertTrue(saved.coachMission.contains("Tempo herausnehmen"))
        assertTrue(saved.coachOutcome.contains("ruhigeren Rhythmus"))
    }
    @Test fun measuredBoundsRemainActionableInsteadOfBeingSilentlyRaised() {
        val p=profile.copy(customBounds=listOf(90,110,135,155,175,195))
        val coach=replay(trace(500,{150}),p=p)
        assertTrue(coach.events.first{it.kind==CueKind.EFFORT}.text.contains("unter 135"))
    }
    @Test fun isolatedPulseSpikeDoesNotTriggerAnIntervention() {
        val coach=replay(trace(900,{if(it in 500..510)180 else 145}))
        assertTrue(coach.events.none{it.kind==CueKind.EFFORT})
    }
    @Test fun intervalTransitionDoesNotOverrideAnActiveReliefInstruction() {
        val full=trace(500,{170}).copy(workout=Workout.INTERVALS,openEnded=false,phases=listOf(Phase("Ankommen",300),Phase("Tempo",120,true),Phase("Erholung",120)))
        val coach=replay(full)
        val transition=coach.events.first{it.kind==CueKind.PHASE&&it.elapsed in 300.0..419.0}
        assertTrue(transition.text.contains("ruhigen Tempo"));assertFalse(transition.text.contains("zügig"))
    }
    @Test fun missingPulseIsExplainedOnceAndDoesNotEarnFitnessPraise() {
        val coach=replay(trace(1500).copy(pulses=emptyList()))
        assertEquals(1,coach.events.count{it.text.contains("Kein frischer Live-Puls")})
        assertTrue(coach.events.none{it.kind==CueKind.PROGRESS})
    }
    @Test fun normalAndLongRunsRemainOpenAndComparisonIsNotASeparateMode() {
        val p=AdaptiveTrainer.plan(profile,emptyList())
        assertFalse(p.available().contains(Workout.BASELINE))
        assertTrue(p.openEnded(Workout.EASY));assertTrue(p.openEnded(Workout.LONG))
        assertEquals("Offen",p.durationLabel(Workout.EASY))
        val full=trace(2200).copy(workout=Workout.LONG,plannedSeconds=1800)
        assertEquals(1,replay(full).events.count{it.text.contains("vorgeschlagener Umfang")})
    }
    @Test fun positiveRouteComparisonNeedsSamePathDirectionAndPulseCoverage() {
        val old=square(1_000_000)
        val current=square(3_000_000,400.0,143,3000)
        val match=RouteMatcher(listOf(old)).match(current)!!
        assertNotNull(match.comparison);assertEquals(old.id,match.reference.id)
        assertEquals(1000.0,match.remaining,20.0)
        assertNull(RouteMatcher(listOf(old)).match(current.copy(pulses=emptyList()))!!.comparison)
        assertNull(RouteMatcher(listOf(old)).match(current.copy(pulses=current.pulses.map{it.copy(bpm=160)}))!!.comparison)
    }
    @Test fun reverseAndDifferentRoutesAreNotClaimedAsComparable() {
        val old=square(1_000_000)
        val current=square(3_000_000,400.0,143,3000)
        assertNull(RouteMatcher(listOf(old)).match(current.copy(points=current.points.map{it.copy(lat=it.lat+.002)})))
        val reversePoints=old.points.reversed().mapIndexed{i,p->p.copy(time=3_000_000+i*4000L,elapsed=i*4.0)}
        assertNull(RouteMatcher(listOf(old)).match(current.copy(points=reversePoints.take(301))))
    }
    @Test fun differentReadinessKeepsRouteRecognitionWithoutPerformanceComparison() {
        val old=square(1_000_000)
        val current=square(3_000_000,400.0,143,3000)
        for(readiness in listOf("TIRED","UNWELL","UNKNOWN")) {
            assertNull(RouteMatcher(listOf(old)).match(current.copy(readiness=readiness))!!.comparison)
            assertNull(RouteMatcher(listOf(old.copy(readiness=readiness))).match(current)!!.comparison)
        }
        assertNotNull(RouteMatcher(listOf(old)).match(current)!!.comparison)
    }
    @Test fun matchingReadinessIsPreferredOverANewerIncompatibleReference() {
        val rested=square(1_000_000)
        val tired=square(2_000_000).copy(readiness="TIRED")
        val current=square(4_000_000,400.0,143,3000)
        val match=RouteMatcher(listOf(tired,rested)).match(current)!!
        assertEquals(rested.id,match.reference.id);assertNotNull(match.comparison)
    }
    @Test fun pausesSuppressPerformanceClaimsButDoNotEraseRouteRecognition() {
        val old=square(1_000_000)
        val current=square(3_000_000,400.0,143,3000)
        val paused=current.copy(pauses=listOf(PauseSpan(current.ended!!-120_000,current.ended-100_000)))
        assertNull(RouteMatcher(listOf(old)).match(paused)!!.comparison)
    }
    @Test fun nearStartIsNotNearFinishAndRemainingFollowsRecordedRoute() {
        val old=square(1_000_000)
        assertNull(RouteMatcher(listOf(old)).match(square(3_000_000,length=100)))
        val late=square(3_000_000,length=3500)
        assertEquals(500.0,RouteMatcher(listOf(old)).match(late)!!.remaining,20.0)
    }
    @Test fun pausedOrMissingDistanceSamplesDoNotCompleteSteadyTask() {
        val full=trace(1200)
        val run=full.copy(pauses=listOf(PauseSpan(full.started+900_000,full.started+910_000)))
        assertNull(distanceWindow(run,2000.0,2500.0))
        assertNotNull(distanceWindow(full.copy(pauses=listOf(PauseSpan(full.started+1_000_000,full.started+1_010_000))),2000.0,2500.0))
        assertNull(distanceWindow(full.copy(motion=full.motion.filter{it.elapsed !in 900.0..1000.0}),2000.0,2500.0))
    }
    @Test fun coachHistoryAndExperiencePersistAndLegacyRunsStayReadable() {
        val full=trace(1200)
        val run=replay(full).finish(full)
        assertEquals(run,Codec.run(Codec.run(run)))
        val p=profile.copy(distanceExperienceKm=10.0)
        assertEquals(p,Codec.profile(Codec.profile(p)))
        val legacy=Codec.run(full).apply{remove("openEnded");remove("coachEvents");remove("coachMission")}
        assertFalse(Codec.run(legacy).openEnded);assertTrue(Codec.run(legacy).coachEvents.isEmpty())
        assertTrue(AdaptiveTrainer.plan(p,emptyList()).reason.contains("Zieldistanz bereits"))
    }
}
