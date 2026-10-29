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
 * @property impedanceFactor calibration factor `k` applied to raw impedances before calculations.
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
    val impedanceFactor: Double = 1.0,
    val unbound: Boolean = false,
    val boundAt: Instant,
) {
    init {
        require(impedanceFactor > 0) { "Calibration factor must be positive: $impedanceFactor" }
    }
}

internal fun ScaleBindingEntity.toScaleBinding() = ScaleBinding(
    id, mac, bleAddress, name, protocol, protocolVersion, productId, v2Key, impedanceFactor, unbound, boundAt,
)

internal fun ScaleBinding.toEntity() = ScaleBindingEntity(
    id, mac, bleAddress, name, protocol, protocolVersion, productId, v2Key, impedanceFactor, unbound, boundAt,
)
