package dev.kinash.smartscales.core.model

enum class Sex {
    MALE,
    FEMALE,
    ;

    /** Numeric value used by published body composition equations: 1 for male, 0 for female. */
    val formulaValue: Int
        get() = if (this == MALE) 1 else 0
}
