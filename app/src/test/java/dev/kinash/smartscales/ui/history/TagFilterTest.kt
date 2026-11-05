package dev.kinash.smartscales.ui.history

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.model.MeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TagFilterTest {
    private fun measurement(vararg tags: String) = Measurement(
        profileId = null, takenAt = Instant.parse("2026-11-05T08:00:00Z"), zone = ZoneOffset.UTC,
        source = MeasurementSource.MANUAL, weightKg = 80.0, person = null, tags = tags.toList(),
    )

    @Test
    fun tagsAreTheProfilesOnceEachInTheNewestSpelling() {
        // Newest first, as the history comes
        val history = listOf(measurement("Morning", "fasting"), measurement("morning"), measurement("After gym"))
        assertEquals(listOf("After gym", "fasting", "Morning"), profileTags(history))
    }

    @Test
    fun aWeighingMatchesWithAllTheTagsOn() {
        val both = measurement("Morning", "Fasting")
        assertTrue(matches(both, setOf("morning", "fasting")))
        assertFalse(matches(measurement("Morning"), setOf("morning", "fasting")))
        assertTrue(matches(measurement(), emptySet()))
    }

    @Test
    fun aTagNoWeighingHasIsOffSoNothingHidesWithoutAChip() {
        assertEquals(setOf("morning"), activeFilter(setOf("morning", "renamed"), listOf("Morning", "Fasting")))
    }
}
