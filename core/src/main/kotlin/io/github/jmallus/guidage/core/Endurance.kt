package io.github.jmallus.guidage.core

/**
 * À quelle vitesse la batterie de l'appareil se vide.
 *
 * Le Karoo publie son niveau de charge, jamais sa pente. Or c'est la pente qui répond à la
 * seule question qui compte en longue distance : arriverai-je avec de la batterie ? Le
 * niveau seul ne répond rien — quarante pour cent, c'est confortable à une heure de
 * l'arrivée et perdu d'avance à six.
 *
 * La mesure part du premier relevé de la sortie et non du dernier pas : le niveau se publie
 * par points entiers, si bien qu'une pente calculée sur deux relevés voisins vaut soit zéro
 * soit une valeur absurde. Sur une fenêtre longue, la quantification se dilue.
 */
class BatteryDrain {

    private var departPercent: Double? = null
    private var departMillis: Long? = null
    private var courantPercent: Double? = null
    private var courantMillis: Long? = null

    /** Le niveau de charge au dernier relevé (%). */
    val percent: Double? get() = courantPercent

    /** Points de charge perdus par heure, ou null tant que rien n'est mesurable. */
    val perHour: Double?
        get() {
            val depart = departPercent ?: return null
            val depuis = departMillis ?: return null
            val courant = courantPercent ?: return null
            val maintenant = courantMillis ?: return null
            val heures = (maintenant - depuis) / 3_600_000.0
            if (heures < MIN_HOURS) return null
            val perdu = depart - courant
            if (perdu < MIN_POINTS) return null
            return perdu / heures
        }

    fun observe(nowMillis: Long, percent: Double?) {
        val niveau = percent?.takeIf { it in 0.0..100.0 } ?: return
        val depart = departPercent
        // Une charge en cours de route remet la mesure à plat : la pente d'avant ne dit plus
        // rien de celle d'après, et la moyenner avec elle donnerait une décharge négative.
        if (depart == null || niveau > depart) {
            departPercent = niveau
            departMillis = nowMillis
        }
        courantPercent = niveau
        courantMillis = nowMillis
    }

    fun reset() {
        departPercent = null
        departMillis = null
        courantPercent = null
        courantMillis = null
    }

    private companion object {
        /** Fenêtre minimale avant de se prononcer (h), et perte minimale (points). */
        const val MIN_HOURS = 0.2
        const val MIN_POINTS = 1.0
    }
}
