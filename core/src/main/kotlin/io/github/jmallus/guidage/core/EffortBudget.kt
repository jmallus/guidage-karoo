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

    /**
     * Rapproche ce qui est dépensé de ce qui reste.
     *
     * Rend null tant que l'un des deux manque : sans capteur de puissance il n'y a pas de
     * dépense, et sans allure apprise il n'y a pas de budget. Une moitié de la comparaison
     * ne vaut rien — c'est le rapport des deux qu'on vient lire.
     */
    fun progress(
        spentKilojoules: Double?,
        estimate: EffortEstimate?,
        distanceAlongRoute: Double?,
        totalDistance: Double?,
    ): EffortProgress? {
        val spent = spentKilojoules?.takeIf { it >= 0.0 } ?: return null
        val remaining = estimate?.kilojoules ?: return null
        val fraction = if (distanceAlongRoute != null && totalDistance != null && totalDistance > 0.0) {
            (distanceAlongRoute / totalDistance).coerceIn(0.0, 1.0)
        } else {
            null
        }
        return EffortProgress(
            spentKilojoules = spent,
            remainingKilojoules = remaining,
            distanceFraction = fraction,
        )
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

/**
 * Ce qui est déjà payé, ce qui reste à payer, et l'avance ou le retard de l'un sur l'autre.
 *
 * Le budget seul dit ce que coûte la suite. Il ne dit pas où l'on en est : six cents
 * kilojoules devant, est-ce le début ou la fin ? Le Karoo compte de son côté les kilojoules
 * déjà produits — c'est une intégrale de la puissance, mesurée, sans modèle. Les deux se
 * lisent donc dans la même unité et se mettent bout à bout.
 *
 * D'où la comparaison qui donne son sens à l'ensemble : la part de l'effort déjà faite en
 * regard de la part de la distance déjà faite. Aux deux tiers de l'effort à la moitié des
 * kilomètres, la fin sera plus dure que le début ne l'a laissé croire — et c'est une chose
 * qu'aucun compteur ne dit, parce qu'aucun ne rapproche les deux.
 */
data class EffortProgress(
    /** Kilojoules déjà produits depuis le départ. */
    val spentKilojoules: Double,
    /** Kilojoules qu'il reste à produire, du budget. */
    val remainingKilojoules: Double,
    /** Part de la distance déjà parcourue (0 à 1), null si l'itinéraire ne la donne pas. */
    val distanceFraction: Double?,
) {
    val totalKilojoules: Double get() = spentKilojoules + remainingKilojoules

    /** Part de l'effort déjà faite (0 à 1), null si le total est nul. */
    val effortFraction: Double?
        get() = totalKilojoules.takeIf { it > 0.0 }?.let { spentKilojoules / it }

    /**
     * Écart entre la part d'effort et la part de distance, en points.
     *
     * Positif, l'effort est en avance sur les kilomètres : le plus dur est derrière. Négatif,
     * il est en retard : ce qui reste coûte plus cher que ce qui est fait.
     */
    val lead: Double?
        get() {
            val effort = effortFraction ?: return null
            val distance = distanceFraction ?: return null
            return effort - distance
        }
}
