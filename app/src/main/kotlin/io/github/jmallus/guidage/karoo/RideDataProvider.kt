package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.BatteryDrain
import io.github.jmallus.guidage.core.ClimbProgress
import io.github.jmallus.guidage.core.DriftTracker
import io.github.jmallus.guidage.core.Drivetrain
import io.github.jmallus.guidage.core.LearnedPace
import io.github.jmallus.guidage.core.PaceLearner
import io.github.jmallus.guidage.core.RideLevel
import io.github.jmallus.guidage.core.WPrime
import io.github.jmallus.guidage.core.WPrimeSettings
import io.github.jmallus.guidage.core.WPrimeTracker
import io.github.jmallus.guidage.core.ZoneClock
import io.github.jmallus.guidage.core.ZoneRange
import io.github.jmallus.guidage.core.Zones
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Le coureur tel que le Karoo le connaît : ses zones, son seuil, son poids.
 *
 * Rassemblés en un seul objet parce qu'ils arrivent tous du même événement `UserProfile` :
 * les lire séparément multiplierait les abonnements sans rien garantir sur la simultanéité.
 */
data class RiderProfile(
    val powerZones: List<ZoneRange> = emptyList(),
    val heartRateZones: List<ZoneRange> = emptyList(),
    /** Puissance au seuil réglée sur l'appareil (W), à défaut de puissance critique mesurée. */
    val ftp: Int? = null,
    val weightKilograms: Double? = null,
)

/** Les valeurs chiffrées affichées par le tableau de bord. */
data class RideData(
    val speed: Double? = null,
    /** Moyenne de la sortie, à laquelle la vitesse instantanée est comparée. */
    val averageSpeed: Double? = null,
    val power: Double? = null,
    val heartRate: Double? = null,
    val cadence: Double? = null,
    val grade: Double? = null,
    /**
     * Kilojoules déjà produits depuis le départ, que le Karoo intègre lui-même.
     *
     * C'est la moitié mesurée du champ « Budget d'effort » : le budget dit ce que coûte la
     * suite, celle-ci dit ce qui est déjà payé. Aucun modèle derrière — c'est l'intégrale de
     * la puissance, et elle n'existe donc pas sans capteur.
     */
    val energyOutput: Double? = null,
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
    /** Ce que la sortie vaut depuis le départ : moyennes, maxima, répartition. */
    val level: RideLevel = RideLevel.UNKNOWN,
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
    /**
     * Les paramètres de la réserve anaérobie, qui viennent des réglages de l'application.
     *
     * C'est le seul réglage que les relevés lisent : les autres décident de ce qu'on montre,
     * celui-ci décide de ce qu'on calcule, et le calcul se mène sur la sortie entière.
     */
    private val wPrimeSettings: Flow<WPrimeSettings> = flowOf(WPrimeSettings()),
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

    /**
     * Vrai dès qu'une cadence a été rapportée depuis le début de la sortie.
     *
     * Le Karoo cesse d'émettre la cadence quand on arrête de pédaler : il ne dit pas « zéro »,
     * il ne dit plus rien, et la case affichait « -- » — c'est-à-dire « je ne sais pas », là
     * où l'on sait très bien. Une fois le capteur entendu, son silence veut dire zéro.
     *
     * Avant de l'avoir entendu, « -- » reste juste : sans capteur, la cadence est inconnue et
     * non nulle. C'est tout l'intérêt de retenir ce fait plutôt que d'écrire zéro d'emblée.
     */
    private var cadenceVue = false

    /**
     * Le temps passé dans chaque zone de fréquence cardiaque.
     *
     * Il vit ici pour la même raison que l'allure apprise : il se mesure sur la sortie
     * entière, non sur la durée d'affichage d'une page. Le Karoo publie la zone courante,
     * jamais le temps qu'on y a passé.
     */
    private val zoneClock = ZoneClock()

    /** La réserve anaérobie, pour la même raison : elle se vide et se remplit sur la sortie. */
    private val wPrime = WPrimeTracker()

    /** La dérive aérobie et la décharge de l'appareil, deux cumuls de plus que rien ne publie. */
    private val drift = DriftTracker()
    private val battery = BatteryDrain()

    val data: StateFlow<RideData> = combine(
        metrics(),
        profile(),
        gears(),
        climb(),
        bilan(),
    ) { values, profile, drivetrain, climb, bilan ->
        val (summary, reglages) = bilan
        observePace(speed = values[0], grade = values[5], power = values[2], distance = values[6])
        if (values[4] != null) cadenceVue = true
        zoneClock.observe(clock(), Zones.zoneOf(values[3] ?: 0.0, profile.heartRateZones))
        // Les paramètres réglés priment sur ce que l'appareil sait du coureur : la FTP et le
        // poids ne sont qu'un point de départ, et qui a mesuré les siens a mieux.
        wPrime.observe(
            nowMillis = clock(),
            powerWatts = values[2],
            criticalPower = (reglages.criticalPower ?: profile.ftp)?.toDouble(),
            capacityJoules = reglages.capacityJoules?.toDouble()
                ?: profile.weightKilograms?.let { WPrime.defaultCapacity(it) },
        )
        // La puissance est prise lissée et le cœur brut : le cœur l'est déjà par nature, il
        // met une demi-minute à répondre, là où la puissance saute à chaque coup de pédale.
        drift.observe(clock(), powerWatts = values[2], heartRate = values[3])
        battery.observe(clock(), summary[8])
        RideData(
            speed = values[0],
            averageSpeed = values[1],
            power = values[2],
            heartRate = values[3],
            cadence = values[4] ?: 0.0.takeIf { cadenceVue },
            grade = values[5],
            distance = values[6],
            distanceRemaining = values[7],
            arrivalTime = values[8],
            drivetrain = drivetrain,
            climb = climb,
            onRoute = values[9]?.let { it > 0.5 },
            energyOutput = values[10],
            powerZones = profile.powerZones,
            heartRateZones = profile.heartRateZones,
            pace = paceLearner.pace,
            level = RideLevel(
                averageHeartRate = summary[0],
                maxHeartRate = summary[1],
                averagePower = summary[2],
                normalizedPower = summary[3],
                elevationGain = summary[4],
                elevationRemaining = summary[5],
                intensityFactor = summary[6],
                trainingStressScore = summary[7],
                // Coupé au nombre de zones réglées : l'horloge en tient sept, l'appareil en
                // règle cinq pour le cœur, et deux cases toujours vides feraient croire à
                // deux zones où l'on n'est jamais monté.
                heartRateZoneSeconds = zoneClock.elapsed.take(profile.heartRateZones.size),
                wPrimeBalance = wPrime.balance,
                wPrimeCapacity = wPrime.size,
                criticalPower = (reglages.criticalPower ?: profile.ftp)?.toDouble(),
                totalSeconds = summary[9],
                movingSeconds = summary[10],
                drift = drift.drift(),
                batteryPercent = battery.percent,
                batteryPerHour = battery.perHour,
            ),
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
            value(DataType.Type.ENERGY_OUTPUT),
        ),
    ) { it }

    /**
     * Les cumuls de la sortie, que le Karoo tient lui-même.
     *
     * Rien n'est recalculé ici : ces huit valeurs sont celles de l'enregistrement, et les
     * reconstruire de notre côté les ferait diverger de ce que le fichier de sortie
     * contiendra. Le seul cumul que nous tenons est le temps par zone, que l'appareil ne
     * publie pas.
     */
    private fun summary(): Flow<Array<Double?>> = combine(
        listOf(
            value(DataType.Type.AVERAGE_HR),
            value(DataType.Type.MAX_HR),
            value(DataType.Type.AVERAGE_POWER),
            value(DataType.Type.NORMALIZED_POWER),
            value(DataType.Type.ELEVATION_GAIN),
            value(DataType.Type.ELEVATION_REMAINING),
            value(DataType.Type.INTENSITY_FACTOR),
            value(DataType.Type.TRAINING_STRESS_SCORE),
            value(DataType.Type.BATTERY_PERCENT),
            // Les noms du Karoo sont trompeurs et se lisent à l'envers de l'intuition :
            // « RIDE_TIME » est le temps total, pauses comprises, et « ELAPSED_TIME » celui
            // passé à enregistrer. Les échanger ferait un temps d'arrêt négatif.
            value(DataType.Type.RIDE_TIME),
            value(DataType.Type.ELAPSED_TIME),
        ),
    ) { it }

    /**
     * Les cumuls et les réglages qui décident de la réserve, réunis en un seul flux.
     *
     * `combine` ne se décline en arguments nommés que jusqu'à cinq flux ; au-delà il faut
     * passer par un tableau non typé. Les rassembler ici garde les cinq et, accessoirement,
     * garantit que les réglages appliqués sont ceux de l'instant qu'on rapporte.
     */
    private fun bilan(): Flow<Pair<Array<Double?>, WPrimeSettings>> =
        combine(summary(), wPrimeSettings) { cumuls, reglages -> cumuls to reglages }

    /** Le coureur tel que l'appareil le connaît : zones, seuil et poids. */
    private fun profile(): Flow<RiderProfile> =
        karooSystem.consumerFlow<UserProfile>()
            .map { profile ->
                RiderProfile(
                    powerZones = profile.powerZones.map { it.toRange() },
                    heartRateZones = profile.heartRateZones.map { it.toRange() },
                    ftp = profile.ftp.takeIf { it > 0 },
                    weightKilograms = profile.weight.toDouble().takeIf { it > 0.0 },
                )
            }
            .onStart { emit(RiderProfile()) }

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
                // Le bilan est celui de la sortie, non celui de la journée : le temps par
                // zone repart de zéro en même temps que l'allure apprise.
                zoneClock.reset()
                wPrime.reset()
                drift.reset()
                battery.reset()
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
