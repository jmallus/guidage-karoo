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
    private const val MAX_DIMENSION = 640

    fun of(config: ViewConfig): Pair<Int, Int> {
        val width = config.viewSize.first.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = config.viewSize.second.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        return Pair(width.coerceAtMost(MAX_DIMENSION), height.coerceAtMost(MAX_DIMENSION))
    }
}
