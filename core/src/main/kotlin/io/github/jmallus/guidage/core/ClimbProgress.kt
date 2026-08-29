package io.github.jmallus.guidage.core

/**
 * Avancement dans la côte en cours, tel que le Karoo le suit lui-même.
 *
 * C'est la source la plus sûre : l'appareil sait où commence et où finit la côte qu'il a
 * identifiée. Le reconstituer de notre côté suppose une distance parcourue exacte, et le
 * moindre décalage fait apparaître le profil de la côte une fois la bosse passée.
 *
 * Tout est facultatif : hors côte, le Karoo ne rapporte rien.
 */
data class ClimbProgress(
    /** Distance depuis le pied de la côte (m). */
    val distanceFromBottom: Double? = null,
    /** Distance restant jusqu'au sommet (m). */
    val distanceToTop: Double? = null,
    /** Dénivelé restant jusqu'au sommet (m). */
    val elevationToTop: Double? = null,
    /** Dénivelé total de la côte (m). */
    val totalElevation: Double? = null,
    /** Rang de la côte sur l'itinéraire, 1 pour la première. */
    val number: Int? = null,
    val totalClimbs: Int? = null,
) {
    /** Longueur de la côte, quand ses deux bouts sont connus (m). */
    val length: Double?
        get() {
            val from = distanceFromBottom ?: return null
            val to = distanceToTop ?: return null
            return (from + to).takeIf { it > 0.0 }
        }

    /**
     * Vrai quand une côte est effectivement en cours.
     *
     * Le Karoo continue de publier ses champs à zéro entre deux côtes : une distance au
     * sommet nulle ne veut donc pas dire « au sommet » mais « pas de côte ».
     */
    val onClimb: Boolean get() = (distanceToTop ?: 0.0) > 0.0 && length != null

    /** Progression dans la côte, de 0 au pied à 1 au sommet. */
    val progress: Double
        get() {
            val from = distanceFromBottom ?: return 0.0
            val total = length ?: return 0.0
            return (from / total).coerceIn(0.0, 1.0)
        }

    /** Rang de la côte, façon « 2/5 », quand le Karoo le donne. */
    val label: String?
        get() {
            val rank = number?.takeIf { it > 0 } ?: return null
            val total = totalClimbs?.takeIf { it > 0 } ?: return rank.toString()
            return "$rank/$total"
        }

    companion object {
        val NONE = ClimbProgress()
    }
}
