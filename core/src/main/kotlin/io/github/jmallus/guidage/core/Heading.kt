package io.github.jmallus.guidage.core

/**
 * Le cap qui oriente la carte : celui que l'appareil rapporte tant qu'on avance, le dernier
 * retenu dès qu'on s'arrête.
 *
 * À l'arrêt, le cap rapporté n'est plus celui du déplacement — il n'y en a plus — et
 * l'appareil en donne un autre, qui a fait tourner la carte d'un demi-tour à chaque halte,
 * quand sa propre carte ne bougeait pas. On ne sait pas d'où il sort ; on sait qu'il ne
 * vaut rien sans mouvement. Le cap n'est donc repris que si la position a avancé d'assez
 * depuis celle où on l'avait pris : quelques mètres, au-delà du tremblement d'un point GPS
 * immobile, en deçà de ce qu'on parcourt en roulant au pas.
 */
class SteadyHeading {

    /** Où le cap retenu a été pris. */
    private var anchor: GeoPoint? = null

    /** Le cap retenu, null tant qu'aucun n'a été rapporté. */
    var heading: Double? = null
        private set

    /** Prend en compte un relevé, et rend le cap à retenir. */
    fun observe(position: GeoPoint, reported: Double?): Double? {
        val previous = anchor
        val moved = previous == null || Geo.distance(previous, position) >= MIN_MOVE_METERS
        if (reported != null && moved) {
            heading = reported
            anchor = position
        }
        return heading
    }

    fun reset() {
        anchor = null
        heading = null
    }

    companion object {
        /** Déplacement en deçà duquel on tient le coureur pour arrêté (m). */
        const val MIN_MOVE_METERS = 6.0
    }
}
