package dev.kinash.smartscales.core.ble

/**
 * Follows the readings of scales and decides when a weighing is finished. One tracker serves the whole
 * app, so leaving the weighing screen and coming back does not forget what was already finished.
 *
 * A locked reading with a new weighing number finishes a weighing, as in the vendor app. Frames while the
 * weight settles are not required: some phones report a device only once per scan, so they may be missed.
 * The first locked reading from a scale is [Event.FirstSeen] instead: the scale may still advertise a
 * weighing saved before the app was restarted, and the caller checks it against the history. Each
 * weighing finishes once; further locked frames with the same number are ignored until the scale shows a
 * weight being measured again, which starts the next weighing even when the scale keeps the number.
 */
class WeighingTracker {
    sealed interface Event {
        val reading: ScaleReading

        /** The weight while the person stands on the scale. */
        data class Live(override val reading: ScaleReading) : Event

        /** The first locked weight from this scale: new, or a weighing saved before. */
        data class FirstSeen(override val reading: ScaleReading) : Event

        /** A weighing just finished; save it. */
        data class Finished(override val reading: ScaleReading) : Event
    }

    private val seen = mutableSetOf<String>()
    private val finishedSeqNo = mutableMapOf<String, Int?>()

    @Synchronized
    fun onReading(reading: ScaleReading): Event? {
        val scale = reading.scaleMac
        val firstSight = seen.add(scale)
        if (!reading.locked) {
            // Zero frames come when the person steps off; only a real weight starts the next weighing
            if (reading.weight != null) finishedSeqNo -= scale
            return Event.Live(reading)
        }
        if (scale in finishedSeqNo && finishedSeqNo[scale] == reading.measureSeqNo) return null
        finishedSeqNo[scale] = reading.measureSeqNo
        return if (firstSight) Event.FirstSeen(reading) else Event.Finished(reading)
    }
}
