package de.laufklar.app

import kotlin.math.abs
import kotlin.math.roundToInt

enum class ComparisonKind {
    FASTER_AT_SIMILAR_PULSE, LOWER_PULSE_AT_SIMILAR_PACE, FASTER_HIGHER_PULSE,
    SLOWER_LOWER_PULSE, SLOWER_HIGHER_PULSE, SLOWER_SIMILAR_PULSE, SIMILAR_PACE_HIGHER_PULSE, SIMILAR
}

data class ComparedSection(val startMeters: Double, val endMeters: Double, val current: Section, val previous: Section)
data class RunComparison(
    val referenceId: String, val referenceDate: Long, val sections: List<ComparedSection>,
    val pace: Double, val previousPace: Double, val hr: Double, val previousHr: Double,
    val kind: ComparisonKind, val referenceDistance: Double, val referenceActiveSeconds: Double,
    val referenceTotalSeconds: Double, val sameFullRoute: Boolean, val version: Int = 1, val referenceRevision: Long = 1
) {
    val paceDelta get() = (pace - previousPace).roundToInt()
    val hrDelta get() = (hr - previousHr).roundToInt()

    fun text(): String {
        val tempo = if (paceDelta == 0) "gleich schnell" else
            "${abs(paceDelta)} Sekunden pro Kilometer ${if (paceDelta < 0) "schneller" else "langsamer"}"
        val pulse = if (hrDelta == 0) "gleichem Puls" else
            "${abs(hrDelta)} Schlägen ${if (hrDelta < 0) "weniger" else "mehr"} Puls"
        val verdict = when (kind) {
            ComparisonKind.FASTER_AT_SIMILAR_PULSE, ComparisonKind.LOWER_PULSE_AT_SIMILAR_PACE -> "Ein günstiger Vergleich für heute."
            ComparisonKind.FASTER_HIGHER_PULSE -> "Heute war die Belastung höher. Der Vergleich zeigt keinen eindeutigen Effizienzgewinn."
            ComparisonKind.SLOWER_LOWER_PULSE -> "Heute ruhiger unterwegs."
            ComparisonKind.SLOWER_HIGHER_PULSE -> "Die Ursache für den höheren Puls bleibt offen."
            ComparisonKind.SLOWER_SIMILAR_PULSE -> "Heute etwas langsamer bei ähnlichem Puls."
            ComparisonKind.SIMILAR_PACE_HIGHER_PULSE -> "Heute ähnliches Tempo bei höherem Puls."
            ComparisonKind.SIMILAR -> "Ein ähnliches Ergebnis."
        }
        return "Auf ${Metrics.spokenKm(sections.sumOf { it.current.meters })} vergleichbaren Kilometern warst du $tempo, bei $pulse. $verdict"
    }
}

data class ComparisonEvaluation(val result: RunComparison? = null, val reason: String = "", val favorableRuns: Int = 0)

object ComparisonEngine {
    fun classify(paceDelta: Double, hrDelta: Double): ComparisonKind = when {
        paceDelta < -10 && hrDelta > 3 -> ComparisonKind.FASTER_HIGHER_PULSE
        paceDelta < -10 -> ComparisonKind.FASTER_AT_SIMILAR_PULSE
        paceDelta > 10 && hrDelta < -3 -> ComparisonKind.SLOWER_LOWER_PULSE
        paceDelta > 10 && hrDelta > 3 -> ComparisonKind.SLOWER_HIGHER_PULSE
        paceDelta > 10 -> ComparisonKind.SLOWER_SIMILAR_PULSE
        hrDelta < -3 -> ComparisonKind.LOWER_PULSE_AT_SIMILAR_PACE
        hrDelta > 3 -> ComparisonKind.SIMILAR_PACE_HIGHER_PULSE
        else -> ComparisonKind.SIMILAR
    }

    fun evaluate(run: Run, history: List<Run>, trend: Boolean = false): ComparisonEvaluation {
        if (run.unusualConditions)
            return ComparisonEvaluation(reason = "Ungewöhnliche Bedingungen. Dieses Mal bleiben die Laufdaten ohne Leistungsvergleich.")
        if (run.readiness != Readiness.READY.name)
            return ComparisonEvaluation(reason = "Heute zählt die Tagesform. Kein Vergleich mit erholten Läufen.")
        val candidates = history.asSequence().filter { old ->
            old.id != run.id && old.started < run.started && old.ended != null && !old.recovered &&
                old.mode == run.mode && !old.unusualConditions && compatibleTargets(run, old) && Metrics.sameReadiness(run, old) && old.hasTempoWork() == run.hasTempoWork()
        }.sortedByDescending { it.started }.take(12).toList()
        if (candidates.isEmpty()) return ComparisonEvaluation(reason = "Ein früherer Lauf mit passender Tagesform und passendem Auftrag fehlt.")
        if (run.mode != RunMode.OUTDOOR)
            return ComparisonEvaluation(reason = "Für Laufbandvergleiche fehlt ein bestätigtes gleiches Strecken- und Steigungsprotokoll.")
        if (run.hasTempoWork()) return ComparisonEvaluation(reason = "Tempoabschnitte werden als eigene Missionen ausgewertet.")
        val matches = if (trend) candidates.mapNotNull { compare(run, it) }.take(3) else
            listOfNotNull(candidates.firstNotNullOfOrNull { compare(run, it) })
        val latest = matches.firstOrNull()
            ?: return ComparisonEvaluation(reason = "Es fehlen mindestens 500 Meter und drei Minuten auf derselben Strecke mit durchgängigen Tempo- und Pulsdaten.")
        val favorable = if (matches.size == 3) matches.count {
            it.kind == ComparisonKind.FASTER_AT_SIMILAR_PULSE || it.kind == ComparisonKind.LOWER_PULSE_AT_SIMILAR_PACE
        } else 0
        return ComparisonEvaluation(latest, favorableRuns = favorable)
    }

    private fun compatibleTargets(run: Run, old: Run): Boolean {
        val a = run.sessionPlan
        val b = old.sessionPlan
        // Old quiet runs are still valid evidence when the route and recorded phase qualify.
        if (a == null || b == null) return true
        if (a.version != b.version) return false
        val personal = setOf(TargetSource.USER_CALIBRATED_THRESHOLD, TargetSource.USER_DEFINED_UNVERIFIED)
        return if (a.source in personal || b.source in personal) a.source == b.source && run.zoneBounds == old.zoneBounds else true
    }

    private fun compare(run: Run, reference: Run): RunComparison? {
        val currentNodes = routeNodes(run.points)
        val previousNodes = routeNodes(reference.points)
        if (currentNodes.size < 20 || previousNodes.size < 20 ||
            Metrics.distance(currentNodes.first().point, previousNodes.first().point) > 100) return null
        val sections = mutableListOf<ComparedSection>()
        var referenceFloor = 0
        val block = if ((run.pace ?: 360.0) < 375 || (reference.pace ?: 360.0) < 375) 750 else 500
        for (meters in 0 until currentNodes.last().meters.toInt() - block + 1 step block) {
            val probes = (0..5).map { n -> closest(currentNodes, meters + n * block / 5.0) }
            if (probes.any { it == null }) continue
            val start = probes.first()!!
            val end = probes.last()!!
            if (start.point.segment != end.point.segment) continue
            if (!CoachingEvidence.quiet(run, start.point.elapsed, end.point.elapsed)) continue
            val current = CoachingEvidence.time(run, start.point.elapsed, end.point.elapsed) ?: continue
            if (current.seconds < 180 || current.hr == null) continue
            val starts = previousNodes.indices.filter { i ->
                i >= referenceFloor && abs(previousNodes[i].meters - start.meters) <= 150 &&
                    Metrics.distance(previousNodes[i].point, start.point) <= 30
            }.sortedBy { Metrics.distance(previousNodes[it].point, start.point) }
            for (i in starts) {
                val first = previousNodes[i]
                var index = i
                var aligned = true
                for (probe in probes.drop(1).filterNotNull()) {
                    val expected = first.meters + probe.meters - start.meters
                    val next = (index + 1 until previousNodes.size).asSequence()
                        .dropWhile { previousNodes[it].meters < expected - 35 }
                        .takeWhile { previousNodes[it].meters <= expected + 35 }
                        .filter { previousNodes[it].point.segment == first.point.segment }
                        .minByOrNull { Metrics.distance(previousNodes[it].point, probe.point) }
                    if (next == null || Metrics.distance(previousNodes[next].point, probe.point) > 30) {
                        aligned = false
                        break
                    }
                    index = next
                }
                if (!aligned) continue
                val last = previousNodes[index]
                if (!CoachingEvidence.quiet(reference, first.point.elapsed, last.point.elapsed)) continue
                val old = CoachingEvidence.time(reference, first.point.elapsed, last.point.elapsed) ?: continue
                if (old.seconds < 180 || old.hr == null || abs(old.meters / current.meters - 1) > .06) continue
                sections += ComparedSection(start.meters, end.meters, current, old)
                referenceFloor = index
                break
            }
        }
        if (sections.sumOf { it.current.meters } < 500 || sections.sumOf { it.previous.meters } < 500) return null
        val a = sections.map { it.current }
        val b = sections.map { it.previous }
        fun pace(values: List<Section>) = values.sumOf { it.seconds } * 1000 / values.sumOf { it.meters }
        fun hr(values: List<Section>): Double {
            val covered = values.sumOf { it.seconds * it.coverage }
            return values.sumOf { it.hr!! * it.seconds * it.coverage } / covered
        }
        val sameFull = abs(run.distance - reference.distance) <= 50 &&
            Metrics.distance(currentNodes.last().point, previousNodes.last().point) <= 35 &&
            (0..20).all { n ->
                val p = closest(currentNodes, currentNodes.last().meters * n / 20)
                val q = closest(previousNodes, previousNodes.last().meters * n / 20)
                p != null && q != null && Metrics.distance(p.point, q.point) <= 35
            }
        return RunComparison(reference.id, reference.started, sections, pace(a), pace(b), hr(a), hr(b),
            classify(pace(a) - pace(b), hr(a) - hr(b)), reference.distance, reference.activeSeconds,
            (reference.ended!! - reference.started) / 1000.0, sameFull, referenceRevision = reference.revision)
    }

    private fun closest(nodes: List<RouteNode>, meters: Double): RouteNode? {
        val index = nodes.binarySearch { it.meters.compareTo(meters) }
        if (index >= 0) return nodes[index]
        val i = -index - 1
        return listOfNotNull(nodes.getOrNull(i - 1), nodes.getOrNull(i)).minByOrNull { abs(it.meters - meters) }
            ?.takeIf { abs(it.meters - meters) <= 25 }
    }
}

enum class SummarySpeech { AVAILABLE, ATTEMPTED, PLAYED }
enum class RecoveryMethod(val label: String) {
    UNKNOWN("Nicht angegeben"), WALKING_SELF_REPORTED("Gehend"), STANDING_SELF_REPORTED("Stehend")
}

data class RoundFacts(val id: String, val date: Long, val distance: Double, val active: Double, val total: Double)
data class RecoveryComparison(val date: Long, val dropBpm: Int)

data class RunSummary(
    val id: String, val distance: Double, val activeSeconds: Double, val totalSeconds: Double,
    val comparison: RunComparison?, val comparisonReason: String, val favorableRuns: Int,
    val cooldownDrop: Int?, val cooldownDelaySeconds: Int?, val recovery: RecoveryMethod,
    val next: String, val audio: String, val speech: SummarySpeech = SummarySpeech.AVAILABLE,
    val version: Int = 1, val lastRound: RoundFacts? = null, val cooldownComparison: RecoveryComparison? = null
)

object SummaryBuilder {
    fun build(run: Run, history: List<Run>): RunSummary {
        val evaluation = ComparisonEngine.evaluate(run, history, trend = true)
        val cooldown = run.cooldown
        val earlier = history.filter { it.id != run.id && it.started < run.started && it.ended != null && !it.recovered }
            .sortedByDescending { it.started }
        val lastRound = earlier.firstOrNull()?.let {
            RoundFacts(it.id, it.started, it.distance, it.activeSeconds, (it.ended!! - it.started) / 1000.0)
        }
        val delay = cooldown?.let { ((it.started - (run.ended ?: it.started)) / 1000).toInt().coerceAtLeast(0) }
        val cooldownComparison = if (cooldown?.dropBpm != null && cooldown.recovery != RecoveryMethod.UNKNOWN &&
            delay != null && run.readiness == Readiness.READY.name && !run.unusualConditions) {
            earlier.firstNotNullOfOrNull { reference ->
                val prior = reference.cooldown ?: return@firstNotNullOfOrNull null
                val drop = prior.dropBpm ?: return@firstNotNullOfOrNull null
                val priorDelay = ((prior.started - reference.ended!!) / 1000).toInt().coerceAtLeast(0)
                if (reference.mode == run.mode && !reference.unusualConditions && Metrics.sameReadiness(run, reference) &&
                    reference.hasTempoWork() == run.hasTempoWork() && prior.recovery == cooldown.recovery &&
                    abs(prior.startBpm - cooldown.startBpm) <= 5 && abs(priorDelay - delay) <= 10) RecoveryComparison(reference.started, drop)
                else null
            }
        } else null
        val total = ((run.ended ?: run.started) - run.started) / 1000.0
        val finished = run.missionAttempts.filter { it.state.terminal && it.startedAt != null }
        val last = finished.lastOrNull()
        val success = finished.lastOrNull { it.state == MissionState.SUCCEEDED }
        val next = when {
            run.readiness != Readiness.READY.name -> "Gib der Erholung Vorrang."
            run.hasTempoWork() -> "Beim nächsten Lauf sammeln wir ruhige Ausdauer."
            last?.state == MissionState.NOT_MET -> "Beim nächsten Versuch halten wir das Tempo gleichmäßiger."
            success != null -> "Beim nächsten ruhigen Lauf knüpfen wir an diesen Rhythmus an."
            else -> "Ergänze dein Belastungsgefühl für den nächsten Trainingsvorschlag."
        }
        val distance = Metrics.spokenKm(run.distance)
        val minutes = (run.activeSeconds / 60).toInt()
        val seconds = (run.activeSeconds % 60).toInt()
        val duration = if (minutes == 0) "$seconds Sekunden" else "$minutes Minuten und $seconds Sekunden"
        val mission = if (finished.isEmpty()) "Heute ohne ausgewertete Aufgabe."
            else finished.joinToString(" ") { "${it.id.title}. ${it.state.label}." }
        val comparison = evaluation.result?.let {
            val pace = it.paceDelta
            val hr = it.hrDelta
            val tempo = if (pace == 0) "gleich schnell" else "${abs(pace)} Sekunden pro Kilometer ${if (pace < 0) "schneller" else "langsamer"}"
            val pulse = if (hr == 0) "gleichem Puls" else "${abs(hr)} Schlägen ${if (hr < 0) "weniger" else "mehr"} Puls"
            val date = java.time.Instant.ofEpochMilli(it.referenceDate).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d. MMMM", java.util.Locale.GERMANY))
            val qualification = if (it.kind == ComparisonKind.FASTER_HIGHER_PULSE) " Heute war die Belastung höher." else ""
            "Gegenüber dem Lauf vom $date auf passenden Abschnitten $tempo bei $pulse.$qualification"
        } ?: "Heute fehlt ein passender Vergleich."
        val recovery = cooldown?.dropBpm?.let {
            if (it >= 0) "Nach dem Speichern sank dein Puls in einer Minute um $it Schläge."
            else "Nach dem Speichern stieg der Puls in einer Minute um ${-it} Schläge."
        }.orEmpty()
        val pauseSeconds = (total - run.activeSeconds).roundToInt().coerceAtLeast(0)
        val pause = if (pauseSeconds >= 15) "Dazu $pauseSeconds Sekunden Pause." else ""
        val audio = listOf("$distance Kilometer in $duration aktiver Zeit.", pause, mission, comparison, recovery, next)
            .filter { it.isNotBlank() }.joinToString(" ")
        return RunSummary("${run.id}:summary", run.distance, run.activeSeconds, total, evaluation.result,
            evaluation.reason, evaluation.favorableRuns, cooldown?.dropBpm, delay,
            cooldown?.recovery ?: RecoveryMethod.UNKNOWN, next, audio, lastRound = lastRound, cooldownComparison = cooldownComparison)
    }

    fun badges(history: List<Run>): List<String> {
        val groups = listOf(
            "Rhythmus gefunden" to setOf(MissionId.M01, MissionId.M02),
            "Belastung gesteuert" to setOf(MissionId.M03, MissionId.M04, MissionId.M06),
            "Tempo kontrolliert" to setOf(MissionId.M07, MissionId.M09, MissionId.M10)
        )
        return groups.filter { (_, ids) ->
            history.filter { !it.recovered && it.missionAttempts.any { m -> m.id in ids && m.state == MissionState.SUCCEEDED } }
                .map { java.time.Instant.ofEpochMilli(it.started).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                .distinct().size >= 3
        }.map { it.first }
    }
}
