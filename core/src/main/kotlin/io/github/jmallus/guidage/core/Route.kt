package io.github.jmallus.guidage.core

/** Une côte détectée par Karoo sur l'itinéraire. */
data class RouteClimb(
    /** Distance du pied de la côte depuis le départ de l'itinéraire (m). */
    val startDistance: Double,
    /** Longueur de la côte (m). */
    val length: Double,
    /** Pente moyenne (%). */
    val grade: Double,
    /** Dénivelé positif total de la côte (m). */
    val totalElevation: Double,
) {
    val endDistance: Double get() = startDistance + length
}

/** Un point d'intérêt positionné le long de l'itinéraire. */
data class RoutePoi(
    val id: String,
    val name: String?,
    val type: String,
    /** Distance du POI depuis le départ de l'itinéraire (m). */
    val distanceAlongRoute: Double,
)

/**
 * Itinéraire en cours de navigation, réduit à ce dont le guidage a besoin.
 */
data class Route(
    val name: String,
    /** Distance totale de l'itinéraire (m). */
    val totalDistance: Double,
    val profile: ElevationProfile?,
    val climbs: List<RouteClimb> = emptyList(),
    val pois: List<RoutePoi> = emptyList(),
)

/** État d'une côte relativement à la position courante. */
data class ClimbStatus(
    val climb: RouteClimb,
    /** Numéro de la côte dans l'itinéraire (1 = première). */
    val number: Int,
    /** Nombre total de côtes de l'itinéraire. */
    val totalClimbs: Int,
    /** true si le coureur est actuellement dans la côte. */
    val onClimb: Boolean,
    /** Distance restante avant le pied de la côte (m), 0 si déjà dedans. */
    val distanceToStart: Double,
    /** Distance restante jusqu'au sommet (m). */
    val distanceToTop: Double,
    /** Dénivelé restant jusqu'au sommet (m). */
    val elevationToTop: Double,
    /** Progression dans la côte, de 0 à 1 (0 tant qu'elle n'est pas commencée). */
    val progress: Double,
)

/** État du prochain point d'intérêt. */
data class PoiStatus(
    val poi: RoutePoi,
    /** Distance restante jusqu'au POI (m). */
    val distance: Double,
)

/**
 * Fenêtre de profil à afficher : la portion d'itinéraire à venir.
 */
data class ProfileWindow(
    val points: List<ProfilePoint>,
    /** Distance du bord gauche (= position courante) depuis le départ (m). */
    val start: Double,
    /** Distance du bord droit (m). */
    val end: Double,
    val minElevation: Double,
    val maxElevation: Double,
) {
    val isEmpty: Boolean get() = points.size < 2
    val distanceSpan: Double get() = end - start
    val elevationSpan: Double get() = maxElevation - minElevation
}

/**
 * État complet du guidage à un instant donné.
 *
 * [distanceAlongRoute] est déduit de la distance restante annoncée par Karoo :
 * `distance parcourue sur l'itinéraire = distance totale - distance restante`.
 */
data class GuidanceState(
    val route: Route? = null,
    val distanceAlongRoute: Double? = null,
    /** Distance restante jusqu'à l'arrivée (m). */
    val distanceRemaining: Double? = null,
    /** Pente instantanée mesurée par le Karoo (%). */
    val currentGrade: Double? = null,
) {
    val navigating: Boolean get() = route != null && distanceAlongRoute != null

    companion object {
        val IDLE = GuidanceState()
    }
}
