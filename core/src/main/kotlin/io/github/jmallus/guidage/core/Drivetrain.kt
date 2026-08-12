package io.github.jmallus.guidage.core

/**
 * Position de la transmission, telle que rapportée par le groupe électronique.
 *
 * Tout est facultatif : un groupe mécanique ne rapporte rien, un groupe électronique
 * rapporte le rapport engagé, parfois le nombre de rapports, parfois les dentures. Le
 * champ s'accommode de ce qu'il reçoit plutôt que d'exiger l'ensemble.
 */
data class Drivetrain(
    /** Plateau engagé, numéroté depuis le plus petit. */
    val front: Int? = null,
    val frontCount: Int? = null,
    val frontTeeth: Int? = null,
    /** Pignon engagé, numéroté depuis le plus petit. */
    val rear: Int? = null,
    val rearCount: Int? = null,
    val rearTeeth: Int? = null,
) {
    val isKnown: Boolean get() = front != null || rear != null

    companion object {
        val UNKNOWN = Drivetrain()
    }
}
