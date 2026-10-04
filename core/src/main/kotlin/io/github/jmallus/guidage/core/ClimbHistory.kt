package io.github.jmallus.guidage.core

import kotlin.math.abs

/**
 * Mémoire des côtes d'un itinéraire.
 *
 * Le Karoo ne rapporte que les côtes **qu'il reste à faire** : la liste maigrit à mesure
 * qu'on avance, et la côte où l'on vient d'entrer en disparaît. Prise telle quelle, elle
 * donne deux défauts qu'on voit en roulant : la numérotation recule — « 1/5 » puis « 1/4 »
 * pour la côte suivante — et le profil de la côte s'efface juste au moment où on l'attaque,
 * c'est-à-dire quand il sert.
 *
 * On garde donc trace de toutes les côtes vues depuis le début de l'itinéraire. La liste ne
 * fait que grandir, la numérotation ne bouge plus, et une côte commencée reste connue
 * jusqu'à son sommet.
 */
class ClimbHistory {

    private var routeKey: String? = null
    private val known = mutableListOf<RouteClimb>()

    /**
     * Complète les côtes de [route] par celles déjà rencontrées sur le même itinéraire.
     *
     * Changer d'itinéraire vide la mémoire : rien ne serait plus faux que de numéroter les
     * côtes du jour d'après celles de la veille.
     */
    fun remember(route: Route): Route {
        val key = "${route.name}|${route.totalDistance.toInt()}"
        if (key != routeKey) {
            routeKey = key
            known.clear()
        }
        route.climbs.forEach { climb ->
            // Une même côte peut être rapportée avec un pied très légèrement différent d'une
            // mise à jour à l'autre ; on la reconnaît à la tolérance près plutôt que de la
            // compter deux fois. La première version rencontrée fait foi, pour que ni le
            // numéro ni le profil ne sautent en cours de montée.
            if (known.none { memeCote(it, climb) }) {
                known += climb
            }
        }
        if (known.isEmpty()) return route
        return route.copy(climbs = known.sortedBy { it.startDistance })
    }

    /**
     * Deux relevés désignent-ils la même côte ?
     *
     * Le pied seul ne suffisait pas. Sur le vélo, le Karoo a redécoupé une côte en cours de
     * route — pied déplacé de plus de cinquante mètres —, et la mémoire en a gardé deux qui se
     * chevauchaient : deux montées à la fois, et un total de côtes gonflé. Deux côtes qui se
     * recouvrent sur plus de la moitié de la plus courte sont donc tenues pour une seule.
     */
    private fun memeCote(a: RouteClimb, b: RouteClimb): Boolean {
        if (abs(a.startDistance - b.startDistance) <= SAME_CLIMB_TOLERANCE) return true
        val recouvrement = minOf(a.endDistance, b.endDistance) - maxOf(a.startDistance, b.startDistance)
        val plusCourte = minOf(a.length, b.length)
        return plusCourte > 0.0 && recouvrement >= plusCourte * SAME_CLIMB_OVERLAP
    }

    private companion object {
        /** Part de la plus courte des deux côtes au-delà de laquelle leur recouvrement en fait une. */
        const val SAME_CLIMB_OVERLAP = 0.5

        /** Écart en deçà duquel deux relevés désignent la même côte (m). */
        const val SAME_CLIMB_TOLERANCE = 50.0
    }
}
