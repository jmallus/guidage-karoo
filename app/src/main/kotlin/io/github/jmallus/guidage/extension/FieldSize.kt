package io.github.jmallus.guidage.extension

import io.hammerhead.karooext.models.ViewConfig

/**
 * Taille en pixels du bitmap à dessiner pour un champ.
 *
 * Karoo fournit la taille réelle du champ dans [ViewConfig.viewSize] ; on retombe sur une
 * taille par défaut quand elle n'est pas encore connue, et on borne la surface pour éviter
 * d'envoyer des bitmaps inutilement lourds au système.
 */
object FieldSize {

    private const val DEFAULT_WIDTH = 240
    private const val DEFAULT_HEIGHT = 100

    /**
     * Plus grande dimension rendue, au-delà de laquelle le bitmap est réduit.
     *
     * Elle valait 640, ce qui était plus petit que le champ plein écran : celui-ci mesure
     * 478 × 642 sur un Karoo 3, bandeau d'état déduit. Le tableau de bord était donc dessiné
     * sur 640 lignes puis étiré sur les deux dernières, ce qui adoucit les traits et fait
     * mentir toutes les tailles calculées en part de la hauteur — la moitié de ce qu'on
     * règle ici.
     *
     * Mille vingt-quatre laisse la place à l'écran entier sans rien étirer. Un bitmap de
     * 480 × 800 en ARGB pèse un mégaoctet et demi, ce qui n'est rien pour une image par
     * seconde ; la borne ne protège que du cas absurde.
     */
    private const val MAX_DIMENSION = 1_024

    fun of(config: ViewConfig): Pair<Int, Int> {
        val width = config.viewSize.first.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = config.viewSize.second.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        return Pair(width.coerceAtMost(MAX_DIMENSION), height.coerceAtMost(MAX_DIMENSION))
    }
}
