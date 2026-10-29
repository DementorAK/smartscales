package dev.kinash.smartscales.core.data.db.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class MeasurementWithDetails(
    @Embedded val measurement: MeasurementEntity,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val impedances: List<ImpedanceEntity>,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val manualValues: List<ManualValueEntity>,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val importedValues: List<ImportedValueEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(MeasurementTagEntity::class, parentColumn = "measurementId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
)
