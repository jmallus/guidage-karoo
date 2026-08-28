package io.github.jmallus.guidage.core

/**
 * La traversée qui attend : une portion d'itinéraire sans rien où se ravitailler.
 *
 * C'est elle qu'on veut connaître, et non le prochain point. « Prochaine eau dans 2 km » ne
 * dit pas s'il faut y remplir les bidons ; « dernière eau avant 34 km » le dit, et c'est la
 * même donnée retournée.
 */
data class Crossing(
    /**
     * Dernier point de ravitaillement avant la traversée.
     *
     * Null quand il n'y en a plus aucun devant : la traversée a déjà commencé, et sa longueur
     * se compte alors depuis le coureur.
     */
    val lastPoi: PoiStatus?,
    /** Longueur de la traversée (m), depuis ce point ou depuis le coureur. */
    val length: Double,
)

/** Ce que l'itinéraire offre encore en ravitaillement — et ce qu'il n'offre plus. */
data class ResupplyStatus(
    /** Prochain point de ravitaillement devant, s'il y en a un. */
    val next: PoiStatus? = null,
    /** Distance depuis le dernier point dépassé (m), null si aucun ne l'a été. */
    val sinceLast: Double? = null,
    /** Traversée sans ravitaillement à venir, null quand l'itinéraire n'en réserve pas. */
    val crossing: Crossing? = null,
) {
    companion object {
        val NONE = ResupplyStatus()
    }
}

/**
 * Lit la réserve d'un itinéraire : ce qu'on a passé, ce qui vient, et la traversée à venir.
 *
 * Les types de points qui comptent sont passés en paramètre plutôt que codés ici : ce sont
 * ceux de Karoo, et ce module ignore Karoo. Le coureur qui roule en autonomie complète ne
 * retient que l'eau ; celui d'une cyclosportive y ajoute les ravitaillements et les
 * contrôles. C'est le même calcul.
 */
object Resupply {

    /**
     * Longueur au-delà de laquelle une portion sans ravitaillement mérite d'être annoncée (m).
     *
     * Quinze kilomètres : moins, c'est une demi-heure qu'on tient toujours avec ce qu'on a ;
     * plus, c'est une décision à prendre au point précédent, quand il est encore temps.
     */
    const val DEFAULT_CROSSING_METERS = 15_000.0

    fun status(
        route: Route,
        distanceAlongRoute: Double,
        types: Set<String>,
        crossingMeters: Double = DEFAULT_CROSSING_METERS,
    ): ResupplyStatus {
        if (types.isEmpty()) return ResupplyStatus.NONE
        val points = route.pois
            .filter { it.type in types }
            .sortedBy { it.distanceAlongRoute }

        val ahead = points.filter { it.distanceAlongRoute > distanceAlongRoute }
        val behind = points.filter { it.distanceAlongRoute <= distanceAlongRoute }

        val next = ahead.firstOrNull()?.let {
            PoiStatus(it, it.distanceAlongRoute - distanceAlongRoute)
        }
        val sinceLast = behind.lastOrNull()?.let { distanceAlongRoute - it.distanceAlongRoute }

        return ResupplyStatus(
            next = next,
            sinceLast = sinceLast,
            crossing = crossing(route, distanceAlongRoute, ahead, crossingMeters),
        )
    }

    /**
     * La première longue traversée devant le coureur.
     *
     * On avance de point en point et on s'arrête au premier dont l'écart au suivant — ou à
     * l'arrivée — dépasse la longueur retenue. Les traversées suivantes ne sont pas
     * cherchées : celle-ci se décide avant, et rien ne sert d'annoncer la seconde quand on
     * n'a pas encore passé la première.
     */
    private fun crossing(
        route: Route,
        distanceAlongRoute: Double,
        ahead: List<RoutePoi>,
        crossingMeters: Double,
    ): Crossing? {
        val finish = route.totalDistance
        if (ahead.isEmpty()) {
            // Plus rien devant : la traversée a commencé, et le coureur est dedans.
            val remaining = finish - distanceAlongRoute
            return if (remaining >= crossingMeters) Crossing(null, remaining) else null
        }

        ahead.forEachIndexed { index, poi ->
            val following = ahead.getOrNull(index + 1)?.distanceAlongRoute ?: finish
            val gap = following - poi.distanceAlongRoute
            if (gap >= crossingMeters) {
                return Crossing(
                    lastPoi = PoiStatus(poi, poi.distanceAlongRoute - distanceAlongRoute),
                    length = gap,
                )
            }
        }
        return null
    }
}
