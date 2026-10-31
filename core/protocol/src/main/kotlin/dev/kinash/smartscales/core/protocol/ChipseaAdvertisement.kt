package dev.kinash.smartscales.core.protocol

/** Chipsea protocol families recognised by their advertisement. */
enum class ChipseaProtocol {
    /** `CA 20`: weight and impedance in the advertisement, no connection. */
    OKOK_BROADCAST,

    /** `CA 10` / `CA 11`: a GATT scale; the advertisement carries the weight for preview. */
    OKOK,

    /** `FF F0`: a GATT scale; the advertisement carries the weight for preview. */
    OKOK_CLOUD,

    /** `FF F0 10/11`: a GATT scale with up to 8 electrodes; the advertisement carries the weight for preview. */
    OKOK_CLOUD_V4,

    /** `C0`: weight and impedance in the advertisement, possibly encrypted. */
    OKOK_V2,
    ;

    /** Weighing works from advertisements alone; the other families need a GATT connection to lock a weight. */
    val isBroadcastOnly: Boolean get() = this == OKOK_BROADCAST || this == OKOK_V2

    companion object {
        /** The protocol of a manufacturer data block (with the company id bytes), or null for other devices. */
        fun detect(manufacturerData: ByteArray): ChipseaProtocol? {
            if (manufacturerData.size < 2) return null
            val first = manufacturerData[0].toUByteInt()
            val second = manufacturerData[1].toUByteInt()
            return when {
                first == 0xCA && second == 0x20 -> OKOK_BROADCAST
                first == 0xCA -> OKOK
                first == 0xC0 -> OKOK_V2
                first == 0xFF && second == 0xF0 -> {
                    val version = manufacturerData.getOrNull(2)?.toUByteInt()
                    if (version == 0x10 || version == 0x11) OKOK_CLOUD_V4 else OKOK_CLOUD
                }
                else -> null
            }
        }
    }
}

/**
 * One parsed advertisement of a Chipsea scale.
 *
 * @property scaleMac the scale's own MAC when the frame carries it (encrypted `C0`), otherwise null and the
 *   address Android reports is the scale's MAC.
 * @property weight null when the frame carries no weight, for example `CA 20` before the weight is locked or
 *   an encrypted `C0` without a key.
 * @property impedanceOhm foot-to-foot impedance, or null when the frame has none or it is zero.
 * @property locked the weight is final; only broadcast-only protocols lock in advertisements.
 * @property measureSeqNo number of the weighing, the same in all frames of one weighing.
 * @property encrypted `C0` frame with encrypted weight and impedance.
 * @property needsKey encrypted, and no key was given: the weight cannot be read.
 */
data class ChipseaAdvertisement(
    val protocol: ChipseaProtocol,
    val version: Int? = null,
    val productId: Int? = null,
    val scaleMac: String? = null,
    val property: ScaleProperty? = null,
    val weight: ScaleWeight? = null,
    val impedanceOhm: Double? = null,
    val locked: Boolean = false,
    val measureSeqNo: Int? = null,
    val measuresImpedance: Boolean = false,
    val encrypted: Boolean = false,
    val needsKey: Boolean = false,
) {
    companion object {
        /**
         * Parses a manufacturer data block (with the company id bytes) of a Chipsea scale, or returns null when
         * it is not one or is too short. [v2Key] decrypts encrypted `C0` frames.
         */
        fun parse(manufacturerData: ByteArray, v2Key: ByteArray? = null): ChipseaAdvertisement? {
            val protocol = ChipseaProtocol.detect(manufacturerData) ?: return null
            return when (protocol) {
                ChipseaProtocol.OKOK_BROADCAST -> parseBroadcast(manufacturerData)
                ChipseaProtocol.OKOK -> parseOkok(manufacturerData)
                ChipseaProtocol.OKOK_CLOUD, ChipseaProtocol.OKOK_CLOUD_V4 -> parseCloud(manufacturerData, protocol)
                ChipseaProtocol.OKOK_V2 -> parseV2(manufacturerData, v2Key)
            }
        }

        /** `CA 20`: `[3..6]` product, `[7]` 0 = weight only, `[8]` property, then seqNo, weight and impedance. */
        private fun parseBroadcast(d: ByteArray): ChipseaAdvertisement? {
            if (d.size < 14) return null
            val property = ScaleProperty(d[8].toUByteInt())
            val base = ChipseaAdvertisement(
                protocol = ChipseaProtocol.OKOK_BROADCAST,
                version = d[1].toUByteInt(),
                productId = d.uIntBe(3, 4),
                property = property,
                measuresImpedance = d[7].toInt() != 0,
            )
            // The scale sends the weight only once it is locked
            if (!property.locked) return base
            return base.copy(
                weight = ScaleWeight.of(d.uIntBe(10, 2), property, DigitsLayout.OKOK),
                impedanceOhm = impedance(d.uIntBe(12, 2)),
                locked = true,
                measureSeqNo = d[9].toUByteInt(),
            )
        }

        /** `CA 10` / `CA 11`: device type and property swap places between the versions. */
        private fun parseOkok(d: ByteArray): ChipseaAdvertisement? {
            if (d.size < 7) return null
            val version = d[1].toUByteInt()
            val deviceType = d[if (version == 0x10) 3 else 4].toUByteInt()
            val propertyBits = d[if (version == 0x10) 4 else 3].toUByteInt()
            val base = ChipseaAdvertisement(protocol = ChipseaProtocol.OKOK, version = version)
            if (deviceType == 0 && version == 0x10) {
                // Weight-only v1.0 scales: kilograms × 10 at [8..9], no property byte
                if (d.size < 10) return null
                return base.copy(weight = ScaleWeight(d.uIntBe(8, 2), ScaleUnit.KILOGRAM, 1))
            }
            if (d.size < 11) return null
            val property = ScaleProperty(propertyBits)
            return base.copy(
                productId = d.uIntBe(7, 4),
                property = property,
                weight = ScaleWeight.of(d.uIntBe(5, 2), property, DigitsLayout.OKOK),
                measuresImpedance = deviceType != 0,
            )
        }

        /** `FF F0`: `[2]` version, `[3]` property, little-endian weight `[4..5]`, product `[6..9]`. */
        private fun parseCloud(d: ByteArray, protocol: ChipseaProtocol): ChipseaAdvertisement? {
            if (d.size < 10) return null
            val productId = d.uIntBe(6, 4)
            // Two products send a meaningless property: kilograms with one decimal
            val property = ScaleProperty(if (productId in CLOUD_FIXED_PROPERTY_PRODUCTS) 0x04 else d[3].toUByteInt())
            val layout = if (protocol == ChipseaProtocol.OKOK_CLOUD_V4) DigitsLayout.CLOUD_V4 else DigitsLayout.CLOUD
            return ChipseaAdvertisement(
                protocol = protocol,
                version = d[2].toUByteInt(),
                productId = productId,
                property = property,
                weight = ScaleWeight.of((d[5].toUByteInt() shl 8) or d[4].toUByteInt(), property, layout),
            )
        }

        /**
         * `C0`: `[1]` seqNo, `[2..3]` weight, `[4..5]` impedance with the encryption flag in the top bit,
         * `[6..7]` product, `[8]` property; encrypted frames add the scale's MAC in `[9..14]`.
         */
        private fun parseV2(d: ByteArray, key: ByteArray?): ChipseaAdvertisement? {
            if (d.size < 9) return null
            val property = ScaleProperty(d[8].toUByteInt())
            val encrypted = d[4].toUByteInt() and 0x80 != 0
            var weightRaw = d.uIntBe(2, 2)
            var impedanceRaw = d.uIntBe(4, 2)
            var scaleMac: String? = null
            if (encrypted) {
                if (d.size >= 15) {
                    val macKey = d.copyOfRange(11, 15)
                    val head = V2Cipher.decrypt(d.uIntBe(9, 2), macKey)
                    scaleMac = macOf(byteArrayOf((head shr 8).toByte(), head.toByte()) + macKey)
                }
                if (key != null) {
                    weightRaw = V2Cipher.decrypt(weightRaw, key)
                    impedanceRaw = V2Cipher.decrypt(impedanceRaw, key) and 0x7FFF
                }
            }
            val readable = !encrypted || key != null
            return ChipseaAdvertisement(
                protocol = ChipseaProtocol.OKOK_V2,
                productId = d.uIntBe(6, 2),
                scaleMac = scaleMac,
                property = property,
                weight = if (readable) ScaleWeight.of(weightRaw, property, DigitsLayout.OKOK) else null,
                impedanceOhm = if (readable) impedance(impedanceRaw) else null,
                locked = property.locked,
                measureSeqNo = d[1].toUByteInt(),
                measuresImpedance = property.measuresImpedance,
                encrypted = encrypted,
                needsKey = !readable,
            )
        }

        private fun impedance(raw: Int): Double? = if (raw == 0) null else raw / 10.0

        private val CLOUD_FIXED_PROPERTY_PRODUCTS = setOf(0xFAFBFCFD.toInt(), 1_104_000_001)
    }
}
