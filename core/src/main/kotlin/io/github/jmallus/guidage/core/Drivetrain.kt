package io.github.jmallus.guidage.core

/**
 * Position de la transmission, telle que rapportée par le groupe électronique.
 *
 * Tout est facultatif : un groupe mécanique ne rapporte rien, un groupe électronique
 * rapporte le rapport engagé, parfois le nombre de rapports, parfois les dentures. Le
 * champ s'accommode de ce qu'il reçoit plutôt que d'exiger l'ensemble.
 */
data class Drivetrain(
    /** Plateau engagé, numéroté depuis le petit : le n° 1 est le petit plateau. */
    val front: Int? = null,
    val frontCount: Int? = null,
    val frontTeeth: Int? = null,
    /**
     * Pignon engagé, numéroté depuis le grand : le n° 1 est le grand pignon, celui qu'on
     * prend pour monter. Les deux extrémités ne se numérotent donc pas dans le même sens,
     * et c'est ainsi que les groupes comptent — le schéma du tableau de bord le dessine de
     * même, plus grande barre et plus grande denture du même côté.
     */
    val rear: Int? = null,
    val rearCount: Int? = null,
    val rearTeeth: Int? = null,
) {
    val isKnown: Boolean get() = front != null || rear != null

    companion object {
        val UNKNOWN = Drivetrain()
    }
}
