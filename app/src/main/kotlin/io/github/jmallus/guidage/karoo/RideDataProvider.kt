package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.ZoneRange
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Les valeurs chiffrées affichées par le tableau de bord. */
data class RideData(
    val speed: Double? = null,
    /** Moyenne de la sortie, à laquelle la vitesse instantanée est comparée. */
    val averageSpeed: Double? = null,
    val power: Double? = null,
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val grade: Double? = null,
    val distanceRemaining: Double? = null,
    val arrivalTime: Double? = null,
    /** Zones réglées sur l'appareil, qui donnent leur couleur aux cases. */
    val powerZones: List<ZoneRange> = emptyList(),
    val heartRateZones: List<ZoneRange> = emptyList(),
)

/**
 * Agrège les flux de données de la sortie en un état unique, partagé par les champs.
 *
 * Les moyennes lissées sur 3 s sont préférées aux valeurs instantanées : c'est ce qui
 * se lit le mieux en roulant, les valeurs brutes sautant trop pour être suivies à l'œil.
 */
class RideDataProvider(
    private val karooSystem: KarooSystemService,
    scope: CoroutineScope,
) {
    val data: StateFlow<RideData> = combine(metrics(), zones()) { values, profile ->
        RideData(
            speed = values[0],
            averageSpeed = values[1],
            power = values[2],
            heartRate = values[3],
            cadence = values[4],
            grade = values[5],
            distanceRemaining = values[6],
            arrivalTime = values[7],
            powerZones = profile.first,
            heartRateZones = profile.second,
        )
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RideData())

    private fun metrics(): Flow<Array<Double?>> = combine(
        listOf(
            value(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED),
            value(DataType.Type.AVERAGE_SPEED),
            value(DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
            value(DataType.Type.HEART_RATE),
            value(DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE),
            value(DataType.Type.ELEVATION_GRADE),
            value(DataType.Type.DISTANCE_TO_DESTINATION),
            value(DataType.Type.TIME_OF_ARRIVAL),
        ),
    ) { it }

    /** Zones de puissance et de fréquence cardiaque telles que réglées sur l'appareil. */
    private fun zones(): Flow<Pair<List<ZoneRange>, List<ZoneRange>>> =
        karooSystem.consumerFlow<UserProfile>()
            .map { profile ->
                profile.powerZones.map { it.toRange() } to profile.heartRateZones.map { it.toRange() }
            }
            .onStart { emit(emptyList<ZoneRange>() to emptyList()) }

    private fun value(dataTypeId: String) =
        karooSystem.streamValueFlow(dataTypeId).onStart { emit(null) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun UserProfile.Zone.toRange() = ZoneRange(min, max)
    }
}
