package io.github.jmallus.guidage.core

/**
 * Calculs de guidage : côte en cours ou à venir, prochain POI, fenêtre de profil.
 *
 * Toutes les fonctions sont pures : elles ne dépendent que de l'itinéraire et de la
 * distance parcourue le long de celui-ci.
 */
object Guidance {

    /** Tolérance (m) pour considérer qu'on est encore dans la côte à son sommet. */
    private const val CLIMB_END_TOLERANCE = 20.0

    /**
     * Côte en cours si le coureur y est, sinon la prochaine côte de l'itinéraire.
     * Retourne null si l'itinéraire n'a plus de côte devant.
     */
    fun climbStatus(route: Route, distanceAlongRoute: Double): ClimbStatus? {
        if (route.climbs.isEmpty()) return null
        val climbs = route.climbs.sortedBy { it.startDistance }

        val currentIndex = climbs.indexOfFirst { climb ->
            distanceAlongRoute >= climb.startDistance &&
                distanceAlongRoute < climb.endDistance - CLIMB_END_TOLERANCE
        }
        val index = if (currentIndex >= 0) {
            currentIndex
        } else {
            climbs.indexOfFirst { it.startDistance > distanceAlongRoute }
        }
        if (index < 0) return null

        val climb = climbs[index]
        val onClimb = currentIndex >= 0
        val distanceToStart = (climb.startDistance - distanceAlongRoute).coerceAtLeast(0.0)
        val distanceToTop = (climb.endDistance - distanceAlongRoute).coerceAtLeast(0.0)
        val progress = if (!onClimb || climb.length <= 0.0) {
            0.0
        } else {
            ((distanceAlongRoute - climb.startDistance) / climb.length).coerceIn(0.0, 1.0)
        }
        val elevationToTop = elevationToTop(route.profile, climb, distanceAlongRoute, onClimb, progress)

        return ClimbStatus(
            climb = climb,
            number = index + 1,
            totalClimbs = climbs.size,
            onClimb = onClimb,
            distanceToStart = distanceToStart,
            distanceToTop = distanceToTop,
            elevationToTop = elevationToTop,
            progress = progress,
        )
    }

    private fun elevationToTop(
        profile: ElevationProfile?,
        climb: RouteClimb,
        distanceAlongRoute: Double,
        onClimb: Boolean,
        progress: Double,
    ): Double {
        val from = if (onClimb) distanceAlongRoute else climb.startDistance
        val fromProfile = profile?.let { p ->
            val start = p.elevationAt(from)
            val top = p.elevationAt(climb.endDistance)
            if (start != null && top != null) (top - start).coerceAtLeast(0.0) else null
        }
        return fromProfile ?: (climb.totalElevation * (1.0 - progress)).coerceAtLeast(0.0)
    }

    /**
     * Prochain point d'intérêt situé devant le coureur.
     *
     * @param minimumDistance ignore les POI trop proches derrière/sous la position courante,
     * afin de ne pas rester bloqué sur un POI qu'on vient de dépasser.
     */
    fun nextPoi(route: Route, distanceAlongRoute: Double, minimumDistance: Double = 0.0): PoiStatus? {
        return route.pois
            .asSequence()
            .map { PoiStatus(it, it.distanceAlongRoute - distanceAlongRoute) }
            .filter { it.distance >= minimumDistance }
            .minByOrNull { it.distance }
    }

    /**
     * Fenêtre de profil à venir : de la position courante jusqu'à [lookahead] mètres plus loin.
     *
     * Un peu de contexte derrière la position courante peut être ajouté avec [lookbehind].
     */
    fun profileWindow(
        route: Route,
        distanceAlongRoute: Double,
        lookahead: Double,
        lookbehind: Double = 0.0,
    ): ProfileWindow {
        val profile = route.profile
        val start = (distanceAlongRoute - lookbehind).coerceAtLeast(0.0)
        val end = (distanceAlongRoute + lookahead).coerceAtMost(
            maxOf(route.totalDistance, profile?.totalDistance ?: 0.0),
        )
        if (profile == null || profile.isEmpty || end <= start) {
            return ProfileWindow(emptyList(), start, maxOf(end, start), 0.0, 0.0)
        }

        val points = profile.slice(start, end)
        if (points.size < 2) {
            return ProfileWindow(points, start, end, 0.0, 0.0)
        }
        val min = points.minOf { it.elevation }
        val max = points.maxOf { it.elevation }
        // Marge verticale pour éviter un profil totalement plat écrasé sur une ligne.
        val padded = if (max - min < MIN_ELEVATION_SPAN) {
            val center = (max + min) / 2
            Pair(center - MIN_ELEVATION_SPAN / 2, center + MIN_ELEVATION_SPAN / 2)
        } else {
            Pair(min, max)
        }
        return ProfileWindow(points, start, end, padded.first, padded.second)
    }

    /** Amplitude verticale minimale affichée (m), pour un rendu lisible sur du plat. */
    const val MIN_ELEVATION_SPAN = 20.0
}
