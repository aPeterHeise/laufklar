package de.laufklar.app

import kotlin.math.abs
import kotlin.math.roundToInt

enum class TargetSource(val label: String) {
    AGE_ESTIMATE("Altersformel"), MEASURED_MAX_PERCENT("Prozent vom gemessenen Maximalpuls"),
    OBSERVED_EASY("vorläufiger Bereich aus Referenzläufen"),
    USER_DEFINED_UNVERIFIED("selbst hinterlegte Grenzen"),
    USER_CALIBRATED_THRESHOLD("persönlich bestimmte Schwelle")
}

data class PulseTarget(val low: Int, val high: Int, val source: TargetSource) {
    fun context(hr: Int): String = when {
        hr > high -> "Puls $hr, über dem heutigen Bereich. Nimm etwas Tempo heraus."
        hr >= high - 2 -> "Puls $hr, am oberen Rand. Heute nicht weiter beschleunigen."
        hr < low -> "Puls $hr, unter dem heutigen Bereich. Du kannst ruhig bleiben."
        else -> "Puls $hr, im heutigen Bereich. Halte den Rhythmus."
    }
}

data class Section(
    val from: Double, val until: Double, val meters: Double, val hr: Double?, val coverage: Double
) {
    val seconds get() = until - from
    val pace get() = seconds * 1000 / meters
}

object CoachingEvidence {
    private fun elapsedAt(samples: List<Motion>, meters: Double): Double? {
        val index = samples.binarySearch { it.meters.compareTo(meters) }
        if (index >= 0) return samples[index].elapsed
        val after = -index - 1
        val first = samples.getOrNull(after - 1) ?: return null
        val last = samples.getOrNull(after) ?: return null
        if (last.time - first.time !in 1..10_000 || last.meters <= first.meters) return null
        val elapsed = last.elapsed - first.elapsed
        if (elapsed <= 0 || (last.meters - first.meters) / elapsed > 9) return null
        return first.elapsed + elapsed * (meters - first.meters) / (last.meters - first.meters)
    }

    fun distance(run: Run, start: Double, end: Double): Section? {
        val a = elapsedAt(run.motion, start) ?: return null
        val b = elapsedAt(run.motion, end) ?: return null
        return time(run, a, b)?.copy(meters = end - start)
    }

    fun time(run: Run, start: Double, end: Double): Section? {
        if (start < 0 || end <= start || end > run.activeSeconds + .001) return null
        val points = run.motion
        val left = points.binarySearch { it.elapsed.compareTo(start) }
        val right = points.binarySearch { it.elapsed.compareTo(end) }
        val a = if (left < 0) -left - 2 else left
        val b = if (right < 0) -right - 1 else right
        if (a < 0 || b <= a || b >= points.size) return null
        val from = points[a]
        val until = points[b]
        if (run.pauses.any { it.from < until.time && it.until > from.time }) return null
        fun positionAt(i: Int, j: Int, time: Double): Double {
            val first = points[i]
            val last = points[j]
            val fraction = (time - first.elapsed) / (last.elapsed - first.elapsed)
            return first.meters + fraction * (last.meters - first.meters)
        }
        val startMeters = if (from.elapsed == start) from.meters else positionAt(a, a + 1, start)
        val endMeters = if (until.elapsed == end) until.meters else positionAt(b - 1, b, end)
        val meters = endMeters - startMeters
        if (!meters.isFinite() || meters <= 0) return null
        for (i in a until b) {
            val p = points[i]
            val q = points[i + 1]
            val dt = q.elapsed - p.elapsed
            val distance = q.meters - p.meters
            if (dt < 0 || q.time - p.time !in 1..10_000 || distance < 0) return null
            // Legacy GPS collections snapped elapsed time to the previous UI tick.
            val motionSeconds = maxOf(dt, (q.time - p.time) / 1000.0)
            if (distance / motionSeconds > 9) return null
            // GPS is sampled at reception. Active time must agree with wall time through this window.
            if (abs((q.time - p.time) / 1000.0 - dt) > 2.5) return null
        }
        var covered = 0.0
        var sum = 0.0
        val pulses = run.pulses
        var index = pulses.binarySearch { it.elapsed.compareTo(start) }
        if (index < 0) index = (-index - 2).coerceAtLeast(0)
        while (index + 1 < pulses.size && pulses[index].elapsed < end) {
            val p = pulses[index]
            val q = pulses[++index]
            if (q.time - p.time !in 1..10_000 || p.bpm !in 30..250 || q.elapsed - p.elapsed !in 0.0..10.0) continue
            val overlap = (minOf(end, q.elapsed) - maxOf(start, p.elapsed)).coerceAtLeast(0.0)
            covered += overlap
            sum += overlap * p.bpm
        }
        val coverage = (covered / (end - start)).coerceIn(0.0, 1.0)
        return Section(start, end, meters, if (coverage >= .8) sum / covered else null, coverage)
    }

    fun quiet(run: Run, start: Double, end: Double): Boolean {
        if (start < 480 || run.workout == Workout.INTERVALS && run.sessionPlan?.intense != false) return false
        if (run.missionAttempts.any {
                it.id.intense && it.startedAt?.let { a -> a - 90 < end && (it.endedAt ?: run.activeSeconds) + 90 > start } == true
            }) return false
        return run.coachEvents.none { it.kind == CueKind.EFFORT && it.elapsed in (start - 120)..end }
    }

    fun bandFraction(run: Run, start: Double, end: Double, passes: (Section) -> Boolean): Double {
        if (end <= start) return 0.0
        var from = start
        var seconds = 0.0
        while (from < end) {
            val to = minOf(from + 30, end)
            val section = time(run, from, to)
            if (section != null && passes(section)) seconds += to - from
            from = to
        }
        return seconds / (end - start)
    }
}

data class LearningSnapshot(
    val runIds: List<String> = emptyList(), val observedPace: Int? = null,
    val target: PulseTarget? = null, val bpmSpread: Int? = null, val latest: Long? = null,
    val missing: String = "Ein ruhiger Abschnitt mit Puls und Belastungsangabe fehlt.",
    val version: Int = 1
) {
    fun description(): String {
        val learned = observedPace?.let { " · ruhiges Tempo etwa ${Metrics.pace(it.toDouble())} min/km" }.orEmpty()
        return "Referenzläufe ${runIds.size}/3$learned. $missing".trim()
    }
}

object LearningEvaluator {
    fun evaluate(history: List<Run>, mode: RunMode, now: Long): LearningSnapshot {
        val candidates = history.asSequence().filter {
            it.ended != null && !it.recovered && it.mode == mode && it.readiness == Readiness.READY.name &&
                it.workout != Workout.INTERVALS && now - it.started in 0..35 * AdaptiveTrainer.DAY
        }.sortedByDescending { it.started }.toList()
        var needsRating = false
        val references = candidates.asSequence().mapNotNull { run ->
            val section = (480..(run.activeSeconds.toInt() - 300) step 30).firstNotNullOfOrNull { offset ->
                val start = offset.toDouble()
                if (!CoachingEvidence.quiet(run, start, start + 300)) return@firstNotNullOfOrNull null
                val value = CoachingEvidence.time(run, start, start + 300) ?: return@firstNotNullOfOrNull null
                value.takeIf { it.hr != null && it.pace in 180.0..1200.0 &&
                    CoachingEvidence.bandFraction(run, start, start + 300) { part -> abs(part.pace - it.pace) <= 30 } >= .8 }
            } ?: return@mapNotNull null
            if (run.rpe == null) needsRating = true
            if (run.rpe !in 2..4) return@mapNotNull null
            run to section
        }.distinctBy { it.first.id }.take(3).toList()
        val ids = references.map { it.first.id }
        if (ids.size < 3) return LearningSnapshot(ids, latest = references.firstOrNull()?.first?.started,
            missing = if (needsRating) "Ergänze die Belastung beim letzten geeigneten Lauf." else "Weitere ruhige Abschnitte mit Puls und Belastung 2–4 fehlen.")
        val hrs = references.map { it.second.hr!!.roundToInt() }.sorted()
        val spread = hrs.last() - hrs.first()
        val target = if (spread <= 12) PulseTarget(hrs[1] - 5, hrs[1] + 5, TargetSource.OBSERVED_EASY) else null
        return LearningSnapshot(ids, references.map { it.second.pace.roundToInt() }.sorted()[1], target, spread,
            references.first().first.started, if (target == null) "Der Puls schwankt noch zu stark für ein Zahlenziel." else "")
    }

    fun source(profile: Profile): TargetSource = when {
        profile.customBounds != null && profile.calibratedThreshold -> TargetSource.USER_CALIBRATED_THRESHOLD
        profile.customBounds != null -> TargetSource.USER_DEFINED_UNVERIFIED
        profile.measuredMax != null -> TargetSource.MEASURED_MAX_PERCENT
        else -> TargetSource.AGE_ESTIMATE
    }

    fun target(profile: Profile, learning: LearningSnapshot): PulseTarget? {
        val source = source(profile)
        if (source == TargetSource.USER_CALIBRATED_THRESHOLD || source == TargetSource.USER_DEFINED_UNVERIFIED) {
            return PulseTarget(profile.bounds[1], profile.bounds[2] - 1, source)
        }
        return learning.target?.takeIf { it.high < profile.bounds[4] }
    }
}
