package io.github.jmallus.guidage.core

/**
 * Le bilan de la sortie depuis le départ.
 *
 * Ce ne sont pas des mesures instantanées mais des cumuls : ce que la sortie *vaut* jusqu'ici.
 * Le Karoo les publie déjà, un par champ ; leur réunion deux par deux est ce qui les rend
 * lisibles — une moyenne seule ne dit rien tant qu'on n'a pas le maximum à côté d'elle.
 */
data class RideLevel(
    val averageHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val averagePower: Double? = null,
    /**
     * Puissance normalisée : la moyenne pondérée qui compte les relances pour ce qu'elles
     * coûtent. Son écart à la moyenne simple dit si la sortie fut lisse ou hachée.
     */
    val normalizedPower: Double? = null,
    /** Dénivelé positif déjà monté (m). */
    val elevationGain: Double? = null,
    /** Dénivelé positif restant jusqu'à l'arrivée (m), quand un itinéraire est chargé. */
    val elevationRemaining: Double? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    /**
     * Secondes passées dans chaque zone de fréquence cardiaque, la première case étant la
     * zone 1. Vide tant qu'aucune zone n'est réglée ou qu'aucun cœur n'est rapporté.
     */
    val heartRateZoneSeconds: List<Double> = emptyList(),
    /**
     * Ce qu'il reste de la réserve anaérobie, et sa taille (J).
     *
     * Les seules valeurs du bilan que le Karoo ne publie pas : elles sont calculées par
     * l'extension, voir [WPrime].
     */
    val wPrimeBalance: Double? = null,
    val wPrimeCapacity: Double? = null,
    /** La puissance critique retenue pour ce calcul (W), telle qu'elle sera affichée. */
    val criticalPower: Double? = null,
) {
    /** Part de la réserve encore disponible, de 0 à 1. */
    val wPrimeShare: Float?
        get() {
            val taille = wPrimeCapacity?.takeIf { it > 0.0 } ?: return null
            val reste = wPrimeBalance ?: return null
            return (reste / taille).toFloat().coerceIn(0f, 1f)
        }

    /**
     * Le rapport de la normalisée à la moyenne, dit « indice de variabilité ».
     *
     * À un, la sortie s'est tenue à une puissance constante ; au-delà de 1,15, elle fut une
     * succession de relances et de récupérations. C'est la seule des deux valeurs qui ne se
     * lise pas seule, et c'est pourquoi elle est calculée ici plutôt qu'affichée.
     */
    val variability: Double?
        get() {
            val moyenne = averagePower?.takeIf { it > 0.0 } ?: return null
            val normalisee = normalizedPower ?: return null
            return normalisee / moyenne
        }

    /** Part de chaque zone dans le temps mesuré, ou liste vide si rien n'a été mesuré. */
    val heartRateZoneShares: List<Float>
        get() {
            val total = heartRateZoneSeconds.sum()
            if (total <= 0.0) return emptyList()
            return heartRateZoneSeconds.map { (it / total).toFloat() }
        }

    /** Rang de la zone la plus occupée (1 = zone 1), ou null si rien n'a été mesuré. */
    val dominantHeartRateZone: Int?
        get() = heartRateZoneSeconds
            .withIndex()
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0.0 }
            ?.let { it.index + 1 }

    companion object {
        val UNKNOWN = RideLevel()
    }
}

/**
 * Accumule le temps passé dans chaque zone, pas de mesure après pas de mesure.
 *
 * Le Karoo publie la zone courante, jamais le temps qu'on y a passé : c'est à nous de le
 * compter. Un pas trop long est écarté plutôt qu'imputé à la zone courante — l'appareil s'est
 * tu, on ne sait pas ce qui s'est passé pendant ce temps, et l'inventer fausserait le total
 * précisément là où il compte, c'est-à-dire sur les longues sorties.
 */
class ZoneClock(private val zoneCount: Int = ZONES) {

    private val seconds = DoubleArray(zoneCount)
    private var lastMillis: Long? = null

    /** Le temps par zone tel qu'il est mesuré à cet instant. */
    val elapsed: List<Double> get() = seconds.toList()

    /**
     * Rend vrai quand le pas a été imputé à une zone.
     *
     * [zone] est le rang de la zone, 1 pour la première ; zéro ou hors bornes veut dire que
     * la valeur ne tombe dans aucune zone réglée, et le pas est alors perdu à dessein.
     */
    fun observe(nowMillis: Long, zone: Int): Boolean {
        val precedent = lastMillis
        lastMillis = nowMillis
        if (precedent == null) return false

        val pas = (nowMillis - precedent) / 1_000.0
        if (pas <= 0.0 || pas > MAX_STEP_SECONDS) return false
        if (zone !in 1..zoneCount) return false

        seconds[zone - 1] += pas
        return true
    }

    fun reset() {
        seconds.fill(0.0)
        lastMillis = null
    }

    companion object {
        /** Nombre de zones du Karoo. */
        const val ZONES = 7

        /** Au-delà de cet écart entre deux mesures, le pas est écarté (s). */
        const val MAX_STEP_SECONDS = 10.0
    }
}
