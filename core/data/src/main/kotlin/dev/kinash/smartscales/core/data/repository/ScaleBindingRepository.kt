package dev.kinash.smartscales.core.data.repository

import androidx.room.withTransaction
import dev.kinash.smartscales.core.data.ScaleBinding
import dev.kinash.smartscales.core.data.db.SmartScalesDatabase
import dev.kinash.smartscales.core.data.db.entity.ScaleBindingEntity
import dev.kinash.smartscales.core.data.toEntity
import dev.kinash.smartscales.core.data.toScaleBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScaleBindingRepository @Inject constructor(
    private val database: SmartScalesDatabase,
    private val clock: Clock,
) {
    private val dao = database.scaleBindingDao()

    /** Scales shown in the list and used when scanning. */
    fun observeBound(): Flow<List<ScaleBinding>> = dao.observeBound().map { list -> list.map { it.toScaleBinding() } }

    suspend fun get(id: Long): ScaleBinding? = dao.get(id)?.toScaleBinding()

    suspend fun getByMac(mac: String): ScaleBinding? = dao.getByMac(normalizeMac(mac))?.toScaleBinding()

    /**
     * Pairs a scale by its own [mac] (see [ScaleBinding.mac]). A scale with the same MAC that was unpaired
     * before is restored with its calibration factor, V2 key and name; the address and protocol details
     * are refreshed.
     */
    suspend fun bind(
        mac: String,
        bleAddress: String?,
        protocol: String,
        protocolVersion: String?,
        productId: Int?,
    ): Long = database.withTransaction {
        val normalizedMac = normalizeMac(mac)
        val address = bleAddress?.let(::normalizeMac)
        val existing = dao.getByMac(normalizedMac)
        if (existing == null) {
            dao.insert(
                ScaleBindingEntity(
                    mac = normalizedMac,
                    bleAddress = address,
                    protocol = protocol,
                    protocolVersion = protocolVersion,
                    productId = productId,
                    boundAt = clock.instant(),
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    bleAddress = address,
                    protocol = protocol,
                    protocolVersion = protocolVersion,
                    productId = productId,
                    unbound = false,
                    boundAt = clock.instant(),
                ),
            )
            existing.id
        }
    }

    /** Soft unpairing: the scale is hidden, its history and calibration stay. */
    suspend fun unbind(id: Long) = dao.unbind(id)

    /** Saves name, address, V2 key or calibration factor. A new factor changes indicators of the whole history. */
    suspend fun update(binding: ScaleBinding) = dao.update(binding.copy(mac = normalizeMac(binding.mac)).toEntity())

    private fun normalizeMac(mac: String) = mac.uppercase()
}
