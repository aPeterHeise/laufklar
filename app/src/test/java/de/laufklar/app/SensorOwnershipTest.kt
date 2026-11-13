package de.laufklar.app

import org.junit.Assert.*
import org.junit.Test

class SensorOwnershipTest {
    private val events=mutableListOf<String>()
    private val owner=SensorOwnership(connect={events+="connect:$it"},release={events+="release"})
    private fun preview(){owner.device("air");owner.preview(true);owner.visible(true)}
    @Test fun savedDeviceConnectsWithoutNeedingAnAdvertisement(){
        owner.device("air");owner.preview(true);assertTrue(events.isEmpty())
        owner.visible(true);assertEquals(listOf("connect:air"),events)
    }
    @Test fun leavingTheAppReleasesPreviewAndReturningReconnects(){
        preview();owner.visible(false);owner.visible(true)
        assertEquals(listOf("connect:air","release","connect:air"),events)
    }
    @Test fun leavingHomeReleasesPreview(){preview();owner.preview(false);assertEquals(listOf("connect:air","release"),events)}
    @Test fun recordingKeepsItsConnectionWhenScreenOrAppCloses(){
        preview();owner.recording(true);owner.preview(false);owner.visible(false)
        assertEquals(listOf("connect:air"),events)
        owner.recording(false);assertEquals(listOf("connect:air","release"),events)
    }
    @Test fun runFinishReleasesBeforeReturningToHome(){
        preview();owner.recording(true);owner.preview(false);owner.recording(false)
        assertEquals(listOf("connect:air","release"),events)
        owner.preview(true);assertEquals("connect:air",events.last())
    }
    @Test fun newProcessRestoresSavedSensorWithoutScanResults(){
        preview();owner.visible(false)
        val restarted=SensorOwnership(connect={events+="restarted:$it"},release={events+="release"})
        restarted.visible(true);restarted.preview(true);restarted.device("air")
        assertEquals(listOf("connect:air","release","restarted:air"),events)
    }
    @Test fun duplicateUiUpdatesDoNotCreateDuplicateConnections(){
        preview();repeat(3){owner.device("air");owner.preview(true);owner.visible(true)}
        assertEquals(listOf("connect:air"),events)
    }
    @Test fun explicitReconnectReleasesOldClientFirst(){
        preview();owner.reconnect();assertEquals(listOf("connect:air","release","connect:air"),events)
    }
    @Test fun manualReleaseStaysReleasedUntilExplicitChoiceOrNextVisit(){
        preview();owner.disconnect();owner.device("air");owner.visible(true);owner.preview(true)
        assertEquals(listOf("connect:air","release"),events)
        owner.choose("air");assertEquals("connect:air",events.last())
    }
    @Test fun switchingDevicesClosesPreviousClient(){
        preview();owner.choose("other");assertEquals(listOf("connect:air","release","connect:other"),events)
    }
    @Test fun oldActivityStoppingCannotDisconnectNewVisibleActivity(){
        val old=Any();val current=Any();owner.device("air")
        owner.preview(true,old);owner.visible(true,old)
        owner.preview(true,current);owner.visible(true,current)
        owner.visible(false,old);owner.preview(false,old)
        assertEquals(listOf("connect:air"),events)
        owner.visible(false,current);assertEquals(listOf("connect:air","release"),events)
    }
    @Test fun hiddenHomeCannotKeepConnectionForVisibleNonPreviewScreen(){
        val home=Any();val settings=Any();owner.device("air")
        owner.preview(true,home);owner.visible(true,home);owner.visible(true,settings)
        owner.visible(false,home)
        assertFalse(owner.hasVisiblePreview)
        assertEquals(listOf("connect:air","release"),events)
    }
}
