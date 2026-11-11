package dev.kinash.smartscales.core.model

/** Identifiers of values a scale works out itself, kept with the measurement next to the raw data. */
object ScaleValues {
    const val FAT_PERCENT = "scale_fat_percent"
    const val WATER_PERCENT = "scale_water_percent"
    const val MUSCLE_PERCENT = "scale_muscle_percent"
    const val MUSCLE_KG = "scale_muscle_kg"
    const val BMR_KCAL = "scale_bmr_kcal"
    const val VISCERAL_FAT = "scale_visceral_fat"
    const val BONE_KG = "scale_bone_kg"

    // Brought only by an import of another app's table (requirements §4.3)
    const val PROTEIN_PERCENT = "scale_protein_percent"
    const val BODY_AGE = "scale_body_age"
    const val SKELETAL_MUSCLE_PERCENT = "scale_skeletal_muscle_percent"
    const val LEAN_MASS_KG = "scale_lean_mass_kg"

    /** Every one of them: formulas get each as an input of that name (requirements §4.3). */
    val all: List<String> = listOf(
        FAT_PERCENT, WATER_PERCENT, MUSCLE_PERCENT, MUSCLE_KG, BMR_KCAL, VISCERAL_FAT, BONE_KG,
        PROTEIN_PERCENT, BODY_AGE, SKELETAL_MUSCLE_PERCENT, LEAN_MASS_KG,
    )
}
