package dev.kinash.smartscales.core.ble

import dev.kinash.smartscales.core.ble.WeighingTracker.Event
import dev.kinash.smartscales.core.protocol.ScaleUnit
import dev.kinash.smartscales.core.protocol.ScaleWeight
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeighingTrackerTest {
    private val tracker = WeighingTracker()

    @Test
    fun weighingFinishesOnceAfterLiveWeight() {
        assertTrue(tracker.onReading(reading(locked = false, seq = 5)) is Event.Live)
        assertTrue(tracker.onReading(reading(locked = true, seq = 5)) is Event.Finished)
        // The scale repeats the locked frame for a while
        assertNull(tracker.onReading(reading(locked = true, seq = 5)))
        assertNull(tracker.onReading(reading(locked = true, seq = 5)))
    }

    @Test
    fun firstLockedFrameFromScaleIsLeftToTheCaller() {
        assertTrue(tracker.onReading(reading(locked = true, seq = 4)) is Event.FirstSeen)
        assertNull(tracker.onReading(reading(locked = true, seq = 4)))
    }

    @Test
    fun newNumberFinishesWithoutLiveFrames() {
        // Some phones report a device once per scan, so frames while the weight settles may be missed
        tracker.onReading(reading(locked = true, seq = 4))
        assertTrue(tracker.onReading(reading(locked = true, seq = 5)) is Event.Finished)
        assertNull(tracker.onReading(reading(locked = true, seq = 5)))
    }

    @Test
    fun scaleThatKeepsItsNumberStillFinishesTheNextWeighing() {
        tracker.onReading(reading(locked = false, seq = 0))
        assertTrue(tracker.onReading(reading(locked = true, seq = 0)) is Event.Finished)
        tracker.onReading(reading(locked = false, seq = 0))
        assertTrue(tracker.onReading(reading(locked = true, seq = 0)) is Event.Finished)
    }

    @Test
    fun steppingOffDoesNotStartTheNextWeighing() {
        tracker.onReading(reading(locked = false, seq = 5))
        tracker.onReading(reading(locked = true, seq = 5))
        // Zero frames while stepping off, then the scale repeats the result
        tracker.onReading(reading(locked = false, seq = 5).copy(weight = null))
        assertNull(tracker.onReading(reading(locked = true, seq = 5)))
    }

    @Test
    fun scalesAreTrackedSeparately() {
        tracker.onReading(reading(locked = false, seq = 5, mac = "A"))
        assertTrue(tracker.onReading(reading(locked = true, seq = 5, mac = "B")) is Event.FirstSeen)
        assertTrue(tracker.onReading(reading(locked = true, seq = 5, mac = "A")) is Event.Finished)
    }

    private fun reading(locked: Boolean, seq: Int, mac: String = "A") = ScaleReading(
        scaleMac = mac,
        weight = ScaleWeight(800, ScaleUnit.KILOGRAM, 1),
        locked = locked,
        measureSeqNo = seq,
    )
}
