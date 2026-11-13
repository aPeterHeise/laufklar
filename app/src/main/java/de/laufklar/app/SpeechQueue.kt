package de.laufklar.app

data class SpeechCue(
    val text: String,
    val key: String? = null,
    val validUntil: Long = Long.MAX_VALUE,
    val relevant: () -> Boolean = { true }
)

/** Ordinary coaching is FIFO. Short status updates replace older updates and go next. */
class SpeechQueue {
    private val pending = ArrayDeque<SpeechCue>()
    fun add(cue: SpeechCue, next: Boolean = false) {
        cue.key?.let(::remove)
        if (next) pending.addFirst(cue) else pending.addLast(cue)
    }
    fun remove(key: String) { pending.removeAll { it.key == key } }
    fun poll(now: Long): SpeechCue? {
        while (pending.isNotEmpty()) {
            val cue = pending.removeFirst()
            if (now <= cue.validUntil && cue.relevant()) return cue
        }
        return null
    }
    fun clear() = pending.clear()
}
