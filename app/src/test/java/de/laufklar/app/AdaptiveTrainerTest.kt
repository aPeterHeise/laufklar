package de.laufklar.app
import org.junit.Assert.*
import org.junit.Test

class AdaptiveTrainerTest {
    private val now=1_800_000_000_000L
    private val day=AdaptiveTrainer.DAY
    private val profile=Profile(intensityReady=true)
    private fun easy(days:Int,seconds:Double=1800.0,rpe:Int?=3,mode:RunMode=RunMode.OUTDOOR)=Run(started=now-days*day-seconds.toLong()*1000,ended=now-days*day,mode=mode,workout=Workout.EASY,activeSeconds=seconds,meters=seconds*2.5,zoneBounds=profile.bounds,rpe=rpe)
    @Test fun noHistoryStartsWithLearningNotIntervals(){val p=AdaptiveTrainer.plan(profile,emptyList(),now=now);assertEquals(TrainingFocus.LEARN,p.focus);assertFalse(p.qualityAllowed);assertEquals(30,p.longMinutes)}
    @Test fun unknownPaceDoesNotInventTempoFromTheGoal(){
        val p=AdaptiveTrainer.plan(profile.copy(usualPace=null),listOf(easy(3,rpe=null),easy(8),easy(12)),now=now)
        assertFalse(p.startingPaceKnown);assertFalse(p.qualityAllowed);assertFalse(p.available().contains(Workout.INTERVALS))
    }
    @Test fun threeWellToleratedRunsTeachUnknownStartingPace(){
        val p=AdaptiveTrainer.plan(profile.copy(usualPace=null),listOf(easy(3),easy(8),easy(12)),now=now)
        assertTrue(p.startingPaceKnown);assertTrue(p.qualityAllowed);assertEquals(400,p.targetPace)
    }
    @Test fun unrestedRunsCountAsLoadButDoNotTeachRestedPace(){
        val history=listOf(easy(1),easy(2),easy(3)).map{it.copy(readiness="UNWELL")}
        val p=AdaptiveTrainer.plan(profile.copy(usualPace=null),history,now=now)
        assertFalse(p.startingPaceKnown);assertFalse(p.qualityAllowed);assertTrue(p.restRecommended)
        val mixed=listOf(easy(3),easy(8),easy(12).copy(readiness="TIRED"))
        assertFalse(AdaptiveTrainer.plan(profile.copy(usualPace=null),mixed,now=now).startingPaceKnown)
    }
    @Test fun illnessAndFatigueDoNotEscalateTraining(){val history=listOf(easy(3),easy(8),easy(12));val sick=AdaptiveTrainer.plan(profile,history,readiness=Readiness.UNWELL,now=now);assertTrue(sick.restRecommended);assertFalse(sick.qualityAllowed);assertEquals(20,sick.easyMinutes);val tired=AdaptiveTrainer.plan(profile,history,readiness=Readiness.TIRED,now=now);assertFalse(tired.qualityAllowed)}
    @Test fun longBreakTriggersReturnAndGoalRemains(){val p=AdaptiveTrainer.plan(profile,listOf(easy(30,3600.0)),now=now);assertEquals(TrainingFocus.RETURN,p.focus);assertFalse(p.qualityAllowed);assertEquals(20,p.easyMinutes);assertEquals(300,profile.goalPace)}
    @Test fun returningPersistsForThreeRunsAfterBreak(){val p=AdaptiveTrainer.plan(profile,listOf(easy(3),easy(25),easy(29)),now=now);assertEquals(TrainingFocus.RETURN,p.focus);val q=AdaptiveTrainer.plan(profile,listOf(easy(2),easy(5),easy(8),easy(30)),now=now);assertEquals(TrainingFocus.BUILD,q.focus)}
    @Test fun achievedGoalEntersMaintenanceWithoutAnEndDate(){val goal=easy(4,3000.0,rpe=7).copy(meters=10000.0);val p=AdaptiveTrainer.plan(profile,listOf(goal,easy(10),easy(14)),now=now);assertTrue(p.achieved);assertEquals(TrainingFocus.MAINTAIN,p.focus)}
    @Test fun pausesCannotManufactureGoalAchievement(){val goal=easy(4,3000.0).copy(meters=10000.0,started=now-4*day-4000_000,pauses=listOf(PauseSpan(1,1000)));assertFalse(AdaptiveTrainer.reached(goal,profile))}
    @Test fun customGoalIsEvaluatedInsteadOfHardCodedTenKm(){val p=profile.copy(goalKm=5.0,goalPace=360);val r=easy(3,1800.0).copy(meters=5000.0);assertTrue(AdaptiveTrainer.reached(r,p));assertFalse(AdaptiveTrainer.reached(r,profile));assertNull(p.validate())}
    @Test fun treadmillAndOutdoorAchievementsAreSeparate(){val goal=easy(3,3000.0,mode=RunMode.TREADMILL).copy(meters=10000.0);assertFalse(AdaptiveTrainer.plan(profile,listOf(goal),mode=RunMode.OUTDOOR,now=now).achieved);assertTrue(AdaptiveTrainer.plan(profile,listOf(goal),mode=RunMode.TREADMILL,now=now).achieved)}
    @Test fun tooManyHardEffortsAndMissingReadinessPreventProgress(){val history=listOf(easy(3,rpe=9),easy(8,rpe=8),easy(12,rpe=3));val p=AdaptiveTrainer.plan(profile,history,now=now);assertEquals(TrainingFocus.RECOVER,p.focus);assertFalse(p.qualityAllowed)}
    @Test fun qualityNeedsObjectiveEvidenceAndRpe(){val q=easy(8,3000.0,rpe=4).copy(workout=Workout.INTERVALS,targetPace=330,phases=Training.fallback(Workout.INTERVALS));assertFalse(AdaptiveTrainer.qualitySucceeded(q));val p=AdaptiveTrainer.plan(profile,listOf(easy(3),q,easy(12)),now=now);assertEquals(330,p.targetPace)}
    @Test fun successfulQualityProgressesOnlyPace(){
        val phases=Training.fallback(Workout.INTERVALS);val seconds=phases.sumOf{it.duration};val start=now-8*day-seconds*1000
        val q=Run(started=start,ended=start+seconds*1000,mode=RunMode.OUTDOOR,workout=Workout.INTERVALS,activeSeconds=seconds.toDouble(),meters=seconds*3.0,zoneBounds=profile.bounds,phases=phases,targetPace=330,rpe=6,
            motion=(0..seconds).map{Motion(start+it*1000,it.toDouble(),it*1000.0/330,1000.0/330)},pulses=(0..seconds).map{Pulse(start+it*1000,it.toDouble(),140)})
        assertTrue(AdaptiveTrainer.qualitySucceeded(q));val p=AdaptiveTrainer.plan(profile,listOf(easy(3),q,easy(12)),now=now);assertEquals(325,p.targetPace);assertEquals(4,p.repetitions);assertEquals(120,p.workSeconds)
    }
    @Test fun maxOneQualitySessionWithinSevenDays(){val q=easy(4).copy(workout=Workout.INTERVALS);assertFalse(AdaptiveTrainer.plan(profile,listOf(easy(2),q,easy(12)),now=now).qualityAllowed)}
    @Test fun researchHintUsesAppropriateAgeAndSexWithoutHealthLabel(){assertTrue(ResearchNorms.hint(38,"Männlich").contains("5:07"));assertTrue(ResearchNorms.hint(38,"Weiblich").contains("6:00"));assertTrue(ResearchNorms.hint(38,"divers").contains("keine passende Vergleichsgruppe"));assertFalse(ResearchNorms.hint(80,"Männlich").contains("5:07"))}
    @Test fun speedFactorHasCorrectDirection(){assertEquals(341,ResearchNorms.adjustedPace(307.0,90.0));assertEquals(279,ResearchNorms.adjustedPace(307.0,110.0));assertEquals(307,ResearchNorms.adjustedPace(307.0,100.0));assertEquals(308.0,ResearchNorms.distanceReference(10.0,"Männlich")!!,.001)}
    @Test fun settingsRoundTripConfigurableGoal(){val p=profile.copy(goalKm=5.0,goalPace=330,runsPerWeek=2);assertEquals(p,Codec.profile(Codec.profile(p)))}
}
