package dev.kinash.smartscales.core.data

import java.util.Locale

/** A tag as compared: "Morning " and "morning" are one tag, shown as first written. */
fun normalizeTagName(name: String): String = name.trim().lowercase(Locale.ROOT)
