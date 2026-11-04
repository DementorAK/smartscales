package dev.kinash.smartscales.core.data

import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import java.time.Instant

/**
 * A paired scale. Unpairing is soft: the binding is marked [unbound] and keeps [impedanceFactor] and [v2Key],
 * so the history from this scale does not change, and pairing the same [mac] again restores it.
 *
 * @property mac the scale's own MAC, the identity of the binding. For `C0` advertisements it is decrypted
 *   from the frame and may differ from [bleAddress]; the V2 key is requested by this MAC.
 * @property bleAddress address Android saw the scale at last time; not unique and may change.
 * @property protocol driver identifier from :core:ble.
 * @property v2Key key for encrypted V2 advertisements, as entered or imported. Stored as plain text:
 *   it only opens weight and impedance of this scale, and the database is private to the app.
 * @property v2KeyConfirmed the person confirmed that the weight decrypted with [v2Key] matches the scale
 *   display. A wrong key gives a plausible weight, so weighings are not saved by themselves before that.
 * @property impedanceFactor calibration factor `k` applied to raw impedances before calculations.
 * @property historyAcceptedUntil time of the newest record from the scale's memory taken so far. Scales send
 *   their memory on every connection; records up to this time are skipped, so a record the person deleted
 *   does not come back. Null until the first connection, which takes the whole memory.
 * @property displayUnit the unit set on the scale display when connecting, for scales that take one.
 * @property syncTime set the scale clock from the phone when connecting, for scales with a separate time
 *   command. Off for a scale whose clock should be left alone.
 */
data class ScaleBinding(
    val id: Long = 0,
    val mac: String,
    val bleAddress: String? = null,
    val name: String? = null,
    val protocol: String,
    val protocolVersion: String? = null,
    val productId: Int? = null,
    val v2Key: String? = null,
    val v2KeyConfirmed: Boolean = false,
    val impedanceFactor: Double = 1.0,
    val unbound: Boolean = false,
    val boundAt: Instant,
    val historyAcceptedUntil: Instant? = null,
    val displayUnit: ScaleDisplayUnit = ScaleDisplayUnit.PROFILE,
    val syncTime: Boolean = true,
) {
    init {
        require(impedanceFactor > 0) { "Calibration factor must be positive: $impedanceFactor" }
    }
}

/** The unit a scale shows: that of the profile on the main screen, a fixed one, or whatever the scale has. */
enum class ScaleDisplayUnit { PROFILE, KILOGRAM, POUND, STONE, KEEP }

internal fun ScaleBindingEntity.toScaleBinding() = ScaleBinding(
    id, mac, bleAddress, name, protocol, protocolVersion, productId, v2Key, v2KeyConfirmed, impedanceFactor, unbound,
    boundAt, historyAcceptedUntil, ScaleDisplayUnit.entries.firstOrNull { it.name == displayUnit } ?: ScaleDisplayUnit.PROFILE,
    syncTime,
)

internal fun ScaleBinding.toEntity() = ScaleBindingEntity(
    id, mac, bleAddress, name, protocol, protocolVersion, productId, v2Key, v2KeyConfirmed, impedanceFactor, unbound,
    boundAt, historyAcceptedUntil, displayUnit.name, syncTime,
)
