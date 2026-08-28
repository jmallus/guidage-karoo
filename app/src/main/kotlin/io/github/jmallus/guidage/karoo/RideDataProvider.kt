package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.ClimbProgress
import io.github.jmallus.guidage.core.Drivetrain
import io.github.jmallus.guidage.core.LearnedPace
import io.github.jmallus.guidage.core.PaceLearner
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
    /** Distance parcourue depuis le départ (m). */
    val distance: Double? = null,
    val distanceRemaining: Double? = null,
    val arrivalTime: Double? = null,
    /** Rapport engagé, quand le groupe le rapporte. */
    val drivetrain: Drivetrain = Drivetrain.UNKNOWN,
    /** Côte en cours, telle que le Karoo la suit lui-même. */
    val climb: ClimbProgress = ClimbProgress.NONE,
    /**
     * Faux quand le Karoo estime qu'on a quitté l'itinéraire.
     *
     * Null tant qu'il ne se prononce pas — hors navigation, par exemple.
     */
    val onRoute: Boolean? = null,
    /** Zones réglées sur l'appareil, qui donnent leur couleur aux cases. */
    val powerZones: List<ZoneRange> = emptyList(),
    val heartRateZones: List<ZoneRange> = emptyList(),
    /** Allure apprise depuis le départ, dont se déduit l'heure d'arrivée. */
    val pace: LearnedPace = LearnedPace.UNKNOWN,
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Ce que le coureur tient aujourd'hui, appris au fil de la sortie.
     *
     * Il vit ici et non dans le champ : les deux vitesses se mesurent sur la sortie entière,
     * pas sur la durée d'affichage d'une page. Un champ posé sur la troisième page
     * hériterait sans cela d'une allure apprise en trois minutes.
     */
    private val paceLearner = PaceLearner()
    private var lastObservationMillis: Long? = null
    private var lastDistance: Double? = null

    val data: StateFlow<RideData> = combine(
        metrics(),
        zones(),
        gears(),
        climb(),
    ) { values, profile, drivetrain, climb ->
        observePace(speed = values[0], grade = values[5], power = values[2], distance = values[6])
        RideData(
            speed = values[0],
            averageSpeed = values[1],
            power = values[2],
            heartRate = values[3],
            cadence = values[4],
            grade = values[5],
            distance = values[6],
            distanceRemaining = values[7],
            arrivalTime = values[8],
            drivetrain = drivetrain,
            climb = climb,
            onRoute = values[9]?.let { it > 0.5 },
            powerZones = profile.first,
            heartRateZones = profile.second,
            pace = paceLearner.pace,
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
            value(DataType.Type.DISTANCE),
            // Ce type porte aussi l'état de navigation : il faut nommer le champ voulu.
            field(DataType.Type.DISTANCE_TO_DESTINATION, DataType.Field.DISTANCE_TO_DESTINATION),
            value(DataType.Type.TIME_OF_ARRIVAL),
            field(DataType.Type.DISTANCE_TO_DESTINATION, DataType.Field.ON_ROUTE),
        ),
    ) { it }

    /** Zones de puissance et de fréquence cardiaque telles que réglées sur l'appareil. */
    private fun zones(): Flow<Pair<List<ZoneRange>, List<ZoneRange>>> =
        karooSystem.consumerFlow<UserProfile>()
            .map { profile ->
                profile.powerZones.map { it.toRange() } to profile.heartRateZones.map { it.toRange() }
            }
            .onStart { emit(emptyList<ZoneRange>() to emptyList()) }

    /**
     * Rapport engagé, lu d'un seul flux.
     *
     * Les six champs — plateau, pignon, leur nombre et leurs dentures — voyagent dans le
     * même point de donnée : les lire séparément multiplierait les abonnements pour rien,
     * et rien ne garantirait qu'ils décrivent le même instant.
     */
    private fun gears(): Flow<Drivetrain> =
        karooSystem.streamFieldsFlow(DataType.Type.SHIFTING_GEARS)
            .map { fields ->
                Drivetrain(
                    front = fields[DataType.Field.SHIFTING_FRONT_GEAR]?.toInt(),
                    frontCount = fields[DataType.Field.SHIFTING_FRONT_GEAR_MAX]?.toInt(),
                    frontTeeth = fields[DataType.Field.SHIFTING_FRONT_GEAR_TEETH]?.toInt(),
                    rear = fields[DataType.Field.SHIFTING_REAR_GEAR]?.toInt(),
                    rearCount = fields[DataType.Field.SHIFTING_REAR_GEAR_MAX]?.toInt(),
                    rearTeeth = fields[DataType.Field.SHIFTING_REAR_GEAR_TEETH]?.toInt(),
                )
            }
            .onStart { emit(Drivetrain.UNKNOWN) }

    /**
     * Côte en cours, telle que le Karoo la suit.
     *
     * On préfère ses valeurs aux nôtres : lui sait exactement où commence et où finit la
     * côte qu'il a identifiée, là où nous ne pouvons que la situer d'après une distance
     * parcourue reconstituée, dont le moindre décalage fait apparaître le profil après la
     * bosse. Le rang de la côte vient de la même source, pour la même raison.
     */
    private fun climb(): Flow<ClimbProgress> = combine(
        karooSystem.streamFieldsFlow(DataType.Type.CLIMB).onStart { emit(emptyMap()) },
        karooSystem.streamFieldsFlow(DataType.Type.CLIMB_NUMBER).onStart { emit(emptyMap()) },
    ) { climb, numbering ->
        ClimbProgress(
            distanceFromBottom = climb[DataType.Field.DISTANCE_FROM_BOTTOM],
            distanceToTop = climb[DataType.Field.DISTANCE_TO_TOP],
            elevationToTop = climb[DataType.Field.ELEVATION_TO_TOP],
            totalElevation = climb[DataType.Field.CLIMB_ELEVATION],
            number = numbering[DataType.Field.CLIMB_NUMBER]?.toInt(),
            totalClimbs = numbering[DataType.Field.TOTAL_CLIMBS]?.toInt(),
        )
    }

    /**
     * Nourrit l'allure d'un relevé, et l'oublie quand une nouvelle sortie commence.
     *
     * Le compteur de distance qui recule est le seul signal fiable de départ : l'état de la
     * sortie passe aussi par « en pause », d'où l'on repart sans avoir rien oublié.
     */
    private fun observePace(speed: Double?, grade: Double?, power: Double?, distance: Double?) {
        val now = clock()
        if (distance != null) {
            val previous = lastDistance
            if (previous != null && distance < previous - NEW_RIDE_DROP_METERS) {
                paceLearner.reset()
                lastObservationMillis = null
            }
            lastDistance = distance
        }
        val previousMillis = lastObservationMillis
        lastObservationMillis = now
        if (previousMillis == null) return
        paceLearner.observe(
            deltaSeconds = (now - previousMillis) / 1_000.0,
            speedMetersPerSecond = speed,
            gradePercent = grade,
            powerWatts = power,
        )
    }

    private fun value(dataTypeId: String) =
        karooSystem.streamValueFlow(dataTypeId).onStart { emit(null) }

    private fun field(dataTypeId: String, fieldId: String) =
        karooSystem.streamFieldFlow(dataTypeId, fieldId).onStart { emit(null) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Recul du compteur au-delà duquel on tient la sortie pour nouvelle (m). */
        const val NEW_RIDE_DROP_METERS = 100.0

        fun UserProfile.Zone.toRange() = ZoneRange(min, max)
    }
}
