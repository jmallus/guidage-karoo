package io.github.jmallus.guidage.core

/** Ce que la carte montre : sa portée, et jusqu'où devant le coureur elle pose ses chevrons (m). */
data class PorteeCarte(val rangeMeters: Double, val chevronMeters: Double) {
    companion object {
        fun of(zoom: MapZoom) = PorteeCarte(zoom.rangeMeters, zoom.chevronMeters)
    }
}

/**
 * Le gros plan d'un virage : la carte se resserre à l'approche d'une bifurcation annoncée par
 * le Karoo, puis revient au cran choisi une fois le virage passé.
 *
 * À trois cents mètres de portée, une bifurcation entre deux chemins parallèles tient dans
 * quelques pixels, et c'est précisément là qu'on regarde la carte. Le Karoo sait où tourner —
 * il publie la distance au prochain virage — ; le gros plan vient à ce moment-là, sans qu'on ait
 * à appuyer sur l'écran, et s'en va de lui-même.
 *
 * Il tient un état : le virage franchi, le Karoo annonce aussitôt le suivant, plus loin, et la
 * carte se dézoomerait au milieu de la courbe. On garde donc le gros plan [APRES_METRES] de
 * plus, comptés sur la distance parcourue.
 */
class ZoomVirage {

    /** La distance parcourue à laquelle le gros plan prend fin, une fois le virage passé. */
    private var finA: Double? = null

    fun portee(reglee: MapZoom, distanceAuVirage: Double?, distanceParcourue: Double?): PorteeCarte {
        val normale = PorteeCarte.of(reglee)
        // Déjà plus serré que le gros plan : rien à resserrer.
        if (reglee.rangeMeters <= GROS_PLAN.rangeMeters) return normale

        if (distanceAuVirage != null && distanceAuVirage in 0.0..DECLENCHEMENT_METRES) {
            finA = distanceParcourue?.let { it + distanceAuVirage + APRES_METRES }
            return GROS_PLAN
        }
        val fin = finA
        if (fin != null && distanceParcourue != null && distanceParcourue < fin) return GROS_PLAN
        finA = null
        return normale
    }

    companion object {
        /** À quelle distance du virage le gros plan se déclenche (m). */
        const val DECLENCHEMENT_METRES = 150.0

        /** Combien de mètres il dure encore, le virage passé. */
        const val APRES_METRES = 40.0

        /**
         * Quatre-vingts mètres de portée : la règle d'échelle, au quart de la portée, y lit
         * « 20 m » — la largeur d'un carrefour. Les chevrons vont un peu au-delà du cadre,
         * pour qu'on voie par où la route repart.
         */
        val GROS_PLAN = PorteeCarte(rangeMeters = 80.0, chevronMeters = 150.0)
    }
}
