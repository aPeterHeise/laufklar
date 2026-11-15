package de.laufklar.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class SessionCoach(private val plan: SessionPlan, private val history: List<Run>, private val profile: Profile) {
    private val runner = MissionRunner(plan)
    private val effort = EffortMonitor()
    private val route = RouteMatcher(history)
    private val events = mutableListOf<CoachEvent>()
    private var routeMatch: RouteMatch? = null
    private var nextRouteCheck = 0.0
    private var nextDistance = profile.cueMeters.toDouble()
    private var openingOutside: Double? = null
    private var openingSaid = false
    private var lastResume = -1000.0
    private var lastInfo = -1000.0
    private var closingSaid = false
    private var nearEndSaid = false
    private var comparisonSaid = false
    private var slowDriftSince: Double? = null
    private var slowDriftCheck = 0.0
    private var driftReviewAt: Double? = null
    private var driftAtCue: Int? = null
    private var driftEpisode = false
    private var exhausted = false
    private var offered = false
    private var startDelaySaid = false
    private var referenceWindow: List<TrackPoint> = emptyList()
    private var planDurationSaid = false

    fun snapshot(run: Run): Run {
        val attempts = runner.snapshot().ifEmpty {
            if (exhausted) emptyList()
            else listOf(MissionAttempt(plan.missions.first(), if (offered) MissionState.OFFERED else MissionState.PLANNED))
        }
        return run.copy(sessionPlan = plan, missionAttempts = attempts, coachEvents = events.toList(),
            coachMission = runner.current?.id?.title ?: plan.missions.first().title,
            coachOutcome = attempts.lastOrNull { it.state.terminal }?.result.orEmpty())
    }

    private fun emit(run: Run, kind: CueKind, message: String): CoachEvent {
        lastInfo = run.activeSeconds
        return CoachEvent(run.activeSeconds, kind, message).also { events += it }
    }

    fun skip(run: Run): CoachEvent {
        if (runner.current == null) exhausted = true
        val message = runner.finish(run, MissionState.ABORTED, "Aufgabe übersprungen. Lauf in deinem ruhigen Rhythmus weiter.")
        return emit(run, CueKind.TASK, message)
    }

    fun pause(run: Run): CoachEvent? {
        val message = runner.interrupt(run, if (runner.current?.id?.intense == true)
            "Die Tempoaufgabe endet wegen der Pause. Laufe danach ruhig weiter."
            else "Die Pause unterbricht die Messung der Aufgabe.")
        return message?.let { emit(run, CueKind.TASK, it) }
    }

    fun resume(run: Run) {
        lastResume = run.activeSeconds
        slowDriftSince = null
        if (driftReviewAt != null) driftReviewAt = run.activeSeconds + 120
        openingOutside = null
    }

    fun finish(run: Run): Run {
        if (runner.current == null && runner.attempts.isEmpty()) exhausted = true
        runner.current?.let {
            runner.finish(run, MissionState.PARTIAL, "Lauf beendet, bevor die Aufgabe vollständig ausgewertet werden konnte.")
        }
        return snapshot(run)
    }

    fun update(run: Run, now: Long): CoachEvent? {
        val t = run.activeSeconds
        val hr = Metrics.freshHr(run.pulses, now)
        val crossed = run.meters >= nextDistance
        val distance = if (crossed) {
            val milestone = floor(run.meters / profile.cueMeters) * profile.cueMeters
            nextDistance = milestone + profile.cueMeters
            "${Metrics.spokenKm(milestone)} Kilometer."
        } else ""
        if (events.isEmpty()) {
            offered = true
            val target = plan.pulseTarget?.let { " Heute orientieren wir uns an ${it.low} bis ${it.high} Schlägen, ${it.source.label}." }
                ?: " Dein persönlicher Pulsbereich ist noch offen."
            return emit(run, CueKind.START,
                "${plan.title}. Erst ${plan.warmup / 60} Minuten locker ankommen. Danach: ${plan.missions.first().title}.$target")
        }
        val resumed = t - lastResume < 40
        val fast = runner.current?.let { it.id.phases(plan.extraRepetitions).getOrNull(it.phase)?.fast } == true
        // A measured maximum still gives estimated training boundaries, not a measured threshold.
        val effortCue = effort.update(run, profile, now, fast, limitOverride = when {
            fast -> profile.bounds[4]
            plan.pulseTarget != null -> plan.pulseTarget.high + 1
            else -> profile.bounds[4]
        }, estimatedTarget = plan.pulseTarget?.source != TargetSource.USER_CALIBRATED_THRESHOLD, allowDrift = !resumed && t >= plan.warmup)
        if (effortCue != null) {
            if (!effortCue.recovered && runner.current?.id?.intense == true) {
                runner.finish(run, MissionState.PARTIAL, "Tempoaufgabe zur Entlastung beendet.")
                exhausted = true
            }
            return emit(run, if (effortCue.recovered) CueKind.RECOVERY else CueKind.EFFORT,
                "${effortCue.text}${if (distance.isNotBlank()) " $distance" else ""}")
        }
        if (!fast && !resumed && !effort.active) {
            drift(run, hr)?.let { return emit(run, it.first, it.second) }
        }
        if (t >= 60 && t < 300 && !openingSaid && !resumed && plan.openingPace != null) {
            val pace = Metrics.recentPace(run.motion, t)
            if (pace != null && pace < plan.openingPace - 20) {
                if (openingOutside == null) openingOutside = t
                if (t - openingOutside!! >= 30) {
                    openingSaid = true
                    return emit(run, CueKind.TASK, "Für den ruhigen Einstieg bist du schneller als geplant. Nimm etwas Tempo heraus.")
                }
            } else openingOutside = null
        }
        if (t >= nextRouteCheck) {
            routeMatch = route.match(run)
            nextRouteCheck = t + 10
        }
        val match = routeMatch?.takeIf { now - (run.points.lastOrNull()?.time ?: 0) in 0..10_000 }
        val attempt = runner.current
        if (attempt != null) {
            val increasedLoad = effort.active || driftEpisode
            if (increasedLoad && attempt.id !in listOf(MissionId.M03, MissionId.M04, MissionId.M06))
                return emit(run, CueKind.TASK, runner.finish(run, MissionState.PARTIAL, "Wir beenden die Aufgabe und laufen erst einmal ruhiger."))
            if (attempt.id == MissionId.M05 && run.meters >= attempt.startedMeters!! + 600 &&
                !followsReference(run, attempt)) {
                return emit(run, CueKind.TASK, runner.finish(run, MissionState.UNMEASURABLE,
                    "Die Strecke wich vom Referenzstück ab. Das Ergebnis bleibt offen."))
            }
            runner.update(run, hr)?.let { message ->
                val kind = if (runner.current?.id?.intense == true) CueKind.PHASE else CueKind.TASK
                return emit(run, kind, message)
            }
        } else if (t >= plan.warmup + 90 && !resumed && !effort.active && !driftEpisode && !exhausted) {
            startMission(run, hr, match)?.let {
                val kind = if (runner.current?.id?.intense == true) CueKind.PHASE else CueKind.TASK
                return emit(run, kind, it)
            }
            if (t >= plan.warmup + 150 && !startDelaySaid && runner.attempts.isEmpty()) {
                startDelaySaid = true
                return emit(run, CueKind.TASK, "Für die Aufgabe warte ich auf einen gleichmäßigen, gut gemessenen Abschnitt. Lauf locker weiter.")
            }
        }
        if (runner.current == null && runner.attempts.isNotEmpty() && !closingSaid && t - lastInfo >= 25) {
            closingSaid = true
            return emit(run, CueKind.TASK, "Die Aufgabe ist ausgewertet. Den Rest der Runde halten wir ruhig.")
        }
        if (run.workout == Workout.LONG && !planDurationSaid &&
            run.plannedSeconds?.let { t >= it } == true && runner.current == null) {
            planDurationSaid = true
            return emit(run, CueKind.TASK, "Der vorgeschlagene Umfang ist erreicht. Lauf den Rest ruhig. Du entscheidest, wann du fertig bist.")
        }
        if (runner.current == null && !nearEndSaid && match != null && match.progress > 1000 && match.remaining in 100.0..550.0) {
            nearEndSaid = true
            return emit(run, CueKind.ROUTE, "Noch etwa ${(match.remaining / 50).roundToInt() * 50} Meter bis zum Ende der aufgezeichneten Runde. Bleib im ruhigen Rhythmus.")
        }
        if (distance.isEmpty()) return null
        if (!comparisonSaid && !plan.intense && runner.current == null && t >= 720) {
            val result = ComparisonEngine.evaluate(snapshot(run), history).result
            if (result != null) {
                comparisonSaid = true
                return emit(run, CueKind.PROGRESS, "$distance ${result.text()} Den nächsten Vergleich hörst du beim Abschluss.")
            }
        }
        val pace = Metrics.recentPace(run.motion, t)
        val context = when {
            hr == null -> "Gerade fehlt verlässlicher Puls."
            fast -> "Puls $hr. Halte den vereinbarten Tempoabschnitt."
            effort.active || driftEpisode -> "Puls $hr. Bleib beim ruhigeren Tempo."
            runner.current != null -> {
                val target = plan.pulseTarget
                if (target == null) "Puls $hr. Halte den Rhythmus der Aufgabe."
                else "Puls $hr, ${if (hr > target.high) "über" else if (hr < target.low) "unter" else "im"} heutigen Bereich."
            }
            plan.pulseTarget != null -> plan.pulseTarget.context(hr)
            else -> "Puls $hr. Dein persönlicher Bereich ist noch offen. Heute ruhig weiterlaufen."
        }
        return emit(run, CueKind.DISTANCE, "$distance ${pace?.let { "Pace ${Metrics.pace(it)}. " }.orEmpty()}$context")
    }

    private fun startMission(run: Run, hr: Int?, match: RouteMatch?): String? {
        if (runner.attempts.size >= 2) return null
        val t = run.activeSeconds
        val recent = CoachingEvidence.time(run, t - 122, t - 2) ?: return null
        if (recent.pace !in 180.0..1200.0) return null
        val stable = CoachingEvidence.bandFraction(run, recent.from, recent.until) { abs(it.pace - recent.pace) <= 30 }
        if (stable < .8) return null
        val secondsLeft = match?.let { it.remaining * recent.pace / 1000 }
        val budget = plan.seconds?.minus(t)?.let { if (secondsLeft == null) it else minOf(it, secondsLeft) } ?: secondsLeft
        if (runner.attempts.isNotEmpty()) {
            if (plan.intense || match == null || secondsLeft == null || secondsLeft !in 181.0..215.0) return null
            if (run.workout == Workout.LONG && run.plannedSeconds?.let { t + secondsLeft < it } == true) return null
            if (runner.attempts.last().endedAt?.let { t - it < 45 } == true) return null
            return runner.start(MissionId.M06, run, minOf(recent.pace, plan.quietPace?.toDouble() ?: recent.pace), recent.hr)
        }
        val preferred = plan.missions.first()
        if (preferred.intense) {
            if (hr == null || hr >= profile.bounds[4]) return null
            val length = preferred.phases(plan.extraRepetitions).sumOf { it.seconds }
            if (budget == null || budget < length + 300) {
                exhausted = true
                return "Für die geplante Tempoaufgabe bleibt zu wenig Zeit. Wir halten die Runde ruhig."
            }
            return runner.start(preferred, run, recent.pace, recent.hr)
        }
        var id = preferred
        if (id == MissionId.M05 && (match == null || match.remaining < 800 ||
                !Metrics.sameReadiness(run, match.reference) || match.reference.hasTempoWork())) {
            if (t < 660 && run.mode == RunMode.OUTDOOR) return null
            id = MissionId.M02
        }
        if (id == MissionId.M03 && (hr == null || recent.hr == null)) id = MissionId.M02
        if (id == MissionId.M01 && (budget == null && recent.pace > 480 || budget != null && budget < recent.pace + 180)) id = MissionId.M02
        if (budget != null && budget < id.duration(recent.pace.roundToInt()) + 180) return null
        val reference = if (id == MissionId.M05 && match != null)
            CoachingEvidence.distance(match.reference, match.progress, match.progress + 600)?.takeIf {
                it.hr != null && CoachingEvidence.quiet(match.reference, it.from, it.until)
            } else null
        if (id == MissionId.M05 && reference != null && match != null) {
            val nodes = routeNodes(match.reference.points)
            referenceWindow = (0..6).mapNotNull { part ->
                val meters = match.progress + part * 100
                nodes.minByOrNull { abs(it.meters - meters) }?.takeIf { abs(it.meters - meters) < 20 }?.point
            }
        }
        if (id == MissionId.M05 && (reference == null || referenceWindow.size != 7)) id = MissionId.M02
        val target = when (id) {
            MissionId.M04 -> (recent.pace + 25).coerceAtMost(1200.0)
            MissionId.M05 -> reference!!.pace
            else -> recent.pace
        }
        return runner.start(id, run, target, recent.hr)
    }

    private fun followsReference(run: Run, attempt: MissionAttempt): Boolean {
        if (referenceWindow.size != 7) return false
        val points = run.points.filter { it.elapsed >= attempt.startedAt!! - 3 }
        val nodes = routeNodes(points)
        return referenceWindow.withIndex().all { (part, reference) ->
            val node = nodes.minByOrNull { abs(it.meters - part * 100) }
            node != null && abs(node.meters - part * 100) <= 25 && Metrics.distance(node.point, reference) <= 35
        }
    }

    private fun drift(run: Run, hr: Int?): Pair<CueKind, String>? {
        val t = run.activeSeconds
        if (t < 480 || hr == null || plan.intense) return null
        val review = driftReviewAt
        if (review != null && t >= review) {
            driftReviewAt = null
            val dropped = hr <= (driftAtCue ?: hr) - 4
            if (dropped) driftEpisode = false
            return if (dropped) CueKind.RECOVERY to "Dein Puls ist gesunken. Halte den ruhigeren Rhythmus."
                else CueKind.EFFORT to "Der Puls ist noch nicht gesunken. Lass den restlichen Lauf ruhig ausklingen."
        }
        if (driftEpisode && hr <= (driftAtCue ?: hr) - 4) {
            driftEpisode = false
            return CueKind.RECOVERY to "Dein Puls ist inzwischen gesunken. Halte den ruhigeren Rhythmus."
        }
        if (t < slowDriftCheck || driftEpisode) return null
        slowDriftCheck = t + 10
        val current = CoachingEvidence.time(run, t - 182, t - 2)
        val older = CoachingEvidence.time(run, t - 362, t - 182)
        val previousHr = older?.hr
        val currentHr = current?.hr
        val rising = previousHr != null && currentHr != null && currentHr - previousHr >= 8 &&
            current.pace >= older.pace / 1.03
        if (!rising) { slowDriftSince = null; return null }
        if (slowDriftSince == null) slowDriftSince = t
        if (t - slowDriftSince!! < 60) return null
        slowDriftSince = null
        driftEpisode = true
        driftReviewAt = t + 120
        driftAtCue = hr
        return CueKind.EFFORT to "Dein Puls steigt seit ein paar Minuten bei ähnlichem oder ruhigerem Tempo. Nimm etwas Tempo heraus. Ich prüfe in zwei Minuten erneut."
    }
}
