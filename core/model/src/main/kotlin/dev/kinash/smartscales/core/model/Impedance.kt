package dev.kinash.smartscales.core.model

/**
 * One impedance value exactly as reported by the scale, before the per-scale calibration factor is applied.
 *
 * @property channel [FOOT_TO_FOOT] for 4-electrode scales; for 8-electrode scales the channel number
 *   (1..[MAX_SEGMENT_CHANNEL]) in the order the scale sends them. Mapping channels to body segments is
 *   a property of the scale protocol, not of the stored data.
 * @property frequencyIndex 0-based index of the measurement frequency in the order the scale sends them
 *   (multi-frequency scales such as CloudV4 `CB` send two values per channel without stating the frequencies).
 * @property frequencyKhz actual frequency, or null when the protocol does not report it.
 */
data class Impedance(
    val channel: Int,
    val ohms: Double,
    val frequencyIndex: Int = 0,
    val frequencyKhz: Int? = null,
) {
    init {
        require(channel in FOOT_TO_FOOT..MAX_SEGMENT_CHANNEL) { "Unknown impedance channel: $channel" }
        require(frequencyIndex in 0 until MAX_FREQUENCIES) { "Unknown frequency index: $frequencyIndex" }
        require(frequencyKhz == null || frequencyKhz > 0) { "Frequency must be positive: $frequencyKhz" }
        require(ohms > 0) { "Impedance must be positive: $ohms" }
    }

    companion object {
        const val FOOT_TO_FOOT = 0
        const val MAX_SEGMENT_CHANNEL = 5
        const val MAX_FREQUENCIES = 2

        /** Up to 5 channels at up to 2 frequencies (CloudV4 `CB` frames). */
        const val MAX_PER_MEASUREMENT = 10
    }
}
