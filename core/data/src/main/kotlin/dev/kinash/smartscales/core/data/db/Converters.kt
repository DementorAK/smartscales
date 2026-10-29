package dev.kinash.smartscales.core.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Column formats: instants as epoch milliseconds, dates as ISO-8601 text, zones as region IDs. */
class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun dateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun textToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun zoneToText(value: ZoneId?): String? = value?.id

    @TypeConverter
    fun textToZone(value: String?): ZoneId? = value?.let(ZoneId::of)
}
