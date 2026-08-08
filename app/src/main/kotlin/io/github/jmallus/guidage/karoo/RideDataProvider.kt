package io.github.jmallus.guidage.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * La côte en cours, telle que le Karoo la détecte lui-même.
 *
 * Ce sont les valeurs qui alimentent le champ « côte » natif : on les reprend telles
 * quelles plutôt que de redécouper l'itinéraire de notre côté, pour que l'affichage
 * coïncide avec ce que l'appareil annonce par ailleurs.
 */
data class ClimbData(
    val distanceFromBottom: Double? = null,
    val distanceToTop: Double? = null,
    val elevationFromBottom: Double? = null,
    val elevationToTop: Double? = null,
    /** Dénivelé total de la côte (m). */
    val totalElevation: Double? = null,
    val number: Int? = null,
    val totalClimbs: Int? = null,
) {
    /** true dès que le Karoo signale une côte en cours. */
    val active: Boolean
        get() = distanceToTop != null && (distanceToTop > 0.0 || (distanceFromBottom ?: 0.0) > 0.0)

    /** Longueur totale de la côte (m), déduite des deux distances. */
    val length: Double?
        get() {
            val from = distanceFromBottom ?: return null
            val to = distanceToTop ?: return null
            return (from + to).takeIf { it > 0 }
        }

    /** Progression dans la côte, de 0 à 1. */
    val progress: Float
        get() {
            val total = length ?: return 0f
            return ((distanceFromBottom ?: 0.0) / total).coerceIn(0.0, 1.0).toFloat()
        }

    /** Pente moyenne de la côte (%). */
    val grade: Double?
        get() {
            val total = length ?: return null
            val elevation = totalElevation ?: return null
            return elevation / total * 100.0
        }
}

/** Les valeurs chiffrées affichées par le tableau de bord. */
data class RideData(
    val speed: Double? = null,
    val power: Double? = null,
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val distanceRemaining: Double? = null,
    val arrivalTime: Double? = null,
    val climb: ClimbData = ClimbData(),
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
    val data: StateFlow<RideData> = combine(metrics(), climb()) { values, climb ->
        RideData(
            speed = values[0],
            power = values[1],
            heartRate = values[2],
            cadence = values[3],
            distanceRemaining = values[4],
            arrivalTime = values[5],
            climb = climb,
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

    private fun climb(): Flow<ClimbData> = combine(
        fields(DataType.Type.CLIMB),
        fields(DataType.Type.CLIMB_NUMBER),
    ) { climb, number ->
        ClimbData(
            distanceFromBottom = climb[DataType.Field.DISTANCE_FROM_BOTTOM],
            distanceToTop = climb[DataType.Field.DISTANCE_TO_TOP],
            elevationFromBottom = climb[DataType.Field.ELEVATION_FROM_BOTTOM],
            elevationToTop = climb[DataType.Field.ELEVATION_TO_TOP],
            totalElevation = climb[DataType.Field.CLIMB_ELEVATION],
            number = number[DataType.Field.CLIMB_NUMBER]?.toInt(),
            totalClimbs = number[DataType.Field.TOTAL_CLIMBS]?.toInt(),
        )
    }

    private fun value(dataTypeId: String) =
        karooSystem.streamValueFlow(dataTypeId).onStart { emit(null) }

    private fun fields(dataTypeId: String) =
        karooSystem.streamFieldsFlow(dataTypeId).onStart { emit(emptyMap()) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
