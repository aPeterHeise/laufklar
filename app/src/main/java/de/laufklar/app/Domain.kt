package de.laufklar.app

import java.util.Locale
import java.util.UUID
import kotlin.math.*

enum class RunMode(val label: String) { OUTDOOR("Draußen"), TREADMILL("Laufband") }
enum class Workout(val label: String) { EASY("Locker laufen"), BASELINE("Standortbestimmung"), LONG("Länger locker"), INTERVALS("Tempo aufbauen") }
data class Profile(
    val age: Int = 38, val weight: Double = 83.0, val sex: String = "Männlich",
    val measuredMax: Int? = null, val restingHr: Int? = null, val reserveZones: Boolean = false,
    val customBounds: List<Int>? = null, val audio: Boolean = true, val cueMeters: Int = 500,
    val healthExport: Boolean = false, val shareRoute: Boolean = false, val onlineMaps: Boolean = true,
    val intensityReady: Boolean = false, val planWeek: Int = 1, val usualPace: Int = 345,
    val sensorAddress: String? = null, val sensorName: String? = null
) {
    val maxHr: Int get() = measuredMax ?: (208 - .7 * age).roundToInt()
    val bounds: List<Int> get() = customBounds ?: (5..10).map { p ->
        val rest = if (reserveZones) restingHr ?: 0 else 0
        (rest + (maxHr - rest) * p / 10.0).roundToInt()
    }
    val estimated: Boolean get() = customBounds == null && measuredMax == null
    fun zone(bpm: Int): Int = when {
        bpm < bounds[0] -> 0
        bpm >= bounds[5] -> 5
        else -> (1..5).first { bpm < bounds[it] }
    }
    fun validate(): String? = when {
        age !in 18..100 -> "Alter: 18 bis 100 Jahre."
        !weight.isFinite() || weight !in 30.0..300.0 -> "Gewicht: 30 bis 300 kg."
        measuredMax != null && measuredMax !in 100..230 -> "Gemessener Maximalpuls: 100 bis 230."
        restingHr != null && restingHr !in 30..100 -> "Ruhepuls: 30 bis 100."
        reserveZones && restingHr == null -> "Für die Herzfrequenzreserve fehlt dein Ruhepuls."
        customBounds != null && (customBounds.size != 6 || customBounds.any { it !in 40..230 } || customBounds.zipWithNext().any { it.first >= it.second }) -> "Sechs aufsteigende Zonengrenzen zwischen 40 und 230 eingeben."
        usualPace !in 240..900 -> "Ausgangspace: 240 bis 900 Sekunden/km."
        else -> null
    }
}
data class TrackPoint(val time: Long, val elapsed: Double, val lat: Double, val lon: Double, val accuracy: Double, val segment: Int)
data class Pulse(val time: Long, val elapsed: Double, val bpm: Int)
data class Motion(val time: Long, val elapsed: Double, val meters: Double, val speed: Double?)
data class PauseSpan(val from: Long, val until: Long)
data class Run(
    val id: String = UUID.randomUUID().toString(), val started: Long, val ended: Long? = null,
    val mode: RunMode, val workout: Workout, val activeSeconds: Double = 0.0,
    val meters: Double = 0.0, val correctedMeters: Double? = null,
    val points: List<TrackPoint> = emptyList(), val pulses: List<Pulse> = emptyList(),
    val motion: List<Motion> = emptyList(), val pauses: List<PauseSpan> = emptyList(),
    val zoneBounds: List<Int>, val estimatedZones: Boolean = true,
    val rpe: Int? = null, val note: String = "", val exportStatus: String = "Noch nicht geteilt",
    val revision: Long = 1, val exportedRevision: Long = 0, val recovered: Boolean = false
) {
    val distance get() = correctedMeters ?: meters
    val pace get() = if (distance >= 20 && activeSeconds > 0) activeSeconds * 1000 / distance else null
    val averageHr get() = pulses.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.roundToInt()
}
data class Phase(val name: String, val duration: Int, val intense: Boolean = false)
object Training {
    fun phases(workout: Workout, week: Int): List<Phase> = when (workout) {
        Workout.EASY -> listOf(Phase("Locker ankommen", 300), Phase("Ruhig und gesprächig", 1200), Phase("Locker auslaufen", 300))
        Workout.BASELINE -> listOf(Phase("10 Minuten aufwärmen", 600), Phase("20 Minuten gleichmäßig locker", 1200), Phase("Locker auslaufen", 300))
        Workout.LONG -> listOf(Phase("Locker ankommen", 300), Phase("Ruhig und gesprächig", (25 + (week - 1) * 2).coerceAtMost(45) * 60), Phase("Locker auslaufen", 300))
        Workout.INTERVALS -> buildList {
            add(Phase("10 Minuten aufwärmen", 600))
            val repeats = if (week < 5) 4 else if (week < 9) 5 else 6
            repeat(repeats) { n -> add(Phase("Tempo ${n + 1} von $repeats", if (week < 5) 120 else 180, true)); add(Phase("Locker erholen", 120)) }
            add(Phase("Locker auslaufen", 600))
        }
    }
    fun phaseAt(phases: List<Phase>, seconds: Double): Int {
        var end = 0
        phases.forEachIndexed { i, p -> end += p.duration; if (seconds < end) return i }
        return phases.size
    }
    fun targetPace(profile: Profile) = (profile.usualPace - (profile.usualPace - 300).coerceAtLeast(0) * (profile.planWeek - 1) / 11.0).roundToInt().coerceAtLeast(300)
    fun available(profile: Profile) = Workout.entries.filter { it != Workout.INTERVALS || profile.intensityReady }
}
object Metrics {
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return "—"
        val sec = secondsPerKm.roundToInt(); return "%d:%02d".format(Locale.GERMANY, sec / 60, sec % 60)
    }
    fun duration(seconds: Double): String { val s = seconds.toInt(); return "%d:%02d".format(Locale.GERMANY, s / 60, s % 60) }
    fun km(meters: Double) = "%.2f".format(Locale.GERMANY, meters / 1000)
    fun distance(a: TrackPoint, b: TrackPoint): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dlat = p2 - p1; val dlon = Math.toRadians(b.lon - a.lon)
        val h = sin(dlat / 2).pow(2) + cos(p1) * cos(p2) * sin(dlon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun acceptedGps(previous: TrackPoint?, point: TrackPoint): Boolean {
        if (!point.lat.isFinite() || !point.lon.isFinite() || abs(point.lat) > 90 || abs(point.lon) > 180 || point.accuracy !in 0.0..25.0) return false
        if (previous == null || previous.segment != point.segment) return true
        val seconds = (point.time - previous.time) / 1000.0
        return seconds > 0 && (seconds > 15 || distance(previous, point) / seconds <= 9.0)
    }
    fun recentPace(motion: List<Motion>, elapsed: Double): Double? {
        val samples = motion.filter { it.elapsed >= elapsed - 60 }
        if (samples.size < 2 || elapsed - samples.last().elapsed > 10) return null
        val first = samples.first(); val last = samples.last()
        val distance = last.meters - first.meters; val duration = last.elapsed - first.elapsed
        return if (duration >= 10 && distance >= 15) duration * 1000 / distance else null
    }
    fun freshHr(pulses: List<Pulse>, now: Long): Int? {
        if (pulses.isEmpty() || now - pulses.last().time !in 0..10_000) return null
        val recent = pulses.filter { now - it.time in 0..15_000 }.map { it.bpm }.sorted()
        return if (recent.size >= 3) recent[recent.size / 2] else null
    }
    fun zoneSeconds(run: Run): List<Double> {
        val totals = MutableList(6) { 0.0 }
        run.pulses.zipWithNext().forEach { (a, b) ->
            val dt = b.elapsed - a.elapsed
            if (dt in 0.0..10.0 && b.time - a.time <= 10_000) {
                val z = if (a.bpm < run.zoneBounds[0]) 0 else (1..5).firstOrNull { a.bpm < run.zoneBounds[it] } ?: 5
                totals[z] += dt
            }
        }; return totals
    }
    // Only comparable, steady segments after warmup; no VO2max or clinical diagnosis.
    fun benchmark(run: Run): Pair<Double, Double>? {
        val points = run.motion.filter { it.elapsed in 600.0..1800.0 && it.speed != null && it.speed > 0 }
        val hr = run.pulses.filter { it.elapsed in 600.0..1800.0 }
        if (run.activeSeconds < 1800 || points.size < 120 || hr.size < 120 || run.pauses.isNotEmpty()) return null
        val coverage = hr.zipWithNext().sumOf { (a, b) -> if (b.time - a.time <= 10_000) (b.elapsed - a.elapsed).coerceIn(0.0, 10.0) else 0.0 }
        val speed = points.map { it.speed!! }; val mean = speed.average()
        val cv = sqrt(speed.map { (it - mean).pow(2) }.average()) / mean
        if (coverage < 960 || cv > .10) return null
        return Pair(1000 / mean, hr.map { it.bpm }.average())
    }
    fun drift(run: Run): Double? {
        if (benchmark(run) == null) return null
        fun speed(from: Double, to: Double) = run.motion.filter { it.elapsed >= from && it.elapsed < to }.mapNotNull { it.speed }.filter { it > 0 }.average()
        fun hr(from: Double, to: Double) = run.pulses.filter { it.elapsed >= from && it.elapsed < to }.map { it.bpm }.average()
        val s1 = speed(600.0, 1200.0); val s2 = speed(1200.0, 1800.0)
        if (!s1.isFinite() || !s2.isFinite() || abs(s2 / s1 - 1) > .05) return null
        val h1 = hr(600.0, 1200.0); val h2 = hr(1200.0, 1800.0)
        return ((h2 / s2) / (h1 / s1) - 1) * 100
    }
}
object HeartRatePacket {
    fun parse(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 255
        if (flags and 4 != 0 && flags and 2 == 0) return null // Supported contact detection reports no skin contact.
        val wide = flags and 1 != 0
        if (wide && bytes.size < 3) return null
        val bpm = (bytes[1].toInt() and 255) + if (wide) ((bytes[2].toInt() and 255) shl 8) else 0
        return bpm.takeIf { it in 30..240 }
    }
}
class Coach {
    private var nextDistance = 500.0
    private var previousPhase = -1
    private var aboveSince: Double? = null
    private var lastAlert = -1000.0
    fun reset(distance: Double, every: Int) { nextDistance = (floor(distance / every) + 1) * every }
    fun guidance(hr: Int?, profile: Profile, intense: Boolean, pace: Double?): String {
        if (hr == null) return "Kein verlässlicher Live-Puls. Bleib locker; keine Temposteigerung."
        val upper = if (intense) profile.bounds[4] else profile.bounds[2]
        return when {
            hr >= upper -> "Tempo reduzieren. Bei Bedarf gehen, bis du wieder bequem sprechen kannst."
            intense -> {
                val target = Training.targetPace(profile).toDouble()
                when { pace == null -> "Kontrolliert zügig, kein Sprint."
                    pace < target - 10 -> "Etwas langsamer. Zielpace ${Metrics.pace(target)}."
                    pace > target + 15 -> "Wenn es sich kontrolliert anfühlt, leicht Richtung ${Metrics.pace(target)} beschleunigen."
                    else -> "Tempo passt. Kontrolliert weiterlaufen." }
            }
            hr < profile.bounds[1] -> "Ruhig weiter. Du musst den Puls nicht hochjagen."
            else -> "Gut so. Locker bleiben und in ganzen Sätzen sprechen."
        }
    }
    fun update(run: Run, profile: Profile, now: Long): String? {
        val phases = Training.phases(run.workout, profile.planWeek)
        val index = Training.phaseAt(phases, run.activeSeconds)
        val phase = phases.getOrNull(index)
        val hr = Metrics.freshHr(run.pulses, now)
        val advice = guidance(hr, profile, phase?.intense == true, Metrics.recentPace(run.motion, run.activeSeconds))
        val above = hr != null && hr >= (if (phase?.intense == true) profile.bounds[4] else profile.bounds[2])
        if (above) { if (aboveSince == null) aboveSince = run.activeSeconds } else aboveSince = null
        if (aboveSince != null && run.activeSeconds - aboveSince!! >= 15 && run.activeSeconds - lastAlert >= 60) {
            lastAlert = run.activeSeconds; return "Puls $hr. $advice"
        }
        if (index != previousPhase) {
            previousPhase = index
            return if (phase == null) "Trainingsteil abgeschlossen. Laufe locker aus oder beende deinen Lauf." else "${phase.name}. ${phase.duration / 60} Minuten. $advice"
        }
        if (run.meters >= nextDistance) {
            reset(run.meters, profile.cueMeters)
            val pace = Metrics.recentPace(run.motion, run.activeSeconds)
            val paceText = if (pace == null) "Pace derzeit nicht verlässlich." else {
                val sec = pace.roundToInt(); "Pace ${sec / 60} Minuten ${sec % 60} Sekunden pro Kilometer."
            }
            return "${Metrics.km(run.meters)} Kilometer. $paceText ${hr?.let { "Puls $it." } ?: ""} $advice"
        }
        return null
    }
}
