package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.ProfileGoalEntity
import java.time.Instant

/**
 * A profile's goal for one indicator, a group or the weight: [target] in the indicator's own unit (kilograms,
 * centimetres, percent), set or last changed at [setAt], where the progress is counted from.
 */
data class Goal(val indicatorId: String, val target: Double, val setAt: Instant)

internal fun ProfileGoalEntity.toGoal() = Goal(indicatorId, targetValue, setAt)
