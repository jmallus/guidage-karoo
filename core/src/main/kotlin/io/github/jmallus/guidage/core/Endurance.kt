package io.github.jmallus.guidage.core

/**
 * La dérive aérobie : ce que le même effort coûte au cœur, à la fin par rapport au début.
 *
 * On compare le rapport puissance / fréquence cardiaque de la première moitié de la sortie à
 * celui de la seconde. S'il faut plus de battements pour les mêmes watts, c'est que ça se
 * paie — et c'est la seule mesure qui dise où en est le coureur plutôt que ce qu'il a fait.
 *
 * Aucun compteur ne l'affiche en roulant : on la découvre après coup, dans un logiciel
 * d'analyse, quand il est trop tard pour en tenir compte.
 */
data class AerobicDrift(
    /**
     * Perte du rapport, de 0 à 1 : positive quand le cœur monte pour la même puissance.
     *
     * Le signe est celui de la littérature — une dérive positive est une dégradation. Elle
     * peut être négative, quand l'échauffement du début coûtait plus cher que le régime de
     * croisière qui a suivi.
     */
    val ratio: Double,
    /** Le temps d'effort sur lequel le verdict repose (s). */
    val seconds: Double,
)

/**
 * Accumule de quoi calculer la dérive, sans garder la sortie entière.
 *
 * Les relevés sont versés dans des seaux d'égale durée : il suffit alors de sommer les seaux
 * de part et d'autre du milieu. Quand ils deviennent trop nombreux, ils fusionnent deux à
 * deux et leur durée double — la mémoire reste bornée quelle que soit la longueur de la
 * sortie, au prix d'une précision du découpage qui n'a aucune importance ici.
 *
 * Seul le temps d'effort compte : un arrêt où le cœur reste haut sans puissance fausserait
 * la seconde moitié, et c'est précisément ce qu'on cherche à mesurer.
 */
class DriftTracker {

    private class Seau {
        var secondes = 0.0
        var puissance = 0.0
        var coeur = 0.0
    }

    private val seaux = ArrayList<Seau>()
    private var largeur = BUCKET_SECONDS
    private var lastMillis: Long? = null

    /** Le temps d'effort mesuré jusqu'ici (s). */
    val seconds: Double get() = seaux.sumOf { it.secondes }

    /**
     * Rend vrai quand le pas a été versé.
     *
     * Une puissance nulle ou absente n'est pas un effort : le pas est perdu, mais l'instant
     * est retenu, de sorte qu'un arrêt de dix minutes n'imputera rien au redémarrage.
     */
    fun observe(nowMillis: Long, powerWatts: Double?, heartRate: Double?): Boolean {
        val precedent = lastMillis
        lastMillis = nowMillis
        if (precedent == null) return false

        val pas = (nowMillis - precedent) / 1_000.0
        if (pas <= 0.0 || pas > MAX_STEP_SECONDS) return false
        val puissance = powerWatts?.takeIf { it > 0.0 } ?: return false
        val coeur = heartRate?.takeIf { it > 0.0 } ?: return false

        verser(pas, puissance, coeur)
        return true
    }

    private fun verser(pas: Double, puissance: Double, coeur: Double) {
        if (seaux.isEmpty() || seaux.last().secondes >= largeur) {
            if (seaux.size >= MAX_BUCKETS) fusionner()
            seaux.add(Seau())
        }
        seaux.last().let {
            it.secondes += pas
            it.puissance += puissance * pas
            it.coeur += coeur * pas
        }
    }

    /** Deux seaux n'en font plus qu'un, et leur durée double. */
    private fun fusionner() {
        val fondus = ArrayList<Seau>(seaux.size / 2 + 1)
        var index = 0
        while (index < seaux.size) {
            val fondu = Seau()
            for (part in seaux.subList(index, minOf(index + 2, seaux.size))) {
                fondu.secondes += part.secondes
                fondu.puissance += part.puissance
                fondu.coeur += part.coeur
            }
            fondus.add(fondu)
            index += 2
        }
        seaux.clear()
        seaux.addAll(fondus)
        largeur *= 2
    }

    /**
     * La dérive, ou null tant qu'il n'y a pas de quoi la calculer.
     *
     * Elle se tait sous [MIN_SECONDS] d'effort : sur une demi-heure, le seul échauffement
     * suffit à produire un chiffre spectaculaire et dépourvu de sens.
     */
    fun drift(): AerobicDrift? {
        val total = seconds
        if (total < MIN_SECONDS) return null

        val milieu = total / 2.0
        var parcouru = 0.0
        var puissanceA = 0.0
        var coeurA = 0.0
        var puissanceB = 0.0
        var coeurB = 0.0
        for (seau in seaux) {
            if (seau.secondes <= 0.0) continue
            // Le seau à cheval sur le milieu est partagé au prorata : sans cela, un seau
            // fondu de plusieurs minutes ferait basculer la moitié d'un bloc.
            val avant = (milieu - parcouru).coerceIn(0.0, seau.secondes)
            val part = avant / seau.secondes
            puissanceA += seau.puissance * part
            coeurA += seau.coeur * part
            puissanceB += seau.puissance * (1.0 - part)
            coeurB += seau.coeur * (1.0 - part)
            parcouru += seau.secondes
        }
        if (coeurA <= 0.0 || coeurB <= 0.0) return null
        val premier = puissanceA / coeurA
        val second = puissanceB / coeurB
        if (premier <= 0.0) return null
        return AerobicDrift(ratio = (premier - second) / premier, seconds = total)
    }

    fun reset() {
        seaux.clear()
        largeur = BUCKET_SECONDS
        lastMillis = null
    }

    companion object {
        /** Durée initiale d'un seau (s), et nombre au-delà duquel ils fusionnent. */
        const val BUCKET_SECONDS = 60.0
        const val MAX_BUCKETS = 240

        /** Au-delà de cet écart entre deux mesures, le pas est écarté (s). */
        const val MAX_STEP_SECONDS = 10.0

        /** Temps d'effort en deçà duquel la dérive ne veut rien dire (s) : une heure. */
        const val MIN_SECONDS = 3_600.0
    }
}

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
