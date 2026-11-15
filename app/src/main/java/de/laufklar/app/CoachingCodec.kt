package de.laufklar.app

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.number(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
internal fun JSONObject.integer(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
internal fun JSONObject.timestamp(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
internal fun <T> JSONObject.items(key: String, read: (JSONArray, Int) -> T): List<T> =
    optJSONArray(key)?.let { array -> List(array.length()) { read(array, it) } }.orEmpty()

internal object CoachingCodec {
    fun target(p: PulseTarget) = JSONObject().put("low", p.low).put("high", p.high).put("source", p.source.name)
    fun target(j: JSONObject) = PulseTarget(j.getInt("low"), j.getInt("high"), TargetSource.valueOf(j.getString("source")))

    fun learning(s: LearningSnapshot) = JSONObject().apply {
        put("version", s.version)
        put("ids", JSONArray(s.runIds))
        put("pace", s.observedPace)
        put("target", s.target?.let(::target))
        put("spread", s.bpmSpread)
        put("latest", s.latest)
        put("missing", s.missing)
    }

    fun learning(j: JSONObject) = LearningSnapshot(j.items("ids") { a, i -> a.getString(i) }, j.integer("pace"),
        j.optJSONObject("target")?.let(::target), j.integer("spread"), j.timestamp("latest"), j.optString("missing"), j.optInt("version", 1))

    fun session(s: SessionPlan) = JSONObject().apply {
        put("intense", s.intense)
        put("warmup", s.warmup)
        put("openingPace", s.openingPace)
        put("target", s.pulseTarget?.let(::target))
        put("source", s.source.name)
        put("quietPace", s.quietPace)
        put("tempoPace", s.tempoPace)
        put("seconds", s.seconds)
        put("missions", JSONArray(s.missions.map { it.name }))
        put("seed", s.seed)
        put("reason", s.selectionReason)
        put("learning", learning(s.learning))
        put("extra", s.extraRepetitions)
        put("version", s.version)
    }

    fun session(j: JSONObject) = SessionPlan(j.optBoolean("intense"), j.getInt("warmup"), j.integer("openingPace"),
        j.optJSONObject("target")?.let(::target), TargetSource.valueOf(j.getString("source")),
        j.integer("quietPace"), j.integer("tempoPace"), j.integer("seconds"),
        j.items("missions") { a, i -> MissionId.valueOf(a.getString(i)) }, j.getLong("seed"), j.optString("reason"),
        learning(j.getJSONObject("learning")), j.optInt("extra"), j.optInt("version", 1))

    fun attempt(m: MissionAttempt) = JSONObject().apply {
        put("id", m.id.name)
        put("state", m.state.name)
        put("start", m.startedAt)
        put("startMeters", m.startedMeters)
        put("end", m.endedAt)
        put("endMeters", m.endedMeters)
        put("pace", m.pace)
        put("result", m.result)
        put("phase", m.phase)
        put("first", m.firstPace)
        put("second", m.secondPace)
        put("fraction", m.measuredFraction)
        put("hrChange", m.hrChange)
        put("segment", m.segment)
        put("version", m.version)
    }

    fun attempt(j: JSONObject) = MissionAttempt(MissionId.valueOf(j.getString("id")), MissionState.valueOf(j.getString("state")),
        j.number("start"), j.number("startMeters"), j.number("end"), j.number("endMeters"), j.number("pace"), j.optString("result"),
        j.optInt("phase", -1), j.number("first"), j.number("second"), j.number("fraction"), j.integer("hrChange"),
        j.integer("segment"), j.optInt("version", 1))

    private fun section(s: Section) = JSONArray(listOf(s.from, s.until, s.meters, s.hr, s.coverage))
    private fun section(a: JSONArray) = Section(a.getDouble(0), a.getDouble(1), a.getDouble(2),
        if (a.isNull(3)) null else a.getDouble(3), a.getDouble(4))

    fun comparison(c: RunComparison) = JSONObject().apply {
        put("reference", c.referenceId)
        put("date", c.referenceDate)
        put("sections", JSONArray(c.sections.map { JSONArray(listOf(it.startMeters, it.endMeters, section(it.current), section(it.previous))) }))
        put("pace", c.pace)
        put("previousPace", c.previousPace)
        put("hr", c.hr)
        put("previousHr", c.previousHr)
        put("kind", c.kind.name)
        put("distance", c.referenceDistance)
        put("active", c.referenceActiveSeconds)
        put("total", c.referenceTotalSeconds)
        put("sameRoute", c.sameFullRoute)
        put("referenceRevision", c.referenceRevision)
        put("version", c.version)
    }

    fun comparison(j: JSONObject) = RunComparison(j.getString("reference"), j.getLong("date"),
        j.items("sections") { a, i -> a.getJSONArray(i).let {
            ComparedSection(it.getDouble(0), it.getDouble(1), section(it.getJSONArray(2)), section(it.getJSONArray(3)))
        } }, j.getDouble("pace"), j.getDouble("previousPace"), j.getDouble("hr"), j.getDouble("previousHr"),
        ComparisonKind.valueOf(j.getString("kind")), j.getDouble("distance"), j.getDouble("active"),
        j.getDouble("total"), j.optBoolean("sameRoute"), j.optInt("version", 1), j.optLong("referenceRevision", 1))

    fun summary(s: RunSummary) = JSONObject().apply {
        put("id", s.id)
        put("distance", s.distance)
        put("active", s.activeSeconds)
        put("total", s.totalSeconds)
        put("comparison", s.comparison?.let(::comparison))
        put("why", s.comparisonReason)
        put("favorable", s.favorableRuns)
        put("drop", s.cooldownDrop)
        put("delay", s.cooldownDelaySeconds)
        put("recovery", s.recovery.name)
        put("next", s.next)
        put("audio", s.audio)
        put("speech", s.speech.name)
        put("lastRound",s.lastRound?.let { JSONArray(listOf(it.id,it.date,it.distance,it.active,it.total)) })
        put("cooldownComparison",s.cooldownComparison?.let { JSONArray(listOf(it.date,it.dropBpm)) })
        put("version", s.version)
    }

    fun summary(j: JSONObject) = RunSummary(j.getString("id"), j.getDouble("distance"), j.getDouble("active"),
        j.getDouble("total"), j.optJSONObject("comparison")?.let(::comparison), j.optString("why"),
        j.optInt("favorable"), j.integer("drop"), j.integer("delay"),
        RecoveryMethod.valueOf(j.optString("recovery", "UNKNOWN")), j.getString("next"), j.getString("audio"),
        SummarySpeech.valueOf(j.optString("speech", "AVAILABLE")), j.optInt("version", 1),
        j.optJSONArray("lastRound")?.let { RoundFacts(it.getString(0),it.getLong(1),it.getDouble(2),it.getDouble(3),it.getDouble(4)) },
        j.optJSONArray("cooldownComparison")?.let { RecoveryComparison(it.getLong(0),it.getInt(1)) })
}
