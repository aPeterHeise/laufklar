package de.laufklar.app

import org.junit.Assert.*
import org.junit.Test

class SpeechQueueTest {
    @Test fun ordinaryCoachingStaysFifoWhileStatusGoesNext() {
        val queue=SpeechQueue()
        queue.add(SpeechCue("Distanz"));queue.add(SpeechCue("Puls"))
        queue.add(SpeechCue("Pausiert",key="movement"),next=true)
        assertEquals("Pausiert",queue.poll(0)?.text)
        assertEquals("Distanz",queue.poll(0)?.text)
        assertEquals("Puls",queue.poll(0)?.text)
        assertNull(queue.poll(0))
    }

    @Test fun resumeReplacesQueuedPauseWithoutDroppingCoachCues() {
        val queue=SpeechQueue()
        queue.add(SpeechCue("Pausiert",key="movement"),next=true)
        queue.add(SpeechCue("Distanz"))
        queue.add(SpeechCue("Fortgesetzt",key="movement"),next=true)
        assertEquals("Fortgesetzt",queue.poll(0)?.text)
        assertEquals("Distanz",queue.poll(0)?.text)
        assertNull(queue.poll(0))
    }

    @Test fun delayedStatusExpiresAndManualResumeInvalidatesPause() {
        val queue=SpeechQueue()
        var paused=true
        queue.add(SpeechCue("Pausiert",key="movement",relevant={paused}),next=true)
        queue.add(SpeechCue("Distanz"))
        paused=false
        assertEquals("Distanz",queue.poll(0)?.text)
        queue.add(SpeechCue("Fortgesetzt",key="movement",validUntil=5_000),next=true)
        queue.add(SpeechCue("Puls"))
        assertEquals("Puls",queue.poll(5_001)?.text)
        assertNull(queue.poll(5_001))
    }

    @Test fun clearingOnManualPauseOrFinishDropsPendingSpeech() {
        val queue=SpeechQueue()
        queue.add(SpeechCue("Pausiert",key="movement"));queue.add(SpeechCue("Puls"))
        queue.remove("movement")
        assertEquals("Puls",queue.poll(0)?.text)
        queue.add(SpeechCue("Distanz"));queue.clear()
        assertNull(queue.poll(0))
    }
}
