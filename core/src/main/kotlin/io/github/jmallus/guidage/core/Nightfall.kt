package io.github.jmallus.guidage.core

/**
 * Arrivera-t-on avant la nuit ? Le verdict, et ce qu'il coûte s'il est non.
 *
 * Le Karoo affiche une heure d'arrivée et le coureur la compare de tête à l'heure du coucher,
 * qu'il ne connaît qu'à peu près. Deux erreurs s'y glissent : l'heure du coucher est devinée,
 * et l'heure d'arrivée est prise pour exacte alors qu'elle vaut plus ou moins un quart d'heure
 * dès que le parcours a du relief. Le verdict tient compte de cette fourchette : « oui » veut
 * dire que même le cas défavorable passe, « non » que même le cas favorable ne passe pas, et
 * « juste » que le coucher tombe entre les deux — c'est-à-dire qu'il faut décider maintenant
 * si l'on sort la lampe ou si l'on accélère.
 */
object Nightfall {

    enum class Verdict { YES, TIGHT, NO }

    data class Assessment(
        val verdict: Verdict,
        /** Avance de l'arrivée moyenne sur le coucher (s) ; négative quand elle le suit. */
        val marginSeconds: Double,
        /** Incertitude de l'arrivée, de part et d'autre (s). */
        val uncertaintySeconds: Double,
        /**
         * Ce qu'il resterait à parcourir au coucher dans le cas défavorable (m), ou null quand
         * même ce cas-là arrive avant. C'est le chiffre qui dit où la nuit prend le coureur.
         */
        val distanceLeftAtSunset: Double?,
    )

    fun assess(
        nowMillis: Long,
        sunsetMillis: Long,
        arrival: ArrivalEstimate,
        remainingDistance: Double,
    ): Assessment {
        val toSunset = (sunsetMillis - nowMillis) / 1_000.0
        val worst = arrival.seconds + arrival.marginSeconds
        val best = arrival.seconds - arrival.marginSeconds
        val verdict = when {
            worst <= toSunset -> Verdict.YES
            best >= toSunset -> Verdict.NO
            else -> Verdict.TIGHT
        }
        // La progression est supposée régulière sur la durée défavorable : c'est grossier,
        // mais le chiffre sert à situer la nuit sur le parcours, pas à la dater.
        val leftAtSunset = if (worst > toSunset && worst > 0.0) {
            (remainingDistance * (worst - toSunset) / worst).coerceIn(0.0, remainingDistance)
        } else {
            null
        }
        return Assessment(verdict, toSunset - arrival.seconds, arrival.marginSeconds, leftAtSunset)
    }
}
