package dev.kinash.smartscales.importing

import dev.kinash.smartscales.core.data.Measurement
import dev.kinash.smartscales.core.data.Profile
import dev.kinash.smartscales.core.model.Impedance
import dev.kinash.smartscales.core.model.MeasurementSource
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs

/**
 * What importing a table into a profile will do: the weighings to add, and how many rows are left out as
 * already there, as dated before the person's birth or as not read.
 */
class ImportPlan(val add: List<Measurement>, val duplicates: Int, val beforeBirth: Int, val unreadable: List<Int>) {
    companion object {
        /** Within a minute and 0.1 kg of a weighing the profile has, or of an earlier row: the same weighing. */
        private val SAME_TIME: Duration = Duration.ofMinutes(1)
        private const val SAME_KG = 0.1

        /**
         * The weighings [rows] make for [profile], at the times of day the table gives in [zone], with the
         * profile's height, sex and birth date; the other app's values are kept as they are.
         */
        fun of(rows: ImportLayout.Rows, profile: Profile, existing: List<Measurement>, zone: ZoneId): ImportPlan {
            val person = profile.snapshot()
            val kept = ArrayList<Measurement>()
            var duplicates = 0
            var beforeBirth = 0
            val sorted = existing.sortedBy { it.takenAt }
            for (row in rows.rows) {
                val measurement = Measurement(
                    profileId = profile.id,
                    takenAt = row.takenAt.atZone(zone).toInstant(),
                    zone = zone,
                    source = MeasurementSource.IMPORT,
                    weightKg = row.weightKg,
                    person = person,
                    impedances = listOfNotNull(row.impedance?.let { Impedance(Impedance.FOOT_TO_FOOT, it) }),
                    heartRateBpm = row.heartRate,
                    manualValues = row.manualValues,
                    importedValues = row.values,
                    note = row.note,
                    tags = row.tags,
                )
                if (!measurement.isAfterBirth) {
                    beforeBirth++
                    continue
                }
                fun same(other: Measurement) =
                    abs(Duration.between(other.takenAt, measurement.takenAt).seconds) < SAME_TIME.seconds &&
                        abs(other.weightKg - measurement.weightKg) <= SAME_KG + 1e-9
                if (sorted.any(::same) || kept.any(::same)) {
                    duplicates++
                    continue
                }
                kept += measurement
            }
            return ImportPlan(kept.sortedBy { it.takenAt }, duplicates, beforeBirth, rows.unreadable)
        }
    }
}
