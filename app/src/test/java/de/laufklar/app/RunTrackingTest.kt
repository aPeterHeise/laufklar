package de.laufklar.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunTrackingTest {
    private val bounds = listOf(90, 110, 130, 150, 170, 190)
    private fun point(seconds: Int, segment: Int = 0) = TrackPoint(seconds * 1000L, seconds.toDouble(), 52.0, 13.0 + seconds * .00001, 5.0, segment)
    private fun recovery(endBpm: Int = 110, rest: Int? = 60) = Cooldown(
        started = 100_000, startBpm = 160, restingHr = rest, ended = 160_000, state = CooldownState.COMPLETE,
        pulses = (1..60).map { CooldownPulse(100_000 + it * 1000L, if (it < 55) 160 - it else endBpm) }
    )

    @Test fun briefStopsDoNotPauseButEightSecondsDo() {
        val detector = AutoPauseDetector()
        (0..7).forEach { assertNull(detector.sample(.1, it * 1000L, false)) }
        assertEquals(AutoPauseDetector.Action.PAUSE, detector.sample(.1, 8_000, false))
    }

    @Test fun walkingResetsStationaryDetection() {
        val detector = AutoPauseDetector()
        (0..5).forEach { assertNull(detector.sample(0.0, it * 1000L, false)) }
        assertNull(detector.sample(.7, 6_000, false))
        (7..14).forEach { assertNull(detector.sample(0.0, it * 1000L, false)) }
        assertEquals(AutoPauseDetector.Action.PAUSE, detector.sample(0.0, 15_000, false))
    }

    @Test fun MissingOrStaleGpsCannotTriggerPause() {
        val detector = AutoPauseDetector()
        (0..7).forEach { detector.sample(0.0, it * 1000L, false) }
        assertNull(detector.sample(null, 8_000, false))
        assertNull(detector.sample(0.0, 9_000, false))
        assertNull(detector.sample(0.0, 30_000, false))
        assertNull(detector.sample(Double.NaN, 31_000, false))
        assertNull(detector.sample(-1.0, 32_000, false))
    }

    @Test fun resumeRequiresThreeSecondsOfMovementAndRejectsOneSpike() {
        val detector = AutoPauseDetector()
        assertNull(detector.sample(2.0, 0, true))
        assertNull(detector.sample(.3, 1_000, true))
        (2..4).forEach { assertNull(detector.sample(.9, it * 1000L, true)) }
        assertEquals(AutoPauseDetector.Action.RESUME, detector.sample(.9, 5_000, true))
    }

    @Test fun resetForManualPauseDiscardsMovementCandidate() {
        val detector = AutoPauseDetector()
        detector.sample(1.0, 0, true); detector.sample(1.0, 1_000, true)
        detector.reset()
        assertNull(detector.sample(1.0, 3_000, true))
        assertNull(detector.sample(1.0, 4_000, true))
    }

    @Test fun spokenDistancesHaveNoTrailingZeros() {
        assertEquals("2,5", Metrics.spokenKm(2500.0))
        assertEquals("3", Metrics.spokenKm(3000.0))
        assertEquals("0,25", Metrics.spokenKm(250.0))
        assertEquals("10", Metrics.spokenKm(10000.0))
    }

    @Test fun distanceCueUsesCrossedMilestoneEvenWhenGpsOvershoots() {
        val coach = Coach()
        val run = Run(started = 0, mode = RunMode.OUTDOOR, workout = Workout.EASY, zoneBounds = bounds)
        coach.update(run, Profile(), 0)
        assertTrue(coach.update(run.copy(meters = 2507.0, activeSeconds=20.0), Profile(), 20_000)!!.startsWith("2,5 Kilometer."))
        assertTrue(coach.update(run.copy(meters = 3004.0, activeSeconds=40.0), Profile(), 40_000)!!.startsWith("3 Kilometer."))
    }

    @Test fun heartRateAlertCombinesDistanceWithoutRepeatingTheAdvice() {
        val coach = Coach()
        fun run(seconds: Int, meters: Double) = Run(started = 0, mode = RunMode.OUTDOOR, workout = Workout.EASY,
            zoneBounds = bounds, meters = meters, activeSeconds = seconds.toDouble(),
            pulses = (seconds-4..seconds).map { Pulse(it*1000L, it.toDouble(), 170) })
        coach.update(run(100, 490.0), Profile(), 100_000)
        (101..130).forEach{assertNull(coach.update(run(it,490.0),Profile(),it*1000L))}
        val cue=coach.update(run(132, 501.0), Profile(), 132_000)!!
        assertTrue(cue.startsWith("0,5 Kilometer."));assertTrue(cue.contains("Puls"))
        assertNull(coach.update(run(160, 506.0), Profile(), 160_000))
    }

    @Test fun routeUsesRecordedZonesAndKeepsBoundaryConnected() {
        val parts = zoneRouteParts((0..4).map { point(it) }, listOf(Pulse(0, 0.0, 100), Pulse(2000, 2.0, 155)), bounds)
        assertEquals(listOf(1, 4), parts.map { it.zone })
        assertEquals(parts[0].points.last(), parts[1].points.first())
    }

    @Test fun routeNeverBridgesPauseOrGpsGap() {
        val points = listOf(point(0), point(1), point(2, 1), point(3, 1), point(30, 1), point(31, 1))
        val parts = zoneRouteParts(points, emptyList(), bounds)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.points.size == 2 && it.zone == null })
    }

    @Test fun routeDoesNotUseFutureOrStalePulse() {
        val parts = zoneRouteParts((0..14).map { point(it) }, listOf(Pulse(2000, 2.0, 120)), bounds)
        assertEquals(listOf(null, 2, null), parts.map { it.zone })
        assertEquals(2_000L, parts[1].points.first().time)
        assertEquals(12_000L, parts[1].points.last().time)
    }

    @Test fun cooldownScoreUsesPersonalRestingPulseAndOnlyEndpointWindow() {
        val cooldown = recovery()
        assertEquals(110, cooldown.endBpm)
        assertEquals(50, cooldown.dropBpm)
        assertEquals(50, cooldown.score)
        assertEquals(50, cooldown.copy(pulses = listOf(CooldownPulse(110_000, 60)) + cooldown.pulses.filter { it.time >= 155_000 }).score)
    }

    @Test fun risingPulseIsNotPositiveRecoveryAndScoreIsBounded() {
        assertEquals(-10, recovery(170).dropBpm)
        assertEquals(0, recovery(170).score)
        assertEquals(100, recovery(55).score)
        assertNull(recovery(rest = 160).score)
    }

    @Test fun cooldownDoesNotInventRestingPulseOrScoreOnIncompleteData() {
        assertEquals(50, recovery(rest = null).dropBpm)
        assertNull(recovery(rest = null).score)
        assertNull(recovery().copy(state = CooldownState.INTERRUPTED).score)
        assertNull(recovery().copy(state = CooldownState.MEASURING).score)
        assertNull(recovery().copy(pulses = recovery().pulses.take(56)).score)
        assertNull(recovery().copy(pulses = listOf(CooldownPulse(159_000, 110))).score)
    }

    @Test fun restingRangeRequiresSustainedSamplesWithoutGap() {
        val cooldown = recovery().copy(pulses = listOf(CooldownPulse(110_000, 70), CooldownPulse(130_000, 69), CooldownPulse(131_000, 69), CooldownPulse(132_000, 69), CooldownPulse(133_000, 69)))
        assertEquals(33, cooldown.restingRangeSeconds)
    }

    @Test fun cooldownPersistsWithoutChangingRunMetrics() {
        val run = Run(started = 0, ended = 100_000, mode = RunMode.OUTDOOR, workout = Workout.EASY,
            activeSeconds = 90.0, meters = 300.0, zoneBounds = bounds, pulses = listOf(Pulse(99_000, 89.0, 160)), cooldown = recovery())
        val decoded = Codec.run(Codec.run(run))
        assertEquals(run, decoded)
        assertEquals(50, decoded.cooldown!!.score)
        assertEquals(160, decoded.averageHr)
        assertEquals(100_000L, decoded.ended)
        assertEquals(90.0, decoded.activeSeconds, .001)
        assertEquals(50, Codec.run(run).getJSONObject("cooldown").getInt("score"))
        assertNull(Codec.run(Codec.run(run.copy(cooldown = null))).cooldown)
    }

    @Test fun automaticFeaturesDefaultOnAndExplicitOptOutSurvivesReload() {
        assertTrue(Codec.profile(JSONObject().put("health", false)).healthExport)
        assertTrue(Codec.profile(JSONObject()).autoPause)
        val disabled = Profile(healthExport = false, autoPause = false)
        assertEquals(disabled, Codec.profile(Codec.profile(disabled)))
    }
}
