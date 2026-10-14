package io.github.jmallus.guidage.core

import kotlin.math.abs

/**
 * Calculs de guidage : côte en cours ou à venir, prochain POI, fenêtre de profil.
 *
 * Toutes les fonctions sont pures : elles ne dépendent que de l'itinéraire et de la
 * distance parcourue le long de celui-ci.
 */
/**
 * Un reroutage vu depuis la trace : la portion contournée, de [debut] à [fin] (m depuis le
 * départ de l'itinéraire), et la [longueur] du chemin rouge qui la remplace (m).
 */
data class Rejointe(val debut: Double, val fin: Double, val longueur: Double)

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

    /**
     * La côte en cours, recalée sur ce que le Karoo en dit quand il en suit une.
     *
     * Notre abscisse se décale : une sortie a montré notre bandeau annonçant le sommet à
     * 2,1 km et +55 m, quand le ClimbPro du Karoo disait 1,68 km et 42 m au même endroit —
     * quatre cents mètres d'écart sur une côte qu'ils dessinaient pourtant de la même longueur.
     * Le Karoo, lui, sait où commence et où finit la côte qu'il suit ([karoo]). On retrouve
     * cette côte parmi celles de l'itinéraire — celle dont le pied est le plus près de celui
     * qu'il annonce, à longueur voisine — et on y place le coureur à **sa** progression, avec
     * **ses** distance et dénivelé au sommet : notre bandeau dit alors la même chose que le sien.
     *
     * Sans côte suivie par le Karoo, ou sans côte de l'itinéraire qui lui corresponde, on
     * revient à notre propre calcul.
     */
    fun climbStatus(route: Route, distanceAlongRoute: Double, karoo: ClimbProgress): ClimbStatus? {
        val propre = climbStatus(route, distanceAlongRoute)
        if (!karoo.onClimb) return propre
        val depuisLePied = karoo.distanceFromBottom ?: return propre
        val longueur = karoo.length ?: return propre
        val pied = distanceAlongRoute - depuisLePied
        val climbs = route.climbs.sortedBy { it.startDistance }
        val index = climbs.indices
            .filter { abs(climbs[it].startDistance - pied) <= KAROO_CLIMB_MATCH_METERS }
            .minByOrNull { abs(climbs[it].startDistance - pied) + abs(climbs[it].length - longueur) }
            ?: return propre
        val climb = climbs[index]
        val progress = karoo.progress
        return ClimbStatus(
            climb = climb,
            number = index + 1,
            totalClimbs = climbs.size,
            onClimb = true,
            distanceToStart = 0.0,
            distanceToTop = karoo.distanceToTop ?: (climb.length * (1.0 - progress)),
            elevationToTop = karoo.elevationToTop
                ?: elevationToTop(route.profile, climb, climb.startDistance + progress * climb.length, true, progress),
            progress = progress,
        )
    }

    /**
     * Écart toléré entre le pied annoncé par le Karoo et celui d'une côte de l'itinéraire (m).
     *
     * Assez large pour absorber le décalage de notre abscisse, qu'une sortie a montré à quatre
     * cents mètres ; assez court pour ne pas confondre deux côtes qui se suivent.
     */
    const val KAROO_CLIMB_MATCH_METERS = 1_500.0

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

    /**
     * Amplitude verticale minimale affichée (m).
     *
     * L'échelle des altitudes s'étire sur ce que la fenêtre contient : sur du plat, une bosse
     * de six mètres remplissait toute la hauteur de la bande et se lisait comme une montée —
     * une sortie l'a dit. Quatre-vingts mètres au moins : la bosse retombe à un dixième de la
     * hauteur, une vraie côte de quarante mètres en tient la moitié, et en montagne
     * l'amplitude réelle dépasse ce plancher et rien ne change.
     */
    const val MIN_ELEVATION_SPAN = 80.0

    /**
     * La portion de l'itinéraire que le chemin de rejointe contourne : du coureur jusqu'au
     * point où le chemin rouge retrouve la trace (m depuis le départ), ou null hors reroutage.
     *
     * Le Karoo donne le tracé de la rejointe et non son relief. Sur le profil, la portion
     * contournée montrait donc le relief de la trace d'origine, qu'on ne roule pas : c'est elle
     * qu'il faut masquer. La fin se cherche près de la position plus la longueur du détour —
     * sur une boucle, le point le plus proche peut être un autre passage.
     */
    fun rejointe(route: Route, along: Double): Rejointe? {
        val chemin = route.rejoinPath
        if (chemin.size < 2 || route.path.size < 2) return null
        val longueur = chemin.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
        val retour = Geo.anchorOnPath(route.path, chemin.last(), expectedAlong = along + longueur, tolerance = REJOINTE_TOLERANCE)
            ?: return null
        if (retour.deviation > REJOINTE_ECART_MAX) return null
        val fin = retour.distanceAlongPath.coerceAtMost(route.totalDistance)
        return if (fin > along) Rejointe(along, fin, longueur) else null
    }

    /** Écart admis entre la fin du chemin de rejointe et la trace (m). */
    private const val REJOINTE_ECART_MAX = 60.0

    /** Tolérance entre deux passages candidats, pour départager par la distance attendue (m). */
    private const val REJOINTE_TOLERANCE = 30.0
}
