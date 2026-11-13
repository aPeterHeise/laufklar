package de.laufklar.app

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun run(seconds:Double=100.0,meters:Double=100.0,pulses:List<Pulse> = emptyList())=Run(started=0,ended=100_000,mode=RunMode.OUTDOOR,workout=Workout.EASY,activeSeconds=seconds,meters=meters,zoneBounds=Profile().bounds,pulses=pulses)
    @Test fun defaultsAreExplicitEstimates(){val p=Profile();assertEquals(181,p.maxHr);assertTrue(p.estimated);assertEquals(listOf(91,109,127,145,163,181),p.bounds);assertNull(p.restingHr)}
    @Test fun measuredAndManualZonesTakePriority(){val p=Profile(measuredMax=190,restingHr=60,reserveZones=true);assertEquals(138,p.bounds[1]);assertFalse(p.estimated);val q=p.copy(customBounds=listOf(90,110,135,155,175,195));assertEquals(135,q.bounds[2]);assertEquals(3,q.zone(135))}
    @Test fun invalidZoneOrderAndMissingRestAreRejected(){assertNotNull(Profile(customBounds=listOf(90,110,100,150,170,190)).validate());assertNotNull(Profile(reserveZones=true).validate());assertNotNull(Profile(weight=Double.NaN).validate())}
    @Test fun bleSupportsEightAndSixteenBitPackets(){assertEquals(123,HeartRatePacket.parse(byteArrayOf(0,123)));assertEquals(200,HeartRatePacket.parse(byteArrayOf(1,200.toByte(),0)))}
    @Test fun bleRejectsLostSkinContactTruncationAndNonsense(){assertNull(HeartRatePacket.parse(byteArrayOf(4,120)));assertNull(HeartRatePacket.parse(byteArrayOf(1,100)));assertNull(HeartRatePacket.parse(byteArrayOf(0,0)));assertEquals(120,HeartRatePacket.parse(byteArrayOf(6,120)))}
    @Test fun freshHeartRateNeedsEnoughDataAndRejectsOldValues(){val p=listOf(Pulse(1000,1.0,110),Pulse(2000,2.0,180),Pulse(3000,3.0,112));assertEquals(112,Metrics.freshHr(p,4000));assertNull(Metrics.freshHr(p,14000));assertNull(Metrics.freshHr(p.take(2),3000));assertNull(Metrics.freshHr(p,0))}
    @Test fun startDoesNotAnnouncePulseInitializationButLaterMissingDataIsReported(){
        val first=Coach().update(run(seconds=0.0,meters=0.0),Profile(),0)!!
        assertFalse(first.contains("Puls",ignoreCase=true));assertTrue(first.contains("locker"))
        val coach=Coach();coach.update(run(seconds=0.0,meters=0.0),Profile(),0)
        val later=coach.update(run(seconds=200.0,meters=500.0),Profile(),200_000)!!
        assertTrue(later.contains("Kein frischer Live-Puls"))
    }
    @Test fun paceInputUsesMinutesAndSecondsWithoutGuessingUnits(){
        assertEquals(330,Metrics.parsePace("5:30"));assertEquals(345,Metrics.parsePace(" 05:45 "));assertEquals(1200,Metrics.parsePace("20:00"))
        listOf("330","5.5","5:60","5:3","0:00","20:01","").forEach{assertNull(it,Metrics.parsePace(it))}
    }
    @Test fun unknownPaceSurvivesStorageAndLegacyProfilesKeepTheirPace(){
        val p=Profile(usualPace=null);assertNull(p.validate());assertEquals(p,Codec.profile(Codec.profile(p)))
        assertEquals(345,Codec.profile(org.json.JSONObject().put("pace",345)).usualPace)
        assertEquals(345,Codec.profile(org.json.JSONObject()).usualPace)
    }
    @Test fun missingHrNeverAsksToSpeedUp(){val s=Coach().guidance(null,Profile(intensityReady=true),true,450.0);assertTrue(s.contains("keine Temposteigerung"))}
    @Test fun easyLowHrDoesNotForceSpeedUp(){val s=Coach().guidance(90,Profile(),false,500.0);assertTrue(s.contains("nicht hochjagen"))}
    @Test fun highHeartRateOverridesPaceGoal(){val s=Coach().guidance(175,Profile(intensityReady=true),true,400.0);assertTrue(s.startsWith("Tempo reduzieren"))}
    @Test fun distanceCuesDoNotBurstWhenSeveralThresholdsAreCrossed(){val c=Coach();val p=Profile();c.update(run(),p,100_000);assertNotNull(c.update(run(seconds=130.0,meters=1600.0),p,130_000));assertNull(c.update(run(seconds=160.0,meters=1601.0),p,160_000))}
    @Test fun sustainedHighHrProducesEarlyWarning(){val c=Coach();val p=Profile(measuredMax=190)
        fun high(sec:Int)=run(seconds=sec.toDouble(),pulses=(sec-4..sec).map{Pulse(it*1000L,it.toDouble(),140)})
        c.update(high(100),p,100_000);(101..130).forEach{assertNull(c.update(high(it),p,it*1000L))};assertTrue(c.update(high(132),p,132_000)!!.contains("heraus"));assertNull(c.update(high(140),p,140_000))}
    @Test fun intervalsAreOptInAndHaveWarmupCooldown(){assertFalse(Training.available(Profile()).contains(Workout.INTERVALS));val p=Training.fallback(Workout.INTERVALS);assertFalse(p.first().intense);assertFalse(p.last().intense);assertEquals(4,p.count{it.intense});assertEquals(1,Training.phaseAt(p,600.0))}

    @Test fun poorGpsAndTeleportAreRejected(){val p=TrackPoint(0,0.0,52.0,13.0,5.0,0);assertFalse(Metrics.acceptedGps(p,p.copy(time=1000,lat=53.0)));assertFalse(Metrics.acceptedGps(null,p.copy(accuracy=30.0)));assertTrue(Metrics.acceptedGps(p,p.copy(time=1000,lat=52.00002)))}
    @Test fun missingPaceStaysUnknown(){assertEquals("—",Metrics.pace(null));assertNull(Metrics.recentPace(listOf(Motion(0,0.0,0.0,null),Motion(1000,1.0,100.0,2.0)),1.0));assertEquals("5:00",Metrics.pace(299.6))}
    @Test fun paceUsesRecentDistanceAndTime(){val data=listOf(Motion(0,0.0,0.0,3.0),Motion(60000,60.0,200.0,3.0));assertEquals(300.0,Metrics.recentPace(data,60.0)!!,.001);assertNull(Metrics.recentPace(data,80.0))}
    @Test fun pulseGapsAreNotCountedAsTrainingTime(){val r=run(pulses=listOf(Pulse(0,0.0,120),Pulse(1000,1.0,120),Pulse(60000,60.0,120)));assertEquals(1.0,Metrics.zoneSeconds(r).sum(),.001)}
    @Test fun pausesNeverContributeToZoneDurations(){val r=run(pulses=listOf(Pulse(0,0.0,120),Pulse(1000,1.0,120),Pulse(60000,2.0,120)));assertEquals(1.0,Metrics.zoneSeconds(r).sum(),.001)}
    @Test fun codecRoundTripsPartialAndCompletedRuns(){val r=run(pulses=listOf(Pulse(0,0.0,110))).copy(correctedMeters=1234.0,note="Müde & kühl",rpe=3,points=listOf(TrackPoint(1,1.0,52.1,13.1,4.0,1)),motion=listOf(Motion(1,1.0,1.0,null)),pauses=listOf(PauseSpan(1,2)));assertEquals(r,Codec.run(Codec.run(r)));assertEquals(Profile(),Codec.profile(Codec.profile(Profile())));assertEquals(r.copy(ended=null),Codec.run(Codec.run(r.copy(ended=null))))}
    @Test fun sparseComparisonIsUnknown(){assertNull(Metrics.benchmark(run(seconds=1800.0)));assertNull(Metrics.drift(run(seconds=1800.0)))}
    @Test fun comparisonUsesOnlySteadyCoveredPostWarmup(){val r=run(seconds=1800.0).copy(motion=(0..1800).map{Motion(it*1000L,it.toDouble(),it*3.0,3.0)},pulses=(0..1800).map{Pulse(it*1000L,it.toDouble(),if(it<600)90 else 120)});val b=Metrics.benchmark(r)!!;assertEquals(333.33,b.first,.01);assertEquals(120.0,b.second,.01);assertEquals(0.0,Metrics.drift(r)!!,.01)}
    @Test fun variableSpeedIsNotCalledComparable(){val r=run(seconds=1800.0).copy(motion=(0..1800).map{Motion(it*1000L,it.toDouble(),it*3.0,if(it<1200)2.0 else 4.0)},pulses=(0..1800).map{Pulse(it*1000L,it.toDouble(),120)});assertNull(Metrics.benchmark(r))}
}
