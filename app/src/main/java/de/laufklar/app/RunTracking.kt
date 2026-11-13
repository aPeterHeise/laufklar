package de.laufklar.app

import kotlin.math.roundToInt

/** GPS speed in m/s, sampled before filtering stationary route jitter. Missing fixes are not stops. */
class AutoPauseDetector {
    companion object {
        const val STOP_MS = 2_000L
        const val RESUME_MS = 1_000L
        const val MAX_FIX_AGE_MS = 1_500L
    }
    enum class Action { PAUSE, RESUME }
    private var candidateSince: Long? = null
    private var lastSample: Long? = null
    private var candidate: Action? = null

    fun reset() { candidateSince = null; lastSample = null; candidate = null }

    fun sample(speed: Double?, now: Long, automaticallyPaused: Boolean, receivedAt: Long = now): Action? {
        // Late and out-of-order fixes must never rewind a newer movement decision.
        if (lastSample?.let { now <= it } == true) return null
        if (receivedAt - now !in 0..MAX_FIX_AGE_MS) { reset(); return null }
        if (speed == null || !speed.isFinite() || speed !in 0.0..9.0) { reset(); return null }
        if (lastSample?.let { now - it !in 1..3_000 } == true) reset()
        lastSample = now
        val next = when {
            !automaticallyPaused && speed <= .4 -> Action.PAUSE
            automaticallyPaused && speed >= .8 -> Action.RESUME
            else -> null
        }
        if (next != candidate) { candidate = next; candidateSince = now.takeIf { next != null } }
        val since = candidateSince ?: return null
        val required = if (next == Action.PAUSE) STOP_MS else RESUME_MS
        return next?.takeIf { now - since >= required }?.also { reset() }
    }
}

enum class CooldownState { MEASURING, COMPLETE, INTERRUPTED }
data class CooldownPulse(val time: Long, val bpm: Int)
data class Cooldown(
    val started: Long,
    val startBpm: Int,
    val restingHr: Int?,
    val pulses: List<CooldownPulse> = emptyList(),
    val ended: Long? = null,
    val state: CooldownState = CooldownState.MEASURING
) {
    companion object { const val DURATION_MS = 60_000L }
    // Use a short endpoint window, not the minimum pulse reached somewhere during recovery.
    val endBpm: Int? get() {
        if (state != CooldownState.COMPLETE) return null
        val window = pulses.filter { it.time - started in 55_000..DURATION_MS }.distinctBy { it.time }
        if (window.size < 3 || window.last().time - started < 58_000 || window.last().time - window.first().time < 2_000) return null
        return window.map { it.bpm }.sorted().let { it[it.size / 2] }
    }
    val dropBpm: Int? get() = endBpm?.let { startBpm - it }
    // Personal descriptive score: fraction of the initial distance to the configured resting HR.
    // This is deliberately not a clinical HR-recovery classification.
    val score: Int? get() {
        val rest = restingHr ?: return null
        val drop = dropBpm ?: return null
        if (startBpm <= rest) return null
        return (100.0 * drop / (startBpm - rest)).roundToInt().coerceIn(0, 100)
    }
    val restingRangeSeconds: Int? get() {
        val rest = restingHr ?: return null
        var since: Long? = null
        var previous: Long? = null
        for (pulse in pulses) {
            if (pulse.time - started !in 0..DURATION_MS) continue
            if (previous?.let { pulse.time - it !in 1..3_000 } == true) since = null
            if (pulse.bpm > rest + 10) since = null else {
                if (since == null) since = pulse.time
                if (pulse.time - since >= 3_000) return ((pulse.time - started) / 1000).toInt()
            }
            previous = pulse.time
        }
        return null
    }
}

data class ZoneRoutePart(val points: List<TrackPoint>, val zone: Int?)

/** Join historical GPS and pulse timestamps without colouring gaps with stale or future values. */
fun zoneRouteParts(points: List<TrackPoint>, pulses: List<Pulse>, bounds: List<Int>): List<ZoneRoutePart> {
    if (points.size < 2) return emptyList()
    val result = mutableListOf<ZoneRoutePart>()
    var pulseIndex = -1
    var part = mutableListOf<TrackPoint>()
    var partZone: Int? = null
    fun flush() { if (part.size >= 2) result.add(ZoneRoutePart(part.toList(), partZone)); part = mutableListOf() }
    points.zipWithNext().forEach { (a, b) ->
        while (pulseIndex + 1 < pulses.size && pulses[pulseIndex + 1].time <= a.time) pulseIndex++
        if (a.segment != b.segment || b.time - a.time !in 1..15_000) { flush(); return@forEach }
        val pulse = pulses.getOrNull(pulseIndex)
        val zone = pulse?.takeIf { a.time - it.time in 0..10_000 && b.time - it.time <= 10_000 }
            ?.let { Metrics.zone(it.bpm, bounds) }
        if (part.isNotEmpty() && partZone != zone) flush()
        if (part.isEmpty()) { partZone = zone; part.add(a) }
        part.add(b)
    }
    flush()
    return result
}
