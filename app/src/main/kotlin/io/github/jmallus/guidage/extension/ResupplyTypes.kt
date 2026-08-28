package io.github.jmallus.guidage.extension

import io.hammerhead.karooext.models.Symbol

/**
 * Les types de points d'intérêt où l'on peut remplir un bidon ou refaire des poches.
 *
 * La liste est large à dessein. Un point d'eau est le cas franc, mais une station-service à
 * la sortie du village en vaut un, et l'épicerie aussi : ce qu'on cherche à répondre n'est
 * pas « où est la fontaine » mais « après quoi n'y a-t-il plus rien ». Un type oublié fait
 * annoncer une traversée qui n'en est pas une, ce qui est la faute la plus coûteuse ici.
 *
 * Le contrôle de cyclosportive n'y est pas : il oblige à s'arrêter, mais rien ne dit qu'on y
 * trouve à boire, et l'itinéraire en pose souvent là où il n'y a rien d'autre.
 */
object ResupplyTypes {

    /**
     * L'eau seule.
     *
     * Le coureur en autonomie complète porte ce qu'il mange et ne s'arrête que pour boire :
     * lui compter un café comme un ravitaillement lui fait croire à un point utile là où il
     * ne descendra pas de vélo. C'est le second réglage de ce champ, et le seul qui compte.
     */
    val WATER_ONLY: Set<String> = setOf(Symbol.POI.Types.WATER)

    fun of(waterOnly: Boolean): Set<String> = if (waterOnly) WATER_ONLY else ALL

    val ALL: Set<String> = setOf(
        Symbol.POI.Types.WATER,
        Symbol.POI.Types.AID_STATION,
        Symbol.POI.Types.CONVENIENCE_STORE,
        Symbol.POI.Types.SHOPPING,
        Symbol.POI.Types.GAS_STATION,
        Symbol.POI.Types.FOOD,
        Symbol.POI.Types.BAR,
        Symbol.POI.Types.COFFEE,
        Symbol.POI.Types.REST_STOP,
    )
}
