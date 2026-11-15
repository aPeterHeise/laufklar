package de.laufklar.app

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class MissionId(val title: String, val seconds: Int, val intense: Boolean = false) {
    M01("Gleichmäßiger Kilometer", 0), M02("Rhythmus halten", 180),
    M03("Ruhiger Motor", 180), M04("Einen Gang zurück", 180),
    M05("Referenzstück", 0), M06("Mit Reserve ins Ziel", 180),
    M07("Kontrollierte Steigerung", 360, true), M08("Kurze Steigerungen", 360, true),
    M09("Wechselspiel", 600, true), M10("Tempo halten", 720, true);

    fun duration(pace: Int?) = if (seconds > 0) seconds else
        ((pace ?: 480) * if (this == M05) .6 else 1.0).roundToInt()

    fun phases(extra: Int = 0): List<MissionPhase> = when (this) {
        M07 -> listOf(MissionPhase(120), MissionPhase(120, true), MissionPhase(120))
        M08 -> repeatPhases(4 + extra, 15, 75)
        M09 -> repeatPhases(4 + extra, 60, 90)
        M10 -> repeatPhases(3 + extra, 120, 120)
        else -> emptyList()
    }

    private fun repeatPhases(count: Int, fast: Int, recovery: Int) =
        List(count * 2) { i -> MissionPhase(if (i % 2 == 0) fast else recovery, i % 2 == 0) }
}

data class MissionPhase(val seconds: Int, val fast: Boolean = false)
enum class MissionState(val label: String, val terminal: Boolean = true) {
    PLANNED("Geplant", false), OFFERED("Angekündigt", false), ACTIVE("Läuft", false),
    SUSPENDED("Unterbrochen", false), SUCCEEDED("Geschafft"), PARTIAL("Teilweise erfüllt"),
    NOT_MET("Ziel noch offen"), UNMEASURABLE("Nicht messbar"), ABORTED("Übersprungen")
}

data class MissionAttempt(
    val id: MissionId, val state: MissionState = MissionState.PLANNED,
    val startedAt: Double? = null, val startedMeters: Double? = null,
    val endedAt: Double? = null, val endedMeters: Double? = null,
    val pace: Double? = null, val result: String = "", val phase: Int = -1,
    val firstPace: Double? = null, val secondPace: Double? = null,
    val measuredFraction: Double? = null, val hrChange: Int? = null,
    val segment: Int? = null, val version: Int = 1
)

data class SessionPlan(
    val intense: Boolean, val warmup: Int, val openingPace: Int?, val pulseTarget: PulseTarget?,
    val source: TargetSource, val quietPace: Int?, val tempoPace: Int?, val seconds: Int?,
    val missions: List<MissionId>, val seed: Long, val selectionReason: String,
    val learning: LearningSnapshot, val extraRepetitions: Int = 0, val version: Int = 1
) {
    val title get() = if (intense) "Kontrolliertes Tempo" else "Ruhige Ausdauer"
}

fun Run.hasTempoWork(): Boolean = workout == Workout.INTERVALS ||
    missionAttempts.any { it.id.intense && it.startedAt != null }

object MissionPlanner {
    fun create(profile: Profile, history: List<Run>, mode: RunMode, readiness: Readiness,
               workout: Workout, confirmedMinutes: Int?, openPath: Boolean, now: Long): SessionPlan {
        val learning = LearningEvaluator.evaluate(history, mode, now)
        val day = AdaptiveTrainer.plan(profile, history, mode, readiness, now)
        val pace = profile.easyPaceOverride ?: learning.observedPace
        val seed = (history.filter { it.ended != null }.maxOfOrNull { it.started } ?: 17L) xor
            (mode.ordinal.toLong() shl 4) xor workout.ordinal.toLong()
        val budget = confirmedMinutes?.times(60)
        val intense = workout == Workout.INTERVALS && day.qualityAllowed &&
            budget != null && budget >= 1260 && learning.observedPace != null
        val target = LearningEvaluator.target(profile, learning)
        val past = history.sortedByDescending { it.started }
        val previous = past.mapNotNull { it.sessionPlan?.missions?.firstOrNull() }.take(2).toSet()
        val options = when {
            intense -> listOf(MissionId.M07, MissionId.M08, MissionId.M09, MissionId.M10).filter {
                it.seconds + 690 + 300 <= budget!! && (it != MissionId.M08 || openPath)
            }
            readiness != Readiness.READY || day.restRecommended -> listOf(MissionId.M02, MissionId.M04)
            else -> buildList {
                addAll(listOf(MissionId.M01, MissionId.M02, MissionId.M04))
                if (target != null) add(MissionId.M03)
                if (past.any { it.mode == mode && it.readiness == readiness.name && it.points.size >= 20 }) add(MissionId.M05)
            }
        }
        val choices = options.filterNot { it in previous }.ifEmpty { options }.ifEmpty { listOf(MissionId.M02) }
        val weighted = choices.flatMap { id ->
            List(if (!intense && id == MissionId.M05 && learning.runIds.size < 3) 3 else 1) { id }
        }
        val primary = weighted.random(Random(seed))
        val completed = past.filter {
            it.mode == mode && it.readiness == Readiness.READY.name && it.rpe in 2..7 && it.missionFeedback != MissionFeedback.TOO_HARD &&
                it.coachEvents.none { event -> event.kind == CueKind.EFFORT } &&
                it.missionAttempts.any { m -> m.id == primary && m.state == MissionState.SUCCEEDED }
        }.map { localTrainingDay(it.started) }.distinct().size
        val extraSeconds = when (primary) { MissionId.M09 -> 150; MissionId.M10 -> 240; else -> 0 }
        val extra = if (intense && completed >= 2 && extraSeconds > 0 &&
            primary.seconds + extraSeconds + 990 <= budget!!) 1 else 0
        val priorPace = past.firstNotNullOfOrNull { run ->
            run.sessionPlan?.tempoPace?.takeIf { run.mode == mode && run.missionAttempts.any { it.id == primary && it.state == MissionState.SUCCEEDED } }
        }
        val tempoPace = if (extra > 0) priorPace ?: day.targetPace else day.targetPace
        return SessionPlan(intense, if (intense) 600 else 480, pace?.plus(20),
            target, LearningEvaluator.source(profile), pace, tempoPace.takeIf { intense }, budget,
            if (intense) listOf(primary) else listOf(primary, MissionId.M06), seed,
            if (intense) "Geplante Tempoeinheit" else if (learning.runIds.size < 3) "Ruhige Referenz sammeln" else "Ruhige Aufgabe im Wechsel",
            learning, extra)
    }

    private fun localTrainingDay(timestamp: Long) = java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
}

/** A mission owns one fixed window. A later window cannot change its result. */
class MissionRunner(private val plan: SessionPlan) {
    val attempts = mutableListOf<MissionAttempt>()
    var current: MissionAttempt? = null
        private set
    private var lastCorrection = -100.0
    private var outsideSince: Double? = null
    private var middleSaid = false
    private var recoveryOnly = false
    private var startHr: Double? = null
    private var phaseStarted = 0.0
    private var fastPasses = 0
    private var fastMeasured = 0

    fun start(id: MissionId, run: Run, pace: Double?, hr: Double?): String {
        lastCorrection = -100.0
        outsideSince = null
        middleSaid = false
        recoveryOnly = false
        startHr = hr
        phaseStarted = run.activeSeconds
        fastPasses = 0
        fastMeasured = 0
        val startsFast = id.phases(plan.extraRepetitions).firstOrNull()?.fast == true
        current = MissionAttempt(id, MissionState.ACTIVE, run.activeSeconds, run.meters, pace = pace,
            phase = if (id.intense) 0 else -1, segment = run.points.lastOrNull()?.segment)
        val tempo = pace?.let { Metrics.pace(it) }.orEmpty()
        return when (id) {
            MissionId.M01 -> "Ein gleichmäßiger Kilometer. Halte ungefähr $tempo. Ich melde mich nach 500 Metern."
            MissionId.M02 -> "Drei Minuten im Rhythmus, ungefähr $tempo. Halte dieses Tempo."
            MissionId.M03 -> "Ruhiger Motor. Drei Minuten ruhig, Puls höchstens ${plan.pulseTarget?.high}. Unter dem Bereich musst du nicht schneller werden."
            MissionId.M04 -> "Einen Gang zurück. Drei Minuten etwas ruhiger, ungefähr $tempo. Ich prüfe danach, wie dein Puls reagiert."
            MissionId.M05 -> "Ein Referenzstück. Sechshundert Meter im bisherigen Tempo von ungefähr $tempo."
            MissionId.M06 -> "Mit Reserve ins Ziel. Drei Minuten im ruhigen Rhythmus. Lass den Endspurt aus."
            else -> "${id.title}. " + if (startsFast) fastCue(id, plan.tempoPace, id.phases(plan.extraRepetitions).first().seconds)
                else "Erst zwei Minuten ruhig, danach kontrolliert zügiger."
        }
    }

    fun finish(run: Run, state: MissionState, message: String): String {
        val value = current ?: return message
        attempts += value.copy(state = state, endedAt = run.activeSeconds, endedMeters = run.meters, result = message)
        current = null
        return message
    }

    fun interrupt(run: Run, reason: String): String? {
        val value = current ?: return null
        return finish(run, if (value.id.intense) MissionState.PARTIAL else MissionState.UNMEASURABLE, reason)
    }

    fun update(run: Run, hr: Int?): String? {
        val attempt = current ?: return null
        if (attempt.segment != null && attempt.segment != run.points.lastOrNull()?.segment) {
            return interrupt(run, "Die Strecke ist unterbrochen. Die Aufgabe lässt sich heute nicht auswerten.")
        }
        val start = attempt.startedAt!!
        val elapsed = run.activeSeconds - start
        return if (attempt.id.intense) tempo(run, attempt, hr, elapsed) else quiet(run, attempt, elapsed, hr)
    }

    private fun quiet(run: Run, attempt: MissionAttempt, elapsed: Double, hr: Int?): String? {
        val start = attempt.startedAt!!
        val meters = attempt.startedMeters!!
        val length = when (attempt.id) { MissionId.M01 -> 1000; MissionId.M05 -> 600; else -> 0 }
        val traveled = run.meters - meters
        val first = if (attempt.id == MissionId.M01 && traveled >= 500)
            CoachingEvidence.distance(run, meters, meters + 500) else null
        val complete = if (length > 0) traveled >= length else elapsed >= attempt.id.seconds
        if (complete) {
            val whole = (if (length > 0) CoachingEvidence.distance(run, meters, meters + length)
                else CoachingEvidence.time(run, start, start + attempt.id.seconds))
                ?: return if (length == 0 && elapsed < attempt.id.seconds + 10) null
                else finish(run, MissionState.UNMEASURABLE, "Für diese Aufgabe fehlen durchgängige Bewegungsdaten.")
            val second = if (first != null) CoachingEvidence.distance(run, meters + 500, meters + 1000) else null
            if (attempt.id == MissionId.M03 && whole.hr == null)
                return finish(run, MissionState.UNMEASURABLE, "Für Ruhiger Motor fehlen Pulswerte.")
            val fraction = CoachingEvidence.bandFraction(run, whole.from + if (attempt.id == MissionId.M03) 60 else 0, whole.until) {
                when (attempt.id) {
                    MissionId.M03 -> it.hr?.let { value -> value <= plan.pulseTarget!!.high } == true
                    MissionId.M04, MissionId.M06 -> attempt.pace?.let { target -> it.pace >= target - 15 } == true
                    else -> attempt.pace?.let { target -> abs(it.pace - target) <= 15 } == true
                }
            }
            val gap = if (first != null && second != null) (second.pace - first.pace).roundToInt() else null
            val ok = fraction >= .8 && (attempt.id != MissionId.M01 || gap != null && abs(gap) <= 15)
            val endHr = if (attempt.id == MissionId.M04) CoachingEvidence.time(run, whole.until - 45, whole.until)?.hr else null
            val delta = endHr?.let { value -> startHr?.let { (value - it).roundToInt() } }
            current = attempt.copy(firstPace = first?.pace, secondPace = second?.pace, measuredFraction = fraction, hrChange = delta)
            val detail = when {
                gap != null -> "Zweite Hälfte ${abs(gap)} Sekunden pro Kilometer ${if (gap < 0) "schneller" else "langsamer"}."
                delta != null -> "Der Puls liegt jetzt ${abs(delta)} Schläge ${if (delta < 0) "niedriger" else "höher"}."
                else -> ""
            }
            val message = if (ok) "${attempt.id.title} geschafft. $detail" else "${attempt.id.title}: Ziel heute noch offen. $detail"
            return finish(run, if (ok) MissionState.SUCCEEDED else MissionState.NOT_MET, message.trim()).also {
                attempts[attempts.lastIndex] = attempts.last().copy(endedAt = whole.until,
                    endedMeters = if (length > 0) meters + length else run.meters)
            }
        }
        val midpoint = if (length > 0) traveled >= length / 2 else elapsed >= 90
        if (midpoint && !middleSaid) {
            middleSaid = true
            return when {
                first != null -> "Halbzeit. Erste Hälfte ${Metrics.pace(first.pace)}. Halte diesen Rhythmus."
                attempt.id == MissionId.M04 -> "Du bist in der ruhigeren Phase. Bleib dabei, ich prüfe gleich den Puls."
                else -> "Halbzeit bei ${attempt.id.title.lowercase()}. Halte den vereinbarten Rhythmus."
            }
        }
        if (elapsed < 30 || length > 0 && traveled < 200 || run.activeSeconds - lastCorrection < 60) return null
        val recent = CoachingEvidence.time(run, run.activeSeconds - 32, run.activeSeconds - 2) ?: return null
        if (length > 0 && recent.meters < 75) return null
        val target = first?.pace ?: attempt.pace
        val direction = when {
            attempt.id == MissionId.M03 && recent.hr?.let { it > plan.pulseTarget!!.high } == true -> -1
            attempt.id != MissionId.M03 && target != null && recent.pace < target - 15 -> -1
            hr != null && (plan.pulseTarget?.let { hr < it.high - 2 } ?: true) &&
                attempt.id in listOf(MissionId.M01, MissionId.M02, MissionId.M05) && target != null && recent.pace > target + 15 -> 1
            else -> 0
        }
        if (direction == 0) { outsideSince = null; return null }
        if (outsideSince == null) outsideSince = run.activeSeconds
        if (run.activeSeconds - outsideSince!! < 20) return null
        lastCorrection = run.activeSeconds
        outsideSince = null
        return if (direction < 0) "Du bist aus dem Zielbereich. Nimm etwas Tempo heraus und halte den Rhythmus."
            else "Du bist unter dem vereinbarten Tempo. Wenn es sich noch locker anfühlt, finde zurück in den Rhythmus."
    }

    private fun tempo(run: Run, attempt: MissionAttempt, hr: Int?, elapsed: Double): String? {
        val phases = attempt.id.phases(plan.extraRepetitions)
        var end = 0
        val index = phases.indexOfFirst { phase -> end += phase.seconds; elapsed < end }
        val previous = phases.getOrNull(attempt.phase)
        if (previous?.fast == true && hr == null) {
            return finish(run, MissionState.PARTIAL, "Frischer Puls fehlt. Der schnelle Teil endet hier. Lauf locker weiter.")
        }
        if (index == attempt.phase) {
            if (previous?.fast == true && attempt.id != MissionId.M08 && elapsed > end - previous.seconds + 35 &&
                run.activeSeconds - lastCorrection >= 60) {
                val recent = CoachingEvidence.time(run, run.activeSeconds - 32, run.activeSeconds - 2)
                val target = if (attempt.id == MissionId.M07) attempt.pace?.minus(12) else plan.tempoPace?.toDouble()
                if (recent != null && target != null && recent.pace < target - 15) {
                    lastCorrection = run.activeSeconds
                    return "Etwas langsamer. Halte das vereinbarte Tempo."
                }
            }
            return null
        }
        if (previous?.fast == true && !recoveryOnly) {
            val window = CoachingEvidence.time(run, phaseStarted + if (attempt.id == MissionId.M08) 0 else 10, run.activeSeconds - 2)
            val target = if (attempt.id == MissionId.M07) attempt.pace?.minus(12) else plan.tempoPace?.toDouble()
            if (window != null) {
                fastMeasured++
                if (target != null && abs(window.pace - target) <= 20) fastPasses++
            }
        }
        if (index < 0) {
            val required = phases.count { it.fast }
            val state = when {
                recoveryOnly -> MissionState.PARTIAL
                fastMeasured < required -> MissionState.UNMEASURABLE
                attempt.id == MissionId.M08 -> MissionState.PARTIAL
                fastPasses == required -> MissionState.SUCCEEDED
                else -> MissionState.NOT_MET
            }
            val measured = if (attempt.id == MissionId.M08 && fastMeasured == required) "Vier Steigerungen begleitet. Bestätige nach dem Lauf die Ausführung."
                else if (attempt.id == MissionId.M08) "Für die Steigerungen fehlen verwertbare Bewegungsdaten."
                else "$fastPasses von $required schnellen Abschnitten im vereinbarten Tempo."
            current = attempt.copy(measuredFraction = fastMeasured.toDouble() / required)
            return finish(run, state, measured + " Jetzt locker weiter.")
        }
        current = attempt.copy(phase = index)
        phaseStarted = run.activeSeconds
        if (!phases[index].fast) return "Tempoabschnitt vorbei. Jetzt ${phases[index].seconds} Sekunden locker."
        if (hr == null) {
            recoveryOnly = true
            return finish(run, MissionState.PARTIAL, "Frischer Puls fehlt. Die schnellen Abschnitte enden hier. Bleib locker.")
        }
        val pace = if (attempt.id == MissionId.M07) attempt.pace?.minus(12)?.roundToInt() else plan.tempoPace
        return fastCue(attempt.id, pace, phases[index].seconds)
    }

    fun snapshot(): List<MissionAttempt> = attempts.toList() + listOfNotNull(current)

    private fun fastCue(id: MissionId, pace: Int?, seconds: Int): String =
        if (id == MissionId.M08) "Fünfzehn Sekunden flüssig beschleunigen, ohne Sprint."
        else "$seconds Sekunden kontrolliert zügig${pace?.let { ", Richtung ${Metrics.pace(it.toDouble())}" }.orEmpty()}."
}
