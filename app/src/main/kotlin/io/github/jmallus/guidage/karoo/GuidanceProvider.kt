package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Polyline
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
import io.github.jmallus.guidage.core.Units
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
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
)

/** État de guidage accompagné du système d'unités choisi par le coureur. */
data class GuidanceSnapshot(
    val state: GuidanceState = GuidanceState.IDLE,
    val units: Units = Units.METRIC,
    val location: RiderLocation? = null,
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
    val snapshot: StateFlow<GuidanceSnapshot> = build()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GuidanceSnapshot())

    private fun build(): Flow<GuidanceSnapshot> {
        val navigation = karooSystem.consumerFlow<OnNavigationState>()
            .map { it.state }
            .onStart { emit(NavigationState.Idle) }
        val remaining = karooSystem.streamValueFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .onStart { emit(null) }
        val grade = karooSystem.streamValueFlow(DataType.Type.ELEVATION_GRADE)
            .onStart { emit(null) }
        val units = karooSystem.consumerFlow<UserProfile>()
            .map { it.preferredUnit.distance.toUnits() }
            .onStart { emit(Units.METRIC) }
        val location = karooSystem.consumerFlow<OnLocationChanged>()
            .map<OnLocationChanged, RiderLocation?> {
                RiderLocation(GeoPoint(it.lat, it.lng), it.orientation)
            }
            .onStart { emit(null) }

        return combine(
            navigation,
            remaining,
            grade,
            units,
            location,
        ) { nav, distanceRemaining, currentGrade, unitSystem, riderLocation ->
            GuidanceSnapshot(
                state = buildState(nav, distanceRemaining, currentGrade),
                units = unitSystem,
                location = riderLocation,
            )
        }.distinctUntilChanged()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun buildState(
            navigation: NavigationState,
            distanceRemaining: Double?,
            currentGrade: Double?,
        ): GuidanceState {
            val route = navigation.toRoute() ?: return GuidanceState.IDLE
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

            is NavigationState.NavigatingRoute -> Route(
                name = name,
                totalDistance = routeDistance,
                profile = ElevationProfile.fromEncoded(routeElevationPolyline),
                climbs = climbs.map { it.toRouteClimb() },
                pois = pois.flatMap { poi -> poi.toRoutePois() },
                path = decodePath(routePolyline),
            )

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
         */
        private fun Symbol.POI.toRoutePois(): List<RoutePoi> =
            distancesAlongRoute.mapIndexed { index, distance ->
                RoutePoi(
                    id = if (index == 0) id else "$id#$index",
                    name = name,
                    type = type,
                    distanceAlongRoute = distance,
                    position = GeoPoint(lat, lng),
                )
            }

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
