package de.laufklar.app

import kotlin.math.*

enum class Readiness(val label:String) { READY("Erholt"), TIRED("Müde / Hangover"), UNWELL("Krank / Beschwerden") }
enum class TrainingFocus(val label:String) { LEARN("Dich kennenlernen"), BUILD("Ziel aufbauen"), MAINTAIN("Fitness erhalten"), RETURN("Wieder einsteigen"), RECOVER("Erholung priorisieren") }
data class DailyPlan(
    val focus:TrainingFocus, val reason:String, val suggested:Workout, val restRecommended:Boolean,
    val qualityAllowed:Boolean, val targetPace:Int, val easyMinutes:Int, val longMinutes:Int,
    val repetitions:Int, val workSeconds:Int, val achieved:Boolean, val readiness:Readiness,
    val startingPaceKnown:Boolean = true
){
    fun phases(workout:Workout):List<Phase> = when(workout){
        Workout.EASY,Workout.LONG->listOf(Phase("Ankommen",300),Phase(if(workout==Workout.LONG)"Ausdauer sammeln"else "Deine Runde",0))
        Workout.BASELINE->Training.fallback(workout)
        Workout.INTERVALS->buildList{add(Phase("10 Minuten aufwärmen",600));repeat(repetitions){n->add(Phase("Tempo ${n+1} von $repetitions",workSeconds,true));add(Phase("Locker erholen",120))};add(Phase("Locker auslaufen",600))}
    }
    fun available()=Workout.entries.filter{it!=Workout.BASELINE&&(it!=Workout.INTERVALS||qualityAllowed)}
    fun openEnded(workout:Workout)=workout==Workout.EASY||workout==Workout.LONG
    fun durationLabel(workout:Workout)=when(workout){Workout.EASY->"Offen";Workout.LONG->"ca. $longMinutes′";else->"${phases(workout).sumOf{it.duration}/60}′"}
}
object AdaptiveTrainer {
    const val DAY=86_400_000L
    fun reached(run:Run,p:Profile):Boolean {
        val end=run.ended?:return false
        // Goal uses total elapsed time, including pauses, so resting cannot manufacture an achievement.
        return !run.recovered && run.distance>=p.goalKm*1000 && (end-run.started)/1000.0/(run.distance/1000)<=p.goalPace && run.activeSeconds>0
    }
    fun qualitySucceeded(run:Run):Boolean {
        val phases=run.phases.ifEmpty{Training.fallback(run.workout)}
        if(run.workout!=Workout.INTERVALS||run.recovered||run.rpe==null||run.rpe>7||run.activeSeconds<phases.sumOf{it.duration}*.95) return false
        val work=phases.filter{it.intense}.sumOf{it.duration}.toDouble()
        var valid=0.0;var controlled=0.0
        run.pulses.zipWithNext().forEach{(a,b)->
            if(phases.getOrNull(Training.phaseAt(phases,a.elapsed))?.intense==true&&b.time-a.time in 1..10_000){val dt=(b.elapsed-a.elapsed).coerceIn(0.0,10.0);valid+=dt;if(a.bpm<run.zoneBounds[4])controlled+=dt}
        }
        val speeds=run.motion.filter{phases.getOrNull(Training.phaseAt(phases,it.elapsed))?.intense==true}.mapNotNull{it.speed}.filter{it>0}
        if(valid<work*.7||controlled<valid*.8||speeds.size<work/10)return false
        val actualPace=1000/speeds.average()
        return actualPace in run.targetPace*.90..run.targetPace*1.10
    }
    fun plan(profile:Profile,history:List<Run>,mode:RunMode=RunMode.OUTDOOR,readiness:Readiness=Readiness.READY,now:Long=System.currentTimeMillis()):DailyPlan {
        val all=history.filter{it.ended!=null&&it.started<=now&&it.activeSeconds>=300&&!it.recovered}.sortedByDescending{it.started}
        val relevant=all.filter{it.mode==mode}
        val recent=relevant.filter{now-it.started<=35*DAY}
        val recentAll=all.filter{now-it.started<=28*DAY}
        val gap=all.firstOrNull()?.let{(now-it.ended!!)/DAY}?:0
        val latest=all.firstOrNull()
        val poor=recentAll.take(3).count{(it.rpe?:0)>=8}
        val achieved=recent.any{reached(it,profile)}
        val distanceKnown=(profile.distanceExperienceKm?:0.0)>=profile.goalKm||relevant.any{it.distance>=profile.goalKm*1000}
        val recentReturn = all.zipWithNext().withIndex().firstOrNull { (_, pair) -> pair.first.started - pair.second.ended!! >= 14*DAY }?.index?.let { it < 2 } == true
        val returning=all.isNotEmpty()&&(gap>=14||recentReturn)
        val overloaded=poor>=2||readiness!=Readiness.READY
        val focus=when{overloaded->TrainingFocus.RECOVER;returning->TrainingFocus.RETURN;achieved->TrainingFocus.MAINTAIN;recent.size<3->TrainingFocus.LEARN;else->TrainingFocus.BUILD}
        val week=all.filter{now-it.started<7*DAY}
        val priorWeeks=all.filter{now-it.started in 7*DAY until 28*DAY}
        val weeklyBase=priorWeeks.sumOf{it.activeSeconds}/3
        val loadHigh=priorWeeks.size>=4&&week.sumOf{it.activeSeconds}>weeklyBase*1.2
        val recentHard=all.any{now-it.started<7*DAY&&it.workout==Workout.INTERVALS}
        val rest=readiness==Readiness.UNWELL||latest?.let{now-it.ended!!<24*3_600_000L}==true||week.size>=profile.runsPerWeek||loadHigh
        val observedEasyPaces=recent.filter{it.workout!=Workout.INTERVALS&&it.rpe!=null&&it.rpe<=4&&it.pace!=null&&it.pace!! in 180.0..1200.0}.take(3).map{it.pace!!}
        val startingPace=profile.usualPace?:observedEasyPaces.takeIf{it.size>=3}?.average()?.roundToInt()
        val qualityAllowed=startingPace!=null&&profile.intensityReady&&focus in setOf(TrainingFocus.BUILD,TrainingFocus.MAINTAIN)&&readiness==Readiness.READY&&!recentHard&&!rest&&latest?.let{now-it.ended!!>=48*3_600_000L}!=false
        val recentQuality=recent.firstOrNull{it.workout==Workout.INTERVALS}
        val successful=recentQuality?.let(::qualitySucceeded)==true
        val wellTolerated=recent.take(3).count{it.rpe!=null&&it.rpe<=4}>=2
        val previousLong=recent.filter{it.workout!=Workout.INTERVALS}.maxOfOrNull{it.activeSeconds/60}?.roundToInt()?:30
        val comfortablePace=recent.filter{it.workout!=Workout.INTERVALS&&it.pace!=null&&it.pace!! in 180.0..1200.0}.take(3).map{it.pace!!}.takeIf{it.isNotEmpty()}?.average()?:profile.usualPace?.times(1.15)
        val goalLong=comfortablePace?.let{(profile.goalKm*it/60*1.05).roundToInt().coerceIn(25,150)}?:150
        var long=(previousLong+if(wellTolerated&&!loadHigh)5 else 0).coerceAtLeast(30).coerceAtMost(goalLong)
        var easy=(previousLong*.75).roundToInt().coerceIn(25,45)
        if(focus==TrainingFocus.RETURN){long=(previousLong*.65).roundToInt().coerceIn(20,35);easy=20}
        if(focus==TrainingFocus.RECOVER||loadHigh){long=(previousLong*.7).roundToInt().coerceIn(20,35);easy=20}
        // A placeholder is kept only in non-intense run snapshots; no pace prescription until learned.
        val previousPace=recentQuality?.targetPace?:startingPace?.coerceAtLeast(profile.goalPace)?:profile.goalPace
        val oldReps=recentQuality?.phases?.count{it.intense}?.takeIf{it>0}?:4
        val oldWork=recentQuality?.phases?.firstOrNull{it.intense}?.duration?:120
        var reps=oldReps;var work=oldWork;var target=previousPace
        // Progress exactly one variable per successful quality workout. Missing evidence never increases load.
        if(successful&&!loadHigh){
            if(previousPace>profile.goalPace)target=(previousPace-5).coerceAtLeast(profile.goalPace)
            else if(oldReps<6)reps=oldReps+1
            else work=(oldWork+30).coerceAtMost(300)
        }else if((recentQuality?.rpe?:0)>=8){target=previousPace+10;reps=(oldReps-1).coerceAtLeast(3)}
        target=target.coerceAtLeast(profile.goalPace)
        if(focus==TrainingFocus.RETURN||focus==TrainingFocus.RECOVER){target=max(target,startingPace?:target);reps=3;work=120}
        // The everyday entry point stays a familiar, open run. Structured sessions remain optional.
        val suggested=Workout.EASY
        val reason=when{
            readiness==Readiness.UNWELL->"Heute kein Lauftraining. Krankheit oder Beschwerden haben Vorrang vor dem Ziel."
            readiness==Readiness.TIRED->"Du meldest Müdigkeit oder Hangover. Heute keine Intervalle; Erholung oder höchstens sehr locker."
            returning->if(gap>=14)"Seit dem letzten Lauf sind $gap Tage vergangen. Kürzer und locker wieder einsteigen; dein Ziel bleibt bestehen."else "Nach deiner längeren Pause bleiben die ersten drei Einheiten kürzer und locker. Dein Ziel bleibt bestehen."
            poor>=2->"Zwei deiner letzten drei Läufe waren sehr anstrengend. Wir reduzieren zunächst die Belastung."
            loadHigh->"Deine letzten sieben Tage liegen über deinem bisherigen Umfang. Heute nicht weiter steigern."
            rest->"Deine letzten Läufe sprechen heute für Erholung. Der nächste Reiz wirkt besser, wenn du erholt bist."
            achieved->"Dein Ziel wurde in den letzten 35 Tagen für ${mode.label.lowercase()} protokolliert. Jetzt erhalten: regelmäßig locker, gelegentlich kontrolliertes Tempo."
            distanceKnown&&recent.size<3->"Du kannst die Zieldistanz bereits laufen. Jetzt lernen wir dein Tempo und deine Belastung kennen, um über ${Metrics.spokenKm(profile.goalKm*1000)} Kilometer schneller zu werden. Deine Runde hat kein festes Zeitlimit."
            startingPace==null->"Deine Runde, dein Tempo. Ich lerne aus deinen Läufen und deinem Belastungsgefühl. Vergleiche entstehen automatisch auf passenden Abschnitten; ein Tempoziel kommt erst mit genügend Daten."
            recent.size<3->"Ich begleite deine gewohnte Runde ohne festes Zeitlimit. Nach dem Ankommen probieren wir einen gleichmäßigen Kilometer. Ergänze danach dein Belastungsgefühl, damit ich dich besser kennenlerne."
            successful&&qualityAllowed->"Die letzte Tempoeinheit war vollständig, kontrolliert und gut dokumentiert. Heute ist ein kleiner nächster Schritt möglich."
            qualityAllowed->"Du läufst regelmäßig und hattest Abstand zur letzten Belastung. Eine kontrollierte Tempoeinheit kann heute passen."
            distanceKnown->"Die Zieldistanz ist dir vertraut. Gleichmäßige Runden und passende Tempoeinheiten helfen beim nächsten Schritt zum Zeitziel. Deine bisherigen Läufe bestimmen die Aufgaben."
            else->"Deine Runde bleibt offen. Für den Weg zur Zieldistanz kannst du „Länger laufen“ wählen: etwa $long Minuten, angepasst an deinen zuletzt vertragenen Umfang."
        }
        return DailyPlan(focus,reason,suggested,rest,qualityAllowed,target,easy,long,reps,work,achieved,readiness,startingPace!=null)
    }
}
// Primary source: Thuany et al., Healthcare 2021, Table 2. Selected cells with >=10 participants.
// These are self-reported paces across distances, not population health standards or joint age/distance norms.
object ResearchNorms {
    data class Row(val from:Int,val to:Int,val maleMedian:Double,val maleN:Int,val femaleMedian:Double?,val femaleN:Int)
    val rows=listOf(Row(20,24,255.0,32,336.5,10),Row(25,29,270.0,69,360.0,40),Row(30,34,300.0,80,340.0,73),Row(35,39,307.0,99,360.0,65),Row(40,44,310.0,82,360.0,60),Row(45,49,300.0,50,360.0,35),Row(50,54,340.0,47,360.0,13),Row(55,59,330.0,26,null,7))
    fun reference(age:Int,sex:String):Pair<Double,Int>? {
        val row=rows.firstOrNull { age in it.from..it.to }?:return null
        return when(sex.trim().lowercase()) {
            "männlich","mann","male"->row.maleMedian to row.maleN
            "weiblich","frau","female"->row.femaleMedian?.let { it to row.femaleN }
            else->null
        }
    }
    fun adjustedPace(reference:Double,speedPercent:Double):Int {
        require(reference.isFinite() && reference>0 && speedPercent.isFinite() && speedPercent in 80.0..120.0)
        return (reference/(speedPercent/100.0)).roundToInt()
    }
    fun distanceReference(km:Double,sex:String):Double? {
        val male=sex.lowercase() in setOf("männlich","mann","male")
        val female=sex.lowercase() in setOf("weiblich","frau","female")
        if(!male&&!female)return null
        return when(km){5.0->if(male)280.0 else 360.0;10.0->if(male)308.0 else 360.0;21.1->if(male)300.0 else 340.0;42.195->if(male)300.0 else 337.5;else->null}
    }
    fun hint(age:Int,sex:String):String {
        val male=when(sex.trim().lowercase()){ "männlich","mann","male"->true;"weiblich","frau","female"->false;else->return "Für diese Geschlechtsangabe liefert die verwendete Studie keine passende Vergleichsgruppe. Dein Ziel bleibt frei wählbar." }
        val row=rows.firstOrNull{age in it.from..it.to}?:return "Für dieses Alter zeigen wir aus der gewählten Studie keinen ausreichend belastbaren Zahlenvergleich. Es gibt keine allgemeine Laufzeit, die gesunde Menschen erreichen müssen."
        val median=if(male)row.maleMedian else row.femaleMedian
        if(median==null)return "Die passende Vergleichsgruppe war sehr klein. Deshalb zeigen wir hier keine scheinpräzise Normzeit."
        return "Orientierung: ${if(male)"Männer"else "Frauen"} ${row.from}–${row.to} Jahre berichteten in einer Studie mit brasilianischen Freizeitläufern als Bestpace im Median ${Metrics.pace(median)} min/km (n=${if(male)row.maleN else row.femaleN}). Verschiedene Distanzen, Selbstauskunft, keine repräsentative Bevölkerungsnorm. Kein Gesundheits-Soll und keine Vorhersage für deine Zieldistanz. Quelle: Thuany et al., 2021, Tabelle 2."
    }
}
