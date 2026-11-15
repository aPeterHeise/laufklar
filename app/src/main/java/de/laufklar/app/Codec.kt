package de.laufklar.app

import org.json.JSONArray
import org.json.JSONObject

object Codec {
    fun profile(p: Profile) = JSONObject().apply {
        put("age", p.age); put("weight", p.weight); put("sex", p.sex); put("max", p.measuredMax); put("rest", p.restingHr)
        put("reserve", p.reserveZones); put("bounds", p.customBounds?.let { JSONArray(it) }); put("audio", p.audio); put("cue", p.cueMeters)
        put("health", p.healthExport); put("route", p.shareRoute); put("maps", p.onlineMaps); put("ready", p.intensityReady)
        put("autoHealth", p.healthExport); put("autoPause", p.autoPause)
        put("distanceExperienceKm",p.distanceExperienceKm)
        put("calibratedThreshold",p.calibratedThreshold); put("easyPaceOverride",p.easyPaceOverride)
        put("goalKm", p.goalKm); put("goalPace", p.goalPace); put("frequency", p.runsPerWeek); put("pace", p.usualPace ?: JSONObject.NULL); put("address", p.sensorAddress); put("sensor", p.sensorName)
    }
    fun profile(j: JSONObject) = Profile(
        age=j.optInt("age",38), weight=j.optDouble("weight",83.0), sex=j.optString("sex","Männlich"),
        measuredMax=if(j.has("max")) j.getInt("max") else null, restingHr=if(j.has("rest")) j.getInt("rest") else null,
        reserveZones=j.optBoolean("reserve"), customBounds=j.optJSONArray("bounds")?.let { a -> List(a.length()) { a.getInt(it) } },
        // The old health flag was opt-in. Automatic export now defaults on, including existing profiles.
        audio=j.optBoolean("audio",true), cueMeters=j.optInt("cue",500).coerceIn(100,2000), healthExport=j.optBoolean("autoHealth",true), autoPause=j.optBoolean("autoPause",true),
        shareRoute=j.optBoolean("route"), onlineMaps=j.optBoolean("maps",true), intensityReady=j.optBoolean("ready"),distanceExperienceKm=if(j.has("distanceExperienceKm"))j.getDouble("distanceExperienceKm")else null,
        calibratedThreshold=j.optBoolean("calibratedThreshold"), easyPaceOverride=j.integer("easyPaceOverride"),
        goalKm=j.optDouble("goalKm",10.0), goalPace=j.optInt("goalPace",300), runsPerWeek=j.optInt("frequency",3), usualPace=if(j.has("pace")&&j.isNull("pace"))null else j.optInt("pace",345), sensorAddress=j.optString("address").takeIf{it.isNotBlank()}, sensorName=j.optString("sensor").takeIf{it.isNotBlank()}
    )
    fun run(r: Run) = JSONObject().apply {
        put("id",r.id); put("start",r.started); put("end",r.ended); put("mode",r.mode.name); put("workout",r.workout.name)
        put("seconds",r.activeSeconds); put("meters",r.meters); put("corrected",r.correctedMeters); put("bounds",JSONArray(r.zoneBounds)); put("estimated",r.estimatedZones)
        put("points",JSONArray(r.points.map{JSONArray(listOf(it.time,it.elapsed,it.lat,it.lon,it.accuracy,it.segment))}))
        put("pulses",JSONArray(r.pulses.map{JSONArray(listOf(it.time,it.elapsed,it.bpm))}))
        put("motion",JSONArray(r.motion.map{JSONArray(listOf(it.time,it.elapsed,it.meters,it.speed))}))
        put("pauses",JSONArray(r.pauses.map{JSONArray(listOf(it.from,it.until))}))
        put("phases",JSONArray(r.phases.map{JSONArray(listOf(it.name,it.duration,it.intense))}));put("targetPace",r.targetPace);put("reason",r.coachReason);put("readiness",r.readiness)
        put("rpe",r.rpe); put("note",r.note); put("export",r.exportStatus); put("revision",r.revision); put("exportedRevision",r.exportedRevision); put("recovered",r.recovered)
        put("openEnded",r.openEnded);put("plannedSeconds",r.plannedSeconds);put("coachMission",r.coachMission);put("coachOutcome",r.coachOutcome);put("referenceRunId",r.referenceRunId)
        put("coachEvents",JSONArray(r.coachEvents.map{JSONArray(listOf(it.elapsed,it.kind.name,it.text))}))
        put("sessionPlan",r.sessionPlan?.let(CoachingCodec::session))
        put("missionAttempts",JSONArray(r.missionAttempts.map(CoachingCodec::attempt)))
        put("summary",r.summary?.let(CoachingCodec::summary))
        put("missionFeedback",r.missionFeedback?.name)
        put("unusualConditions",r.unusualConditions)
        r.cooldown?.let { c -> put("cooldown",JSONObject().apply {
            put("start",c.started);put("end",c.ended);put("startBpm",c.startBpm);put("rest",c.restingHr);put("state",c.state.name)
            put("pulses",JSONArray(c.pulses.map { JSONArray(listOf(it.time,it.bpm)) }))
            put("recovery",c.recovery.name);put("endBpm",c.endBpm);put("dropBpm",c.dropBpm);put("score",c.score);put("restingRangeSeconds",c.restingRangeSeconds)
        }) }
    }
    private fun <T> array(j:JSONObject,key:String, map:(JSONArray)->T):List<T> = j.optJSONArray(key)?.let{a->List(a.length()){map(a.getJSONArray(it))}} ?: emptyList()
    fun run(j: JSONObject) = Run(
        id=j.getString("id"),started=j.getLong("start"),ended=if(j.has("end"))j.getLong("end")else null,
        mode=RunMode.valueOf(j.getString("mode")),workout=Workout.valueOf(j.getString("workout")),activeSeconds=j.getDouble("seconds"),meters=j.getDouble("meters"),correctedMeters=if(j.has("corrected"))j.getDouble("corrected")else null,
        zoneBounds=j.getJSONArray("bounds").let{a->List(a.length()){a.getInt(it)}}, estimatedZones=j.optBoolean("estimated",true),
        points=array(j,"points"){TrackPoint(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getInt(5))},
        pulses=array(j,"pulses"){Pulse(it.getLong(0),it.getDouble(1),it.getInt(2))},motion=array(j,"motion"){Motion(it.getLong(0),it.getDouble(1),it.getDouble(2),if(it.isNull(3))null else it.getDouble(3))},
        pauses=array(j,"pauses"){PauseSpan(it.getLong(0),it.getLong(1))},rpe=if(j.has("rpe"))j.getInt("rpe")else null,note=j.optString("note"),exportStatus=j.optString("export","Noch nicht geteilt"),revision=j.optLong("revision",1),exportedRevision=j.optLong("exportedRevision",0),recovered=j.optBoolean("recovered"), phases=array(j,"phases"){Phase(it.getString(0),it.getInt(1),it.getBoolean(2))},targetPace=j.optInt("targetPace",345),coachReason=j.optString("reason"),readiness=j.optString("readiness","READY"),
        openEnded=j.optBoolean("openEnded"),plannedSeconds=if(j.has("plannedSeconds"))j.getInt("plannedSeconds")else null,
        coachMission=j.optString("coachMission"),coachOutcome=j.optString("coachOutcome"),referenceRunId=j.optString("referenceRunId").takeIf{it.isNotBlank()},
        coachEvents=array(j,"coachEvents"){CoachEvent(it.getDouble(0),CueKind.valueOf(it.getString(1)),it.getString(2))},
        sessionPlan=j.optJSONObject("sessionPlan")?.let(CoachingCodec::session),
        missionAttempts=j.items("missionAttempts"){a,i->CoachingCodec.attempt(a.getJSONObject(i))},
        summary=j.optJSONObject("summary")?.let(CoachingCodec::summary),
        missionFeedback=j.optString("missionFeedback").takeIf { it.isNotEmpty() }?.let(MissionFeedback::valueOf),
        unusualConditions=j.optBoolean("unusualConditions"),
        cooldown=j.optJSONObject("cooldown")?.let { c -> Cooldown(
            started=c.getLong("start"),startBpm=c.getInt("startBpm"),restingHr=if(c.has("rest"))c.getInt("rest")else null,
            ended=if(c.has("end"))c.getLong("end")else null,state=CooldownState.valueOf(c.getString("state")),
            pulses=array(c,"pulses"){CooldownPulse(it.getLong(0),it.getInt(1))},
            recovery=RecoveryMethod.valueOf(c.optString("recovery","UNKNOWN"))
        ) }
    )
}
