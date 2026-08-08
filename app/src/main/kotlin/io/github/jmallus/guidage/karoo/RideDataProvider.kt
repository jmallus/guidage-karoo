package io.github.jmallus.guidage.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
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
    val power: Double? = null,
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val distanceRemaining: Double? = null,
    val arrivalTime: Double? = null,
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
    val data: StateFlow<RideData> = metrics()
        .map { values ->
            RideData(
                speed = values[0],
                power = values[1],
                heartRate = values[2],
                cadence = values[3],
                distanceRemaining = values[4],
                arrivalTime = values[5],
            )
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RideData())

    private fun metrics(): Flow<Array<Double?>> = combine(
        listOf(
            value(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED),
            value(DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
            value(DataType.Type.HEART_RATE),
            value(DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE),
            value(DataType.Type.DISTANCE_TO_DESTINATION),
            value(DataType.Type.TIME_OF_ARRIVAL),
        ),
    ) { it }

    private fun value(dataTypeId: String) =
        karooSystem.streamValueFlow(dataTypeId).onStart { emit(null) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
