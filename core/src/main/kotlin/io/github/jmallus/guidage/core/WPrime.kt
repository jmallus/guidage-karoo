package io.github.jmallus.guidage.core

/**
 * Les deux paramètres du modèle de puissance critique.
 *
 * Nuls par défaut, c'est-à-dire déduits de ce que le Karoo sait déjà du coureur : la FTP
 * réglée sur l'appareil pour la puissance critique, le poids pour la réserve. C'est le seul
 * moyen que le champ marche sans qu'on ait rien à régler — et un champ qu'il faut configurer
 * avant de voir quoi que ce soit ne se configure jamais.
 */
data class WPrimeSettings(
    /** Puissance critique (W), ou null pour prendre la FTP de l'appareil. */
    val criticalPower: Int? = null,
    /** Réserve anaérobie (J), ou null pour la déduire du poids. */
    val capacityJoules: Int? = null,
)

/**
 * La réserve anaérobie, et ce qu'il en reste.
 *
 * C'est le modèle de puissance critique : au-dessus de la CP, l'effort puise dans une réserve
 * de taille fixe — W′, quelques dizaines de kilojoules — qui se reconstitue en dessous. Le
 * Karoo ne publie rien de tel : de tous les champs de bilan, c'est le seul qui soit un calcul
 * de l'extension et non un nombre de l'appareil rangé autrement.
 *
 * La forme retenue est la différentielle de Froncioni, telle que Clarke et Skiba l'ont
 * publiée en 2013 : elle ne demande aucun historique, seulement l'état précédent et le pas
 * écoulé. C'est ce qui la rend tenable ici — l'intégrale d'origine réclame de garder toute la
 * sortie en mémoire et de la reparcourir à chaque relevé.
 *
 * **Ce que le champ vaut, et ce qu'il ne vaut pas.** La FTP n'est pas la puissance critique :
 * elle lui est proche, un peu au-dessus, et l'écart varie d'un coureur à l'autre. La réserve
 * par défaut est une moyenne de population — trois cents joules par kilogramme — qui peut se
 * tromper du simple au double sur quelqu'un en particulier. Le nombre affiché est donc une
 * jauge relative, à lire comme « combien de cartouches il me reste », et non une quantité de
 * joules qu'on pourrait comparer d'un coureur à l'autre. Les deux paramètres sont réglables
 * pour cette raison.
 */
object WPrime {

    /**
     * L'état de la réserve après un pas de [seconds] passé à [power].
     *
     * Au-dessus de la puissance critique, la réserve se vide de l'excédent — chaque watt
     * au-dessus de la CP coûte un joule par seconde. En dessous, elle se recharge d'autant
     * plus vite qu'elle est vide et que l'on roule doucement : c'est ce terme-là qui fait
     * tout l'intérêt du modèle, la récupération n'étant ni linéaire ni instantanée.
     */
    fun step(
        balance: Double,
        capacity: Double,
        criticalPower: Double,
        power: Double,
        seconds: Double,
    ): Double {
        if (capacity <= 0.0) return 0.0
        val suivant = if (power > criticalPower) {
            balance - (power - criticalPower) * seconds
        } else {
            balance + (capacity - balance) * (criticalPower - power) / capacity * seconds
        }
        return suivant.coerceIn(0.0, capacity)
    }

    /** La réserve d'un coureur dont on ne sait que le poids (J). */
    fun defaultCapacity(weightKilograms: Double): Double =
        weightKilograms * JOULES_PER_KILOGRAM

    /**
     * Réserve anaérobie par kilogramme, chez un coureur entraîné (J/kg).
     *
     * Une moyenne de population, et rien de plus : les valeurs publiées vont du simple au
     * double selon le profil. Elle sert de point de départ, pas de vérité.
     */
    const val JOULES_PER_KILOGRAM = 300.0
}

/**
 * Suit la réserve au fil de la sortie.
 *
 * Il vit auprès des relevés et non dans le champ : la réserve se mesure sur la sortie
 * entière, non sur la durée d'affichage d'une page. Un champ posé sur la troisième page
 * hériterait sans cela d'une réserve pleine à chaque fois qu'on tourne la page.
 */
class WPrimeTracker {

    private var capacity: Double = 0.0
    private var lastMillis: Long? = null

    /** Ce qu'il reste dans la réserve (J), ou null tant que les paramètres manquent. */
    var balance: Double? = null
        private set

    /** La taille de la réserve retenue (J), ou null tant que les paramètres manquent. */
    val size: Double? get() = capacity.takeIf { it > 0.0 && balance != null }

    /**
     * Rend vrai quand le pas a été imputé à la réserve.
     *
     * Une puissance absente vaut zéro et non « inconnu » : le Karoo cesse d'émettre quand on
     * ne pédale plus, et c'est précisément le moment où la réserve se recharge le plus vite.
     * L'écarter reviendrait à ne jamais récupérer dans les descentes.
     */
    fun observe(
        nowMillis: Long,
        powerWatts: Double?,
        criticalPower: Double?,
        capacityJoules: Double?,
    ): Boolean {
        val cp = criticalPower?.takeIf { it > 0.0 }
        val taille = capacityJoules?.takeIf { it > 0.0 }
        if (cp == null || taille == null) {
            lastMillis = nowMillis
            return false
        }
        // Un changement de réglage en cours de sortie repart d'une réserve pleine : la
        // proportion tenue jusque-là ne veut plus rien dire une fois l'échelle changée, et
        // la transposer donnerait un chiffre faux d'apparence exacte. Le pas courant est
        // perdu avec elle — c'est une frontière, comme un départ.
        if (taille != capacity) {
            capacity = taille
            balance = taille
            lastMillis = nowMillis
            return false
        }
        val precedent = lastMillis
        lastMillis = nowMillis
        val courant = balance ?: return false
        if (precedent == null) return false

        val pas = (nowMillis - precedent) / 1_000.0
        if (pas <= 0.0 || pas > MAX_STEP_SECONDS) return false

        balance = WPrime.step(courant, capacity, cp, powerWatts ?: 0.0, pas)
        return true
    }

    fun reset() {
        lastMillis = null
        balance = capacity.takeIf { it > 0.0 }
    }

    companion object {
        /** Au-delà de cet écart entre deux mesures, le pas est écarté (s). */
        const val MAX_STEP_SECONDS = 10.0
    }
}
