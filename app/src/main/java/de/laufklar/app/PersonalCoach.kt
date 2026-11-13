package de.laufklar.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class CueKind { START, PHASE, DISTANCE, EFFORT, RECOVERY, ROUTE, PROGRESS, TASK }
data class CoachEvent(val elapsed: Double, val kind: CueKind, val text: String)
data class EffortCue(val text: String, val recovered: Boolean = false)

/** Budgeted interventions with recovery feedback. A frequent pulse never becomes a learned safe limit. */
class EffortMonitor {
    private var aboveSince: Double? = null
    private var belowSince: Double? = null
    private var lastWarning = -1_000.0
    private var lastWarnedHr = 0
    private var explanationCount = 0
    private var reminders = 0
    private var lastWall: Long? = null
    var target: Int? = null; private set
    var settlingUntil = 0.0; private set
    val active get() = target != null

    fun update(run: Run, profile: Profile, now: Long, intense: Boolean): EffortCue? {
        if (lastWall?.let { now - it > 10_000 } == true) { aboveSince = null; belowSince = null }
        lastWall = now
        val hr = Metrics.freshHr(run.pulses, now)
        if (hr == null) { aboveSince = null; belowSince = null; return null }
        val seconds = run.activeSeconds
        val older = run.pulses.filter { it.elapsed in seconds - 180..seconds - 90 && now - it.time in 90_000..200_000 }.map { it.bpm }
        val currentPace = Metrics.recentPace(run.motion, seconds)
        val oldPace = Metrics.recentPace(run.motion.filter { it.elapsed <= seconds - 90 }, seconds - 90)
        val drift = !intense && seconds >= 480 && older.size >= 30 && hr - older.average() >= 12 && currentPace != null && oldPace != null && currentPace >= oldPace - 10
        val limit = if (intense || profile.estimated) profile.bounds[4] else profile.bounds[2]
        val above = hr >= limit || drift
        if (above) { if (aboveSince == null) aboveSince = seconds } else aboveSince = null
        val goal = target
        if (goal != null && hr <= goal) {
            if (belowSince == null) belowSince = seconds
            if (seconds - belowSince!! >= 20) {
                target = null; belowSince = null; aboveSince = null; reminders = 0; settlingUntil = seconds + 180
                return EffortCue("Gut, dein Puls ist gesunken. Halte das ruhigere Tempo erst einmal.", recovered = true)
            }
        } else belowSince = null
        val sustained = aboveSince?.let { seconds - it >= 30 } == true
        val risingAgain = hr >= lastWarnedHr + 8
        val due = seconds - lastWarning >= if (active) 180 else 120
        if (!sustained || !due || (active && reminders >= 2 && !risingAgain)) return null
        // After two reminders, only a clear new rise or a new episode merits another interruption.
        if (active && explanationCount >= 2 && !risingAgain && seconds - lastWarning < 240) return null
        val responseTarget = if (!profile.estimated && !drift) limit - 4 else hr - 8
        if (target == null) target = responseTarget
        lastWarning = seconds; lastWarnedHr = hr; reminders++; settlingUntil = seconds + 180
        val text = when (explanationCount++) {
            0 -> if (profile.estimated)
                "${if(drift)"Dein Puls steigt bei gleichem oder ruhigerem Tempo."else "Dein Puls bleibt hoch: $hr."} Nimm Tempo heraus, zunächst Richtung ${target} Schläge. Das ist ein vorläufiges Entlastungsziel; deine persönlichen Grenzen sind noch geschätzt."
                else "Puls $hr. Für diesen Abschnitt liegt dein Ziel unter $limit. Nimm Tempo heraus, bis du etwa ${target} erreichst."
            1 -> "Puls $hr. Noch etwas Tempo herausnehmen, Richtung ${target} Schläge. Ich melde mich, wenn der Puls wieder gesunken ist."
            else -> if (risingAgain) "Dein Puls steigt weiter auf $hr. Deutlich langsamer, bei Bedarf ein Stück gehen." else "Etwas langsamer."
        }
        return EffortCue(text)
    }
}

class Coach(history: List<Run> = emptyList()) {
    private val route = RouteMatcher(history)
    private val effort = EffortMonitor()
    private var nextDistance = 500.0
    private var previousPhase = -1
    private var lastCue = -1_000.0
    private var lastRouteCheck = -1_000.0
    private var match: RouteMatch? = null
    private var routeNamed = false
    private var finishApproach = false
    private var finishReached = false
    private var extensionExplained = false
    private var longSuggestion = false
    private var comparisonCount = 0
    private var positiveCount = 0
    private var lastComparisonMeters = -1_000.0
    private var taskStartedMeters: Double? = null
    private var taskComplete = false
    private var taskAdjusted = false
    private var recoveredAt: Double? = null
    private var missingPulseSaid = false
    var mission = "Nach dem Ankommen einen Kilometer gleichmäßig laufen."; private set
    var outcome = "Aufgabe noch offen."; private set
    var referenceRunId: String? = null; private set
    var events: List<CoachEvent> = emptyList(); private set

    fun reset(distance: Double, every: Int) { nextDistance = (floor(distance / every) + 1) * every }
    fun snapshot(run: Run) = run.copy(coachMission = mission, coachOutcome = outcome, referenceRunId = referenceRunId, coachEvents = events)
    fun finish(run: Run): Run {
        if (!taskComplete) outcome = when {
            run.workout == Workout.INTERVALS -> "Tempoeinheit aufgezeichnet. Belastungsgefühl ergänzt die Auswertung."
            taskAdjusted -> "Aufgabe auf Entlastung angepasst. Dein Pulsverlauf hat Vorrang."
            else -> "Runde aufgezeichnet. Die Gleichmäßigkeitsaufgabe bleibt für einen nächsten Lauf."
        }
        return snapshot(run)
    }
    private fun emit(run: Run, kind: CueKind, text: String): String {
        lastCue = run.activeSeconds
        if(kind==CueKind.PROGRESS)positiveCount++
        events = events + CoachEvent(run.activeSeconds, kind, text)
        return text
    }

    // Kept as a standalone explanation for tests and optional UI previews, never appended to every distance cue.
    fun guidance(hr: Int?, profile: Profile, intense: Boolean, pace: Double?, targetPace: Int? = profile.usualPace): String {
        if (hr == null) return "Kein verlässlicher Live-Puls. Heute keine Temposteigerung."
        val limit = if (intense || profile.estimated) profile.bounds[4] else profile.bounds[2]
        if (hr >= limit) return "Tempo reduzieren. Orientiere dich zunächst Richtung ${hr - 8} Schläge."
        if (!intense) return "Dein Rhythmus zählt. Du musst den Puls nicht hochjagen."
        val target = targetPace ?: return "Dein Ausgangstempo ist noch offen."
        return if (pace != null && pace < target - 10) "Etwas langsamer, Richtung ${Metrics.pace(target.toDouble())}." else "Kontrolliert zügig, Zielpace ${Metrics.pace(target.toDouble())}."
    }

    fun update(run: Run, profile: Profile, now: Long): String? {
        val phases = run.phases.ifEmpty { Training.fallback(run.workout) }
        val index = Training.phaseAt(phases, run.activeSeconds)
        val phase = phases.getOrNull(index)
        val hr = Metrics.freshHr(run.pulses, now)
        val normal = run.workout != Workout.INTERVALS
        val ready = run.readiness == Readiness.READY.name
        if (previousPhase < 0) {
            previousPhase = index
            if (!ready) { mission = "Heute ruhig unterwegs sein und auf Belastung achten."; taskAdjusted = true }
            if (!normal) mission = "Tempoabschnitte kontrolliert laufen und die Erholung nutzen."
            val introduction = when {
                !normal -> "Heute Tempo aufbauen. Erst zehn Minuten ankommen, dann ${phases.count{it.intense}} kontrollierte Tempoabschnitte. Ich sage jeden Wechsel an."
                !ready -> "Heute eine ruhige Runde. Kein Tempodruck. Ich begleite dich, bis du fertig bist."
                run.workout == Workout.LONG -> "Heute etwas länger unterwegs: etwa ${(run.plannedSeconds?:1800)/60} Minuten als Orientierung. Erst fünf Minuten ankommen, dann gleichmäßig weiter. Du entscheidest, wo deine Runde endet."
                else -> "Heute deine Runde. Erst fünf Minuten locker ankommen, dann probieren wir einen gleichmäßigen Kilometer. Ich begleite dich bis zum Ende deiner Strecke."
            }
            return emit(run, CueKind.START, introduction)
        }
        val effortCue = effort.update(run, profile, now, phase?.intense == true)
        if (effortCue != null) {
            if (!effortCue.recovered && normal) {
                taskAdjusted = true; taskStartedMeters = null;taskComplete=false;recoveredAt=null
                mission = "Tempo herausnehmen, danach den ruhigeren Rhythmus halten."
                outcome = "Aufgabe wegen erhöhtem oder steigendem Puls angepasst."
            }
            if(effortCue.recovered)recoveredAt=run.activeSeconds
            // A distance crossed at the same time is folded into the intervention, never repeated a second later.
            val distance = if (run.meters >= nextDistance) { val text = "${Metrics.spokenKm(floor(run.meters/profile.cueMeters)*profile.cueMeters)} Kilometer. "; reset(run.meters, profile.cueMeters); text } else ""
            return emit(run, if(effortCue.recovered)CueKind.RECOVERY else CueKind.EFFORT, distance + effortCue.text)
        }
        if (run.activeSeconds - lastCue < 18) return null
        if (index != previousPhase) {
            previousPhase = index
            if (normal && run.openEnded && phase?.duration == 0) {
                taskStartedMeters = run.meters
                return emit(run, CueKind.TASK, if(taskAdjusted)"Angekommen. Bleib jetzt im ruhigeren Rhythmus; deine Runde bleibt offen." else "Du bist angekommen. Jetzt ein Kilometer in möglichst gleichmäßigem Tempo. Ich schaue auf beide Hälften und melde dir, wie es läuft.")
            }
            val text = when {
                phase == null -> "Der geplante Trainingsteil ist geschafft. Wenn du noch unterwegs bist, begleite ich dich beim lockeren Weiterlaufen."
                phase.intense && (effort.active || hr == null) -> "Der nächste Tempoabschnitt wäre dran. Bleib noch im ruhigen Tempo. ${if(hr==null)"Für eine Temposteigerung fehlt frischer Puls."else "Die Entlastung hat gerade Vorrang."}"
                phase.intense -> "${phase.name}. ${phase.duration/60} Minuten kontrolliert zügig, Richtung ${Metrics.pace(run.targetPace.toDouble())} pro Kilometer."
                else -> "${phase.name}. ${phase.duration/60} Minuten. Nimm dir die Erholung."
            }
            return emit(run, CueKind.PHASE, text)
        }
        if (normal && run.openEnded && !longSuggestion && run.workout == Workout.LONG && run.activeSeconds >= (run.plannedSeconds?:1800)) {
            longSuggestion = true
            return emit(run, CueKind.TASK, "Dein vorgeschlagener Umfang ist erreicht. Wenn du noch nach Hause läufst, nimm den Rest ruhiger. Die Aufzeichnung läuft weiter.")
        }
        if (normal && run.activeSeconds - lastRouteCheck >= 10) {
            lastRouteCheck = run.activeSeconds; match = route.match(run)
            match?.let { referenceRunId = it.reference.id }
        }
        val routeMatch = match?.takeIf { now - (run.points.lastOrNull()?.time?:0) in 0..10_000 }
        if (normal && routeMatch != null && routeMatch.progress > 1_000) {
            if (!finishApproach && routeMatch.remaining in 100.0..550.0) {
                finishApproach = true
                val distance = (routeMatch.remaining/50).roundToInt()*50
                return emit(run, CueKind.ROUTE, "Noch ungefähr $distance Meter bis zum Ende deiner zuletzt aufgezeichneten Strecke. Bring die Runde ${if(effort.active)"ruhig"else "gleichmäßig"} zu Ende.")
            }
            if (!finishReached && routeMatch.remaining < 70) {
                finishReached = true
                return emit(run, CueKind.ROUTE, "Hier endete deine letzte aufgezeichnete Runde. Wenn du verlängerst, begleite ich dich weiter.")
            }
        }
        if (finishReached && !extensionExplained && routeMatch == null && run.meters > (match?.reference?.meters?:0.0) + 150) {
            extensionExplained = true
            return emit(run, CueKind.ROUTE, "Du verlängerst heute. Ab hier sammeln wir neue Strecke; für diesen Teil gibt es noch keinen Vergleich.")
        }
        if (run.meters < nextDistance) return null
        val milestone = floor(run.meters / profile.cueMeters) * profile.cueMeters
        reset(run.meters, profile.cueMeters)
        val pace = Metrics.recentPace(run.motion, run.activeSeconds)
        val base = "${Metrics.spokenKm(milestone)} Kilometer. ${pace?.let{"Pace ${Metrics.pace(it)}."}?:""} ${hr?.let{"Puls $it."}?:""}"
        val settled = !effort.active && run.activeSeconds >= effort.settlingUntil && hr != null
        var extra = ""
        if (normal && routeMatch != null && !routeNamed) {
            routeNamed = true
            extra = if(Metrics.sameReadiness(run,routeMatch.reference))"Ich erkenne deine Strecke. Ich vergleiche passende Abschnitte bei gleicher Tagesform."
                else "Ich erkenne deine Strecke. Wegen der anderen Tagesform lasse ich den Leistungsvergleich heute weg."
        } else if (normal && settled && ready && routeMatch?.comparison != null && comparisonCount < 1 && positiveCount < 2 && run.meters - lastComparisonMeters >= 1_000) {
            comparisonCount++; lastComparisonMeters = run.meters
            return emit(run, CueKind.PROGRESS, "$base ${routeMatch.comparison}")
        } else if (normal && settled && ready && !taskAdjusted && !taskComplete && positiveCount < 2 && taskStartedMeters?.let{run.meters-it>=1_000}==true) {
            val first = distanceWindow(run, run.meters-1_000, run.meters-500)
            val second = distanceWindow(run, run.meters-500, run.meters)
            if (first != null && second != null && abs(first.pace-second.pace)<=15) {
                taskComplete = true
                outcome = "Gleichmäßiger Kilometer geschafft: beide Hälften höchstens 15 s/km auseinander."
                return emit(run, CueKind.PROGRESS, "$base Aufgabe geschafft: die beiden Hälften deines letzten Kilometers waren schön gleichmäßig. Gut gemacht.")
            }
        } else if (normal && settled && taskAdjusted && !taskComplete && positiveCount < 2 && recoveredAt?.let{run.activeSeconds-it>=180}==true) {
            taskComplete = true; outcome = "Entlastung angenommen und den ruhigeren Rhythmus gehalten."
            return emit(run, CueKind.PROGRESS, "$base Du hast den ruhigeren Rhythmus gehalten. Genau das war jetzt die Aufgabe.")
        }
        if(hr==null && !missingPulseSaid && run.activeSeconds>=30){missingPulseSaid=true;extra="Kein frischer Live-Puls. Heute keine Temposteigerung."}
        return emit(run, CueKind.DISTANCE, "$base $extra".trim())
    }
}
