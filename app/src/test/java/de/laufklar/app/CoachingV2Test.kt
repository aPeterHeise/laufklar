package de.laufklar.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class CoachingV2Test {
    private val profile = Profile()
    private val origin = 1_800_000_000_000L

    private fun trace(
        seconds: Int = 1500, paceBefore: Double = 390.0, paceAfter: Double = paceBefore,
        changeAt: Int = seconds, start: Long = origin, hr: (Int) -> Int = { 143 }
    ): Run {
        val motion = (0..seconds).map { t ->
            val meters = minOf(t, changeAt) * 1000 / paceBefore + (t - changeAt).coerceAtLeast(0) * 1000 / paceAfter
            Motion(start + t * 1000L, t.toDouble(), meters, if (t > changeAt) 1000 / paceAfter else 1000 / paceBefore)
        }
        return Run(started = start, ended = start + seconds * 1000L, mode = RunMode.OUTDOOR, workout = Workout.EASY,
            activeSeconds = seconds.toDouble(), meters = motion.last().meters, zoneBounds = profile.bounds, rpe = 3,
            motion = motion,
            points = motion.map { TrackPoint(it.time, it.elapsed, 50 + it.meters / 111194.93, 10.0, 4.0, 0) },
            pulses = (0..seconds).map { Pulse(start + it * 1000L, it.toDouble(), hr(it)) })
    }

    private fun at(full: Run, seconds: Int): Run = full.copy(ended = null, activeSeconds = seconds.toDouble(),
        meters = full.motion[seconds].meters, motion = full.motion.take(seconds + 1),
        points = full.points.take(seconds + 1), pulses = full.pulses.take(seconds + 1))

    private fun plan(id: MissionId = MissionId.M01, p: Profile = profile) =
        MissionPlanner.create(p, emptyList(), RunMode.OUTDOOR, Readiness.READY, Workout.EASY, null, false, origin)
            .copy(missions = listOf(id))

    @Test fun threeSuitableRunsLearnDespiteAnExistingProfilePace() {
        val history = listOf(4, 8, 12).map { trace(start = origin - it * AdaptiveTrainer.DAY) }
        assertNull(LearningEvaluator.evaluate(history.map { it.copy(rpe = null) }, RunMode.OUTDOOR, origin).observedPace)
        val learned = LearningEvaluator.evaluate(history, RunMode.OUTDOOR, origin)
        assertEquals(3, learned.runIds.size)
        assertEquals(390, learned.observedPace)
        assertEquals(PulseTarget(138, 148, TargetSource.OBSERVED_EASY), learned.target)
        val planned = MissionPlanner.create(profile, history, RunMode.OUTDOOR, Readiness.READY, Workout.EASY, null, false, origin)
        assertEquals(390, planned.quietPace)
        assertEquals(345, profile.usualPace)
    }

    @Test fun incompatiblePulseValuesLeaveARealPaceButNoPulseTarget() {
        val history = listOf(138, 144, 155).mapIndexed { i, value ->
            trace(start = origin - (i + 1) * AdaptiveTrainer.DAY, hr = { value })
        }
        val learned = LearningEvaluator.evaluate(history, RunMode.OUTDOOR, origin)
        assertEquals(390, learned.observedPace)
        assertNull(learned.target)
        assertEquals(TargetSource.MEASURED_MAX_PERCENT, LearningEvaluator.source(profile.copy(measuredMax = 180)))
    }

    @Test fun fixedKilometerCorrectsSecondHalfAndCannotWinLater() {
        val full = trace(paceAfter = 360.0, changeAt = 795)
        val runner = MissionRunner(plan())
        runner.start(MissionId.M01, at(full, 600), 390.0, 143.0)
        val feedback = (601..1400).mapNotNull { runner.update(at(full, it), 143) }
        val attempt = runner.attempts.single()
        assertEquals(MissionState.NOT_MET, attempt.state)
        assertEquals(-30, (attempt.secondPace!! - attempt.firstPace!!).roundToInt())
        assertEquals(at(full, 600).meters + 1000, attempt.endedMeters!!, .01)
        assertTrue(feedback.any { it.contains("Nimm etwas Tempo heraus") })
        assertTrue(feedback.last().contains("30 Sekunden"))
    }

    @Test fun runtimeDoesNotStartMissionAfterFiveMinutesAndLeavesTimeForSummary() {
        val full = trace()
        val coach = SessionCoach(plan(), emptyList(), profile)
        val cues = (0..1500).mapNotNull { second ->
            coach.update(at(full, second), origin + second * 1000)
        }
        val withMission = coach.finish(full)
        assertEquals(MissionState.SUCCEEDED, withMission.missionAttempts.first().state)
        assertTrue(withMission.missionAttempts.first().startedAt!! >= 570)
        assertTrue(cues.any { it.text.contains("Halbzeit") })
        assertTrue(cues.any { it.text.contains("Rest der Runde") })
        val summary = SummaryBuilder.build(withMission, emptyList())
        assertTrue(summary.audio.contains("Geschafft"))
        assertTrue(summary.audio.contains("passender Vergleich"))
    }

    @Test fun deliberatePaceIsNeededToCallAnOpeningTooFast() {
        fun opening(p: Profile) : List<CoachEvent> {
            val full = trace(305, 360.0)
            val coach = SessionCoach(plan(p = p), emptyList(), p)
            return (0..305).mapNotNull { coach.update(at(full,it), origin + it * 1000) }
        }
        assertTrue(opening(profile).none { it.text.contains("schneller als geplant") })
        assertEquals(1, opening(profile.copy(easyPaceOverride = 420)).count { it.text.contains("schneller als geplant") })
    }

    @Test fun pauseMakesFixedWindowUnmeasurable() {
        val full = trace()
        val runner = MissionRunner(plan())
        runner.start(MissionId.M01, at(full, 600), 390.0, 143.0)
        runner.interrupt(at(full, 650), "Pause")
        assertNull(runner.update(at(full, 1000), 143))
        assertEquals(MissionState.UNMEASURABLE, runner.attempts.single().state)
    }

    @Test fun comparisonUsesNewestSuitableReferenceAndDoesNotCallHigherPulseFailure() {
        val older = trace(start = origin - 2 * AdaptiveTrainer.DAY, paceBefore = 435.0, hr = { 151 })
        val old = trace(start = origin - AdaptiveTrainer.DAY, paceBefore = 410.0, hr = { 138 })
        val current = trace(paceBefore = 390.0, hr = { 146 })
        val result = ComparisonEngine.evaluate(current, listOf(older, old)).result!!
        assertEquals(old.id, result.referenceId)
        assertEquals(ComparisonKind.FASTER_HIGHER_PULSE, result.kind)
        assertEquals(8, result.hrDelta)
        assertEquals(-20, result.paceDelta)
        assertTrue(result.sections.zipWithNext().all { (a, b) -> a.current.until <= b.current.from })
        assertNull(ComparisonEngine.evaluate(current.copy(readiness = "UNWELL"), listOf(old)).result)
        assertNull(ComparisonEngine.evaluate(current.copy(pulses = emptyList()), listOf(old)).result)
    }

    @Test fun summaryAndAttemptsSurvivePersistenceWithoutInventingLegacyResults() {
        val current = trace()
        val session = plan()
        val run = current.copy(sessionPlan = session, missionAttempts = listOf(
            MissionAttempt(MissionId.M01, MissionState.SUCCEEDED, 600.0, 1000.0, 990.0, 2000.0, 390.0, "Geschafft.")
        ))
        val summary = SummaryBuilder.build(run, listOf(trace(start = origin - AdaptiveTrainer.DAY)))
        val stored = run.copy(summary = summary)
        assertEquals(stored, Codec.run(Codec.run(stored)))
        val legacy = Codec.run(current).apply { remove("missionAttempts");remove("sessionPlan");remove("summary") }
        assertTrue(Codec.run(legacy).missionAttempts.isEmpty())
        assertNull(Codec.run(legacy).summary)
    }

    @Test fun tempoRequiresOptInGoodReadinessValidLearningAndWeeklyRecovery() {
        val history = listOf(4, 8, 12).map { trace(start = origin - it * AdaptiveTrainer.DAY) }
        val p = profile.copy(intensityReady = true)
        fun choose(state: Readiness, minutes: Int?, runs: List<Run> = history) =
            MissionPlanner.create(p, runs, RunMode.OUTDOOR, state, Workout.INTERVALS, minutes, true, origin)
        val tempo = choose(Readiness.READY, 30)
        assertTrue(tempo.intense)
        assertTrue(tempo.missions.single().intense)
        assertEquals(tempo, choose(Readiness.READY, 30))
        assertFalse(choose(Readiness.READY, null).intense)
        assertFalse(choose(Readiness.TIRED, 30).intense)
        assertFalse(choose(Readiness.UNWELL, 30).intense)
        val recentQuality = history.first().copy(missionAttempts = listOf(MissionAttempt(MissionId.M07, startedAt = 700.0)))
        assertFalse(choose(Readiness.READY, 30, listOf(recentQuality) + history.drop(1)).intense)
    }

    @Test fun slowDriftPromptsOneAdjustmentAndRechecksAfterTwoMinutes() {
        val full = trace(1000, hr = { t -> if(t<360) 128 else if(t<805) 128+(t-360)/19 else 138 })
        val coach = SessionCoach(plan(MissionId.M02), emptyList(), profile)
        val cues = (0..1000).mapNotNull { second -> coach.update(at(full,second), origin + second*1000) }
        val drift = cues.single { it.text.contains("seit ein paar Minuten") }
        val followUp = cues.first { it.elapsed >= drift.elapsed+120 && it.text.contains("Puls ist") }
        assertTrue(followUp.elapsed in (drift.elapsed+120)..(drift.elapsed+130))
    }

    @Test fun tempoFallsBackToRecoveryWhenPulseDisappears() {
        val full = trace()
        val session = plan(MissionId.M09).copy(intense=true, tempoPace=370)
        val runner = MissionRunner(session)
        runner.start(MissionId.M09, at(full,690),390.0,143.0)
        val cue = runner.update(at(full,700),null)
        assertTrue(cue!!.contains("Lauf locker weiter"))
        assertEquals(MissionState.PARTIAL,runner.attempts.single().state)
        assertNull(runner.update(at(full,900),145))
    }

    @Test fun canceledSpeechCompletesItsWaiterExactlyOnce() {
        val queue = SpeechQueue()
        val callbacks = mutableListOf<Boolean>()
        queue.add(SpeechCue("Zusammenfassung", "summary", onComplete={ callbacks += it }))
        queue.clear()
        queue.clear()
        assertEquals(listOf(false), callbacks)
        queue.add(SpeechCue("Veraltete Phase", "phase", validUntil=500, onComplete={ callbacks += it }))
        assertNull(queue.poll(501))
        assertEquals(listOf(false,false), callbacks)
    }

    @Test fun cooldownOnlyContributesAnObservedMinuteAndRequiresComparableProtocol() {
        val full = trace()
        fun cooldown(run: Run, method: RecoveryMethod, startBpm: Int) =
            Cooldown(run.ended!! + 2000,startBpm,60,(0..60).map { CooldownPulse(run.ended + 2000 + it*1000,120) },
                run.ended+62000,CooldownState.COMPLETE,method)
        val walking = RecoveryMethod.WALKING_SELF_REPORTED
        val prior = trace(start=origin-AdaptiveTrainer.DAY)
        val priorWithCooldown = prior.copy(cooldown=cooldown(prior,walking,150))
        val run = full.copy(cooldown=cooldown(full,walking,155))
        assertEquals(35,SummaryBuilder.build(run,listOf(priorWithCooldown)).cooldownDrop)
        assertEquals(30,SummaryBuilder.build(run,listOf(priorWithCooldown)).cooldownComparison?.dropBpm)
        val unknown = run.copy(cooldown=run.cooldown!!.copy(recovery=RecoveryMethod.UNKNOWN))
        assertNull(SummaryBuilder.build(unknown,listOf(priorWithCooldown)).cooldownComparison)
        val interrupted = run.copy(cooldown=run.cooldown.copy(state=CooldownState.INTERRUPTED))
        assertNull(SummaryBuilder.build(interrupted,emptyList()).cooldownDrop)
    }

}
