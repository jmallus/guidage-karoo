package io.github.jmallus.guidage.core

/** Un poste de dépense du parcours restant. */
data class EffortItem(
    /** Rang de la côte dans l'itinéraire, ou null pour le poste « terrain roulant ». */
    val climbNumber: Int? = null,
    /** Pente moyenne de la côte (%), null pour le roulant. */
    val grade: Double? = null,
    /** Énergie estimée (kJ). */
    val kilojoules: Double,
)

/**
 * Ce que coûte encore le parcours, en kilojoules plutôt qu'en kilomètres.
 *
 * Savoir qu'il reste vingt-cinq kilomètres ne dit pas s'il faut se garder. Savoir qu'il
 * reste six cents kilojoules, dont quatre cents dans deux côtes, le dit — et c'est la
 * répartition, plus que le total, qui décide de ce qu'on fait maintenant.
 */
data class EffortEstimate(
    /** Total restant (kJ). */
    val kilojoules: Double,
    /** Le détail : le roulant d'abord, puis les côtes dans l'ordre où on les prendra. */
    val items: List<EffortItem>,
)

/**
 * Traduit le terrain restant en énergie, à partir de ce que le coureur tient réellement.
 *
 * On ne passe pas par une masse et une physique supposée — rendement de la transmission,
 * coefficient de roulement, prise au vent — dont chaque terme serait un paramètre de plus à
 * régler et à faire mentir. On multiplie le temps estimé de chaque poste par la puissance
 * mesurée dans ce régime : deux grandeurs observées, aucune supposée.
 *
 * Le prix à payer est qu'il faut un capteur de puissance et quelques minutes de roulage.
 * Sans eux, rien n'est annoncé, ce qui vaut mieux qu'un chiffre inventé.
 */
object EffortBudget {

    /**
     * Estimation du reste, ou null tant que la puissance n'est pas connue dans les régimes
     * qui pèsent.
     *
     * @param climbs les côtes restantes, du profil, pour détailler les postes.
     */
    fun estimate(
        pace: LearnedPace,
        terrain: RemainingTerrain,
        climbs: List<RemainingClimb> = emptyList(),
    ): EffortEstimate? {
        if (terrain.totalDistance <= 0.0) return null

        val climbing = terrain.ascent > Pacing.NEGLIGIBLE_ASCENT
        val easyDistance = if (climbing) terrain.easyDistance else terrain.totalDistance

        val flatSpeed = pace.flatSpeed
        val flatPower = pace.flatPower
        if (easyDistance > 0.0 && (flatSpeed == null || flatPower == null)) return null

        val climbRate = pace.climbRate
        val climbPower = pace.climbPower
        if (climbing && (climbRate == null || climbPower == null)) return null

        val items = mutableListOf<EffortItem>()

        val flatKilojoules = if (easyDistance > 0.0) {
            easyDistance / flatSpeed!! * flatPower!! / JOULES_PER_KILOJOULE
        } else {
            0.0
        }
        if (flatKilojoules > 0.0) items += EffortItem(kilojoules = flatKilojoules)

        var climbKilojoules = 0.0
        if (climbing) {
            // Le détail se fait côte par côte quand on les connaît : c'est le seul découpage
            // qui parle au coureur, qui ne pense pas son parcours en mètres de dénivelé mais
            // en bosses à passer.
            val detailed = climbs.filter { it.ascent > 0.0 }
            val detailedAscent = detailed.sumOf { it.ascent }
            detailed.forEach { climb ->
                val kilojoules = climb.ascent / climbRate!! * climbPower!! / JOULES_PER_KILOJOULE
                climbKilojoules += kilojoules
                items += EffortItem(
                    climbNumber = climb.number,
                    grade = climb.grade,
                    kilojoules = kilojoules,
                )
            }
            // Ce que le profil compte en montée sans qu'une côte soit nommée — les faux
            // plats montants, qui ne font pas une bosse mais coûtent quand même.
            val rest = (terrain.ascent - detailedAscent).coerceAtLeast(0.0)
            if (rest > 0.0) {
                val kilojoules = rest / climbRate!! * climbPower!! / JOULES_PER_KILOJOULE
                climbKilojoules += kilojoules
                items += EffortItem(kilojoules = kilojoules)
            }
        }

        val total = flatKilojoules + climbKilojoules
        if (total <= 0.0) return null

        return EffortEstimate(kilojoules = total, items = items)
    }

    /**
     * Les côtes qu'il reste, telles que le profil les découpe depuis la position courante.
     *
     * Une côte commencée ne compte que par ce qui reste à monter : c'est là toute la
     * différence entre un budget et un catalogue.
     */
    fun remainingClimbs(route: Route, distanceAlongRoute: Double): List<RemainingClimb> {
        val profile = route.profile ?: return emptyList()
        return route.climbs
            .sortedBy { it.startDistance }
            .mapIndexedNotNull { index, climb ->
                if (climb.endDistance <= distanceAlongRoute) return@mapIndexedNotNull null
                val from = maxOf(climb.startDistance, distanceAlongRoute)
                val ascent = profile.ascentBetween(from, climb.endDistance)
                if (ascent <= 0.0) return@mapIndexedNotNull null
                RemainingClimb(number = index + 1, ascent = ascent, grade = climb.grade)
            }
    }

    private const val JOULES_PER_KILOJOULE = 1_000.0
}

/** Une côte encore à monter, réduite à ce qu'elle coûte. */
data class RemainingClimb(
    val number: Int,
    /** Dénivelé restant de cette côte (m). */
    val ascent: Double,
    val grade: Double,
)
