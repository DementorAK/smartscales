package dev.kinash.smartscales.ui.weighing

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchProfilesTest {
    private val latest = mapOf(1L to 80.0, 2L to 61.0, 3L to 78.5)

    @Test
    fun onlyProfilesWithinThresholdMatchClosestFirst() {
        assertEquals(listOf(3L, 1L), matchProfiles(79.0, latest, 3.0))
        assertEquals(listOf(2L), matchProfiles(60.0, latest, 3.0))
        assertEquals(emptyList<Long>(), matchProfiles(40.0, latest, 3.0))
    }

    @Test
    fun thresholdIsInclusive() {
        assertEquals(listOf(2L), matchProfiles(64.0, latest, 3.0))
    }
}
