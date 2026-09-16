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
    /**
     * Les côtes de [route] dont le profil montre la montée.
     *
     * Une sortie a montré des côtes annoncées à trois pour cent posées sur du plat, avec leur
     * voile et leur chiffre, là où la silhouette ne montait pas — et pas de trace de leur
     * ascension sur le terrain non plus. D'où qu'elles viennent, une côte que le profil
     * dessiné ne porte pas n'a rien à faire sur lui : on ne garde que celles sous lesquelles
     * la silhouette gagne au moins la moitié du dénivelé annoncé. Sans profil, ou sans
     * dénivelé annoncé, il n'y a rien à confronter et tout est gardé.
     */
    fun climbsOnProfile(route: Route): List<RouteClimb> {
        val profile = route.profile ?: return route.climbs
        return route.climbs.filter { climb ->
            climb.totalElevation <= 0.0 ||
                profile.ascentBetween(climb.startDistance, climb.endDistance) >=
                climb.totalElevation * PROFILE_ASCENT_SHARE
        }
    }

    /** Part du dénivelé annoncé que le profil doit montrer pour qu'une côte soit crue. */
    const val PROFILE_ASCENT_SHARE = 0.5

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
    ): ProfileWindow = window(
        route = route,
        from = distanceAlongRoute - lookbehind,
        to = distanceAlongRoute + lookahead,
    )

    /**
     * Fenêtre de profil de la position courante jusqu'à l'arrivée.
     *
     * Sans portée à passer : c'est tout ce qui reste, et c'est le dessin qui se charge de le
     * faire tenir en comprimant le lointain. Une portée demandait de choisir entre voir la
     * rampe qui arrive et voir la journée ; il n'y a plus de choix à faire.
     */
    fun profileToFinish(route: Route, distanceAlongRoute: Double): ProfileWindow =
        window(route = route, from = distanceAlongRoute, to = routeLength(route))

    /**
     * Fenêtre du graphe de parcours : la totalité de l'itinéraire quand [lookahead] est null,
     * sinon la portion à venir sur la distance demandée.
     *
     * Contrairement à [profileWindow], la position courante n'est pas forcément au bord gauche :
     * sur le parcours entier elle se trouve quelque part au milieu, et c'est au rendu de la placer.
     */
    fun routeGraphWindow(route: Route, distanceAlongRoute: Double, lookahead: Double?): ProfileWindow {
        return if (lookahead == null) {
            window(route, 0.0, routeLength(route))
        } else {
            window(route, distanceAlongRoute, distanceAlongRoute + lookahead)
        }
    }

    private fun routeLength(route: Route): Double =
        maxOf(route.totalDistance, route.profile?.totalDistance ?: 0.0)

    private fun window(route: Route, from: Double, to: Double): ProfileWindow {
        val profile = route.profile
        val start = from.coerceAtLeast(0.0)
        val end = to.coerceAtMost(routeLength(route))
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
