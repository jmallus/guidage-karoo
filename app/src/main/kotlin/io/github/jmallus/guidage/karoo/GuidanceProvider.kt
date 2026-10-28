package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.ClimbHistory
import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Polyline
import io.github.jmallus.guidage.core.PositionSuivie
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
import io.github.jmallus.guidage.core.SteadyHeading
import io.github.jmallus.guidage.core.Suivi
import io.github.jmallus.guidage.core.Units
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.Symbol
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

/** Position et cap du coureur, tels que rapportés par le Karoo. */
data class RiderLocation(
    val position: GeoPoint,
    /** Cap en degrés (0 = nord), null quand il n'est pas encore connu. */
    val heading: Double?,
    /** Instant de réception du point, pour rattraper le retard de la chaîne GPS. */
    val receivedAtMillis: Long = 0L,
) {
    /**
     * Où montrer le coureur à [nowMillis] : posé sur le tracé et avancé le long de lui, du
     * retard de la position, voir [Suivi].
     *
     * Le retard à rattraper n'est pas seulement l'âge du point à sa réception. Le modèle du
     * champ se reconstruit à l'arrivée même du point, de sorte que cet âge vaut à peu près
     * zéro — et la marque restait derrière le coureur de tout le retard de la chaîne GPS et
     * du dessin du champ, que rien ne mesurait. Une sortie l'a montré : quelques secondes,
     * surtout visibles dans les virages. Ce retard fixe s'ajoute donc à l'âge du point.
     *
     * Hors de l'itinéraire, la ligne droite reste bornée à l'âge seul : sans la route pour
     * guider la marque, la pousser plus loin l'enverrait dans les champs.
     */
    fun suivie(route: Route?, distanceAlongRoute: Double?, nowMillis: Long, speedMetersPerSecond: Double?): PositionSuivie {
        val age = if (receivedAtMillis > 0L) (nowMillis - receivedAtMillis).coerceAtLeast(0L) else 0L
        return Suivi.position(
            path = route?.path.orEmpty(),
            totalDistance = route?.totalDistance,
            position = position,
            heading = heading,
            distanceAlongRoute = distanceAlongRoute,
            speedMetersPerSecond = speedMetersPerSecond,
            secondesSurTrace = (age + RETARD_CHAINE_MS).coerceAtMost(MAX_SUR_TRACE_MS) / 1_000.0,
            secondesHorsTrace = age.coerceAtMost(MAX_EXTRAPOLATION_MS) / 1_000.0,
        )
    }

    companion object {
        /**
         * Retard de la chaîne, du GPS à l'écran (ms) : la position arrive déjà vieille, puis
         * le champ se dessine et passe par le système avant de s'afficher.
         *
         * Estimé, non mesuré : c'est l'écart qu'une sortie a montré entre la marque et le
         * coureur. À ajuster si la marque se met à précéder le coureur.
         */
        const val RETARD_CHAINE_MS = 2_000L

        /** Au-delà, le point ne se rafraîchit plus : inventer une trajectoire serait pire. */
        private const val MAX_EXTRAPOLATION_MS = 4_000L
        private const val MAX_SUR_TRACE_MS = 6_000L
    }
}

/** État de guidage accompagné du système d'unités choisi par le coureur. */
data class GuidanceSnapshot(
    val state: GuidanceState = GuidanceState.IDLE,
    val units: Units = Units.METRIC,
    val location: RiderLocation? = null,
    /** Vitesse lissée (m/s), qui dit de combien rattraper le retard de la position. */
    val speed: Double? = null,
)

/**
 * Assemble l'état de guidage à partir des événements Karoo :
 *  - [OnNavigationState] : itinéraire, profil altimétrique, côtes et POI
 *  - `DISTANCE_TO_DESTINATION` : distance restante, d'où l'on déduit la position sur l'itinéraire
 *  - `ELEVATION_GRADE` : pente instantanée
 *
 * Le flux est partagé : tous les champs de données de l'extension s'appuient sur la même
 * souscription, ce qui évite de multiplier les consommateurs côté système.
 */
class GuidanceProvider(
    private val karooSystem: KarooSystemService,
    scope: CoroutineScope,
) {
    /**
     * Les côtes déjà rencontrées sur l'itinéraire en cours.
     *
     * Le Karoo ne rapporte que celles qui restent : sans cette mémoire, la numérotation
     * recule et la côte s'efface au moment où on l'attaque.
     */
    private val climbHistory = ClimbHistory()

    /** Le cap qui oriente la carte, tenu tant qu'on n'avance pas : voir [SteadyHeading]. */
    private val steadyHeading = SteadyHeading()

    /**
     * Le dernier état de navigation complet, et son instant.
     *
     * Pendant un reroutage, l'itinéraire ou la distance restante manquent le temps d'une
     * seconde : l'état n'est plus « en navigation », le profil et la carte se vident, puis
     * tout revient. Ce clignotement ne dit rien au coureur. L'état complet précédent est
     * donc tenu quelques secondes, le temps que le nouvel itinéraire arrive ; passé ce
     * délai, l'absence est vraie — la navigation s'est arrêtée — et elle s'affiche.
     */
    private var lastNavigating: GuidanceState? = null
    private var lastNavigatingMillis = 0L

    private fun steadied(state: GuidanceState, nowMillis: Long): GuidanceState {
        if (state.navigating) {
            lastNavigating = state
            lastNavigatingMillis = nowMillis
            return state
        }
        val previous = lastNavigating ?: return state
        if (nowMillis - lastNavigatingMillis > NAVIGATION_GRACE_MS) return state
        // La pente instantanée, elle, est toujours fraîche : elle ne vient pas de l'itinéraire.
        return previous.copy(currentGrade = state.currentGrade)
    }

    val snapshot: StateFlow<GuidanceSnapshot> = build()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GuidanceSnapshot())

    private fun build(): Flow<GuidanceSnapshot> {
        val navigation = karooSystem.consumerFlow<OnNavigationState>()
            .map { it.state }
            .onStart { emit(NavigationState.Idle) }
        // Les points d'intérêt « globaux » du Karoo — ceux qu'il annonce en bas d'écran
        // (« restaurant à 4,9 km ») sans qu'ils appartiennent à l'itinéraire. Ils n'arrivent
        // pas avec l'état de navigation : sans cet abonnement, le profil les ignorait.
        val globalPois = karooSystem.consumerFlow<OnGlobalPOIs>()
            .map { it.pois }
            .onStart { emit(emptyList()) }
        // L'itinéraire se décode ici, une fois par état de navigation : tracé, profil,
        // côtes et points d'intérêt posés sur le tracé. Il se décodait dans la combinaison
        // ci-dessous, c'est-à-dire à chaque distance, pente, vitesse ou position reçue —
        // plusieurs fois par seconde, pour un itinéraire qui ne change qu'au départ ou à un
        // reroutage. C'était le plus gros calcul de l'extension, et il tournait sans cesse.
        val itineraire = combine(navigation, globalPois) { nav, pois -> itineraire(nav, climbHistory, pois) }
            .distinctUntilChanged()
        // Ce type porte quatre champs (distance, état de navigation, reroutage, sur
        // l'itinéraire) : il faut nommer celui qu'on veut, sans quoi on lit l'un des autres.
        val remaining = karooSystem.streamFieldFlow(
            DataType.Type.DISTANCE_TO_DESTINATION,
            DataType.Field.DISTANCE_TO_DESTINATION,
        ).onStart { emit(null) }
        val grade = karooSystem.streamValueFlow(DataType.Type.ELEVATION_GRADE)
            .onStart { emit(null) }
        val speed = karooSystem.streamValueFlow(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED)
            .onStart { emit(null) }
        val units = karooSystem.consumerFlow<UserProfile>()
            .map { it.preferredUnit.distance.toUnits() }
            .onStart { emit(Units.METRIC) }
        // Réunis pour tenir dans les cinq flux qu'accepte `combine` sous forme typée.
        val unitsEtVitesse = combine(units, speed) { u, v -> u to v }
        val location = karooSystem.consumerFlow<OnLocationChanged>()
            .map<OnLocationChanged, RiderLocation?> {
                val position = GeoPoint(it.lat, it.lng)
                RiderLocation(
                    position = position,
                    heading = steadyHeading.observe(position, it.orientation),
                    receivedAtMillis = System.currentTimeMillis(),
                )
            }
            .onStart { emit(null) }

        return combine(
            itineraire,
            remaining,
            grade,
            unitsEtVitesse,
            location,
        ) { route, distanceRemaining, currentGrade, (unitSystem, vitesse), riderLocation ->
            GuidanceSnapshot(
                state = steadied(
                    etat(route, distanceRemaining, currentGrade),
                    System.currentTimeMillis(),
                ),
                units = unitSystem,
                location = riderLocation,
                speed = vitesse,
            )
        }.distinctUntilChanged()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Combien de temps un état de navigation complet survit à son absence (ms). */
        private const val NAVIGATION_GRACE_MS = 5_000L

        fun buildState(
            navigation: NavigationState,
            distanceRemaining: Double?,
            currentGrade: Double?,
            climbHistory: ClimbHistory? = null,
            globalPois: List<Symbol.POI> = emptyList(),
        ): GuidanceState = etat(itineraire(navigation, climbHistory, globalPois), distanceRemaining, currentGrade)

        /**
         * L'itinéraire tel qu'il se dessine, décodé d'un état de navigation : la partie lourde
         * du calcul, qui ne dépend que de lui et des points d'intérêt globaux.
         */
        fun itineraire(
            navigation: NavigationState,
            climbHistory: ClimbHistory? = null,
            globalPois: List<Symbol.POI> = emptyList(),
        ): Route? {
            val reported = navigation.toRoute()?.withGlobalPois(globalPois) ?: return null
            // Les côtes retenues sont confrontées au profil : celles qu'il ne porte pas ne
            // sont pas dessinées, et ne comptent pas non plus dans la numérotation.
            return (climbHistory?.remember(reported) ?: reported)
                .let { it.copy(climbs = Guidance.climbsOnProfile(it)) }
        }

        /** L'état de guidage d'un itinéraire déjà décodé : quelques soustractions. */
        fun etat(route: Route?, distanceRemaining: Double?, currentGrade: Double?): GuidanceState {
            if (route == null) return GuidanceState.IDLE
            val along = distanceAlongRoute(route.totalDistance, distanceRemaining)
            return GuidanceState(
                route = route,
                distanceAlongRoute = along,
                distanceRemaining = distanceRemaining,
                currentGrade = currentGrade,
            )
        }

        private fun distanceAlongRoute(totalDistance: Double, distanceRemaining: Double?): Double? {
            if (distanceRemaining == null || totalDistance <= 0.0) return null
            return (totalDistance - distanceRemaining).coerceIn(0.0, totalDistance)
        }

        private fun NavigationState.toRoute(): Route? = when (this) {
            is NavigationState.Idle -> null

            is NavigationState.NavigatingRoute -> {
                val path = decodePath(routePolyline)
                Route(
                    name = name,
                    totalDistance = routeDistance,
                    profile = ElevationProfile.fromEncoded(routeElevationPolyline),
                    climbs = climbs.map { it.toRouteClimb() },
                    pois = pois.flatMap { poi -> poi.toRoutePois(path, routeDistance) },
                    path = path,
                    // Le Karoo calcule lui-même le chemin du retour vers l'itinéraire et nous
                    // le donne ; il est nul tant qu'on est dessus, et aussi hors itinéraire
                    // quand la navigation se fait au fil d'Ariane, sans virage à virage.
                    rejoinPath = rejoinPolyline?.let { decodePath(it) }.orEmpty(),
                )
            }

            is NavigationState.NavigatingToDestination -> {
                // En navigation vers un point, la longueur du trajet n'est pas fournie
                // directement : on la déduit du profil altimétrique quand il est disponible.
                val elevationProfile = ElevationProfile.fromEncoded(elevationPolyline)
                val total = elevationProfile?.totalDistance ?: 0.0
                Route(
                    name = destination.name ?: destination.type,
                    totalDistance = total,
                    profile = elevationProfile,
                    climbs = climbs.map { it.toRouteClimb() },
                    pois = listOf(
                        RoutePoi(
                            id = destination.id,
                            name = destination.name,
                            type = destination.type,
                            distanceAlongRoute = total,
                            position = GeoPoint(destination.lat, destination.lng),
                        ),
                    ),
                    path = decodePath(polyline),
                )
            }
        }

        /**
         * Un même POI peut être rencontré plusieurs fois sur un parcours en boucle :
         * il devient alors un point par passage, avec un identifiant distinct.
         *
         * `distancesAlongRoute` est **facultatif** — karoo-ext le dit ainsi, et sa valeur par
         * défaut est la liste vide. Or ce sont ces distances que l'on parcourait : un point
         * qui n'en portait aucune ne produisait aucun `RoutePoi` et **disparaissait purement
         * et simplement**, quel que fût son type. Une fontaine et une boulangerie posées sur
         * l'itinéraire se sont ainsi volatilisées, le Karoo continuant de les afficher de son
         * côté, et le champ « Réserve » annonçant « Aucun ravitaillement sur l'itinéraire ».
         * L'écart n'était pas dans le tri des types : le point n'arrivait jamais jusqu'à lui.
         *
         * Faute de distance annoncée, on la calcule : le point se pose sur le tracé, ce qui
         * est la même opération que celle du Karoo, faite de notre côté.
         */
        /**
         * Ajoute à l'itinéraire les points d'intérêt globaux qui le bordent.
         *
         * Ils sont posés sur le tracé comme ceux de l'itinéraire, à [POI_MAX_DEVIATION_METERS]
         * au plus ; un point déjà porté par l'itinéraire n'est pas doublé.
         */
        fun Route.withGlobalPois(globalPois: List<Symbol.POI>): Route {
            if (globalPois.isEmpty() || path.isEmpty()) return this
            val connus = pois.map { it.id.substringBefore('#') }.toSet()
            val ajoutes = globalPois
                .filter { it.id !in connus }
                .flatMap { it.toRoutePois(path, totalDistance) }
            return if (ajoutes.isEmpty()) this else copy(pois = (pois + ajoutes).sortedBy { it.distanceAlongRoute })
        }

        private fun Symbol.POI.toRoutePois(path: List<GeoPoint>, totalDistance: Double): List<RoutePoi> {
            val position = GeoPoint(lat, lng)
            val distances = distancesAlongRoute.ifEmpty {
                listOfNotNull(Geo.distanceAlongPath(path, position, POI_MAX_DEVIATION_METERS))
            }
            return distances.mapIndexed { index, distance ->
                RoutePoi(
                    id = if (index == 0) id else "$id#$index",
                    name = name,
                    type = type,
                    // Le tracé décodé et la longueur annoncée peuvent différer de quelques
                    // mètres ; la fraction qu'en tirent les champs doit rester dans la ligne.
                    distanceAlongRoute = distance.coerceIn(0.0, totalDistance.coerceAtLeast(0.0)),
                    position = position,
                )
            }
        }

        /**
         * Écart maximal entre un point d'intérêt et le tracé pour l'y rattacher (m).
         *
         * Assez large pour un commerce en retrait de la chaussée, assez court pour ne pas
         * happer celui de la rue parallèle. Ne sert qu'au rattrapage : quand le Karoo annonce
         * lui-même les distances, ce sont les siennes qui font foi.
         */
        private const val POI_MAX_DEVIATION_METERS = 250.0

        /** Le tracé est encodé en polyligne Google de précision 5. */
        private fun decodePath(encoded: String): List<GeoPoint> =
            Polyline.decode(encoded, PATH_PRECISION).map { GeoPoint(it.first, it.second) }

        private const val PATH_PRECISION = 5

        private fun NavigationState.Climb.toRouteClimb() = RouteClimb(
            startDistance = startDistance,
            length = length,
            grade = grade,
            totalElevation = totalElevation,
        )

        private fun UserProfile.PreferredUnit.UnitType.toUnits() = when (this) {
            UserProfile.PreferredUnit.UnitType.METRIC -> Units.METRIC
            UserProfile.PreferredUnit.UnitType.IMPERIAL -> Units.IMPERIAL
        }
    }
}
