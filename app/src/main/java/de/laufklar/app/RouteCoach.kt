package de.laufklar.app

import kotlin.math.abs
import kotlin.math.roundToInt

data class RouteNode(val point: TrackPoint, val meters: Double)
data class RouteMatch(val reference: Run, val progress: Double, val remaining: Double, val comparison: String?)

/** Distances follow the recorded route, excluding gaps exactly as the recorder does. */
fun routeNodes(points: List<TrackPoint>): List<RouteNode> {
    var meters = 0.0
    return points.mapIndexed { index, point ->
        points.getOrNull(index - 1)?.let { previous ->
            if (previous.segment == point.segment && point.time - previous.time in 1..15_000) meters += Metrics.distance(previous, point)
        }
        RouteNode(point, meters)
    }
}

private fun pulseAverage(run: Run, from: TrackPoint, until: TrackPoint): Double? {
    val samples = run.pulses.filter { it.time in from.time..until.time }
    val seconds = (until.time - from.time) / 1000.0
    if (seconds < 60 || samples.size < 10) return null
    val covered = samples.zipWithNext().sumOf { (a, b) -> if (b.time - a.time in 1..10_000) (b.time - a.time) / 1000.0 else 0.0 }
    return samples.takeIf { covered / seconds >= .8 }?.map { it.bpm }?.average()
}

/** Same-start, same-direction matching of a substantial recent path, not distance-to-home. */
class RouteMatcher(history: List<Run>) {
    private val references = history.filter {
        it.mode == RunMode.OUTDOOR && it.ended != null && !it.recovered && it.distance >= 1_000 && it.points.size >= 20
    }.sortedByDescending { it.started }.take(8).map { it to routeNodes(it.points) }
    private var cachedPoints: List<TrackPoint>? = null
    private var currentNodes = emptyList<RouteNode>()

    fun match(run: Run): RouteMatch? {
        if (run.mode != RunMode.OUTDOOR || run.points.size < 10) return null
        if (cachedPoints !== run.points) { cachedPoints = run.points; currentNodes = routeNodes(run.points) }
        val last = currentNodes.last()
        val tail = currentNodes.takeLastWhile { it.point.segment == last.point.segment && it.meters >= last.meters - 550 }
        if (tail.isEmpty() || last.meters - tail.first().meters < 300) return null
        val probes = mutableListOf(tail.first())
        tail.drop(1).forEach { if (it.meters - probes.last().meters >= 60) probes.add(it) }
        if (probes.last() != last) probes.add(last)
        for ((reference, nodes) in references) {
            if (reference.id == run.id || reference.started >= run.started || Metrics.distance(run.points.first(), reference.points.first()) > 100) continue
            val ends = nodes.indices.filter { abs(nodes[it].meters - last.meters) <= maxOf(200.0, last.meters * .15) && Metrics.distance(last.point, nodes[it].point) <= 35 }
            for (endIndex in ends.sortedBy { Metrics.distance(last.point, nodes[it].point) }) {
                val end = nodes[endIndex]
                val aligned = mutableListOf<RouteNode>()
                var previousIndex = -1
                for (probe in probes) {
                    val expected = end.meters - (last.meters - probe.meters)
                    val index = (previousIndex + 1..endIndex).filter { abs(nodes[it].meters - expected) <= 60 }
                        .minByOrNull { Metrics.distance(probe.point, nodes[it].point) }
                    if (index == null || Metrics.distance(probe.point, nodes[index].point) > 35) break
                    aligned.add(nodes[index]); previousIndex = index
                }
                if (aligned.size != probes.size) continue
                val begin = aligned.first()
                val recordedDistance = end.meters - begin.meters
                val currentDistance = last.meters - probes.first().meters
                if (recordedDistance !in currentDistance * .9..currentDistance * 1.1) continue
                val comparison = compare(run, reference, probes.first(), last, begin, end)
                return RouteMatch(reference, end.meters, (nodes.last().meters - end.meters).coerceAtLeast(0.0), comparison)
            }
        }
        return null
    }

    private fun compare(current: Run, reference: Run, a: RouteNode, b: RouteNode, x: RouteNode, y: RouteNode): String? {
        if (b.meters - a.meters < 450 || a.point.elapsed < 300 || x.point.elapsed < 300 || x.point.segment != y.point.segment) return null
        if (current.pauses.any { it.from < b.point.time && it.until > a.point.time } || reference.pauses.any { it.from < y.point.time && it.until > x.point.time }) return null
        val currentSeconds = b.point.elapsed - a.point.elapsed
        val oldSeconds = y.point.elapsed - x.point.elapsed
        if (currentSeconds < 60 || oldSeconds < 60) return null
        val pace = currentSeconds * 1000 / (b.meters - a.meters)
        val oldPace = oldSeconds * 1000 / (y.meters - x.meters)
        val hr = pulseAverage(current, a.point, b.point) ?: return null
        val oldHr = pulseAverage(reference, x.point, y.point) ?: return null
        val gain = oldPace - pace
        return when {
            gain >= 8 && hr <= oldHr + 3 -> "Auf diesem Abschnitt ${gain.roundToInt()} Sekunden pro Kilometer schneller als beim Vergleichslauf, bei ${if(hr < oldHr - 3)"weniger"else "ähnlichem"} Puls. Das läuft gut."
            abs(gain) <= 10 && oldHr - hr >= 4 -> "Auf diesem Abschnitt ähnliches Tempo wie beim Vergleichslauf, mit ${(oldHr - hr).roundToInt()} Schlägen weniger Puls. Gut unterwegs."
            else -> null
        }
    }
}

data class DistanceWindow(val pace: Double, val start: Motion, val end: Motion)
fun distanceWindow(run: Run, fromMeters: Double, toMeters: Double): DistanceWindow? {
    if (fromMeters < 0 || run.motion.size < 2) return null
    val from = run.motion.minByOrNull { abs(it.meters - fromMeters) } ?: return null
    val to = run.motion.minByOrNull { abs(it.meters - toMeters) } ?: return null
    val distance = to.meters - from.meters
    if (abs(from.meters - fromMeters) > 20 || abs(to.meters - toMeters) > 20 || distance < 400 || to.elapsed - from.elapsed < 30) return null
    if (run.pauses.any { it.from < to.time && it.until > from.time }) return null
    val samples = run.motion.filter { it.time in from.time..to.time }
    if (samples.zipWithNext().any { (a, b) -> b.time - a.time !in 1..15_000 }) return null
    return DistanceWindow((to.elapsed - from.elapsed) * 1000 / distance, from, to)
}
