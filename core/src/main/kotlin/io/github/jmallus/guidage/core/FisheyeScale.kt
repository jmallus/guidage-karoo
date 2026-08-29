package io.github.jmallus.guidage.core

import kotlin.math.expm1
import kotlin.math.ln1p

/**
 * L'échelle du profil à venir : fine devant, comprimée au loin.
 *
 * Le champ « Profil à venir » avait une portée réglable, de un à quinze kilomètres. Ce
 * réglage était l'aveu d'un choix impossible : à cinq kilomètres on voit la rampe qui arrive
 * mais plus la journée, à quinze on voit la journée mais la rampe tient dans deux pixels. Et
 * le choix se pose en roulant, c'est-à-dire au moment où l'on ne veut rien régler.
 *
 * Une échelle non linéaire le supprime. La distance est projetée par un logarithme
 * translaté — `ln(1 + d/f)` — qui vaut zéro au coureur et croît sans jamais s'arrêter : les
 * premières centaines de mètres occupent autant de largeur que les dix derniers kilomètres.
 * Le même bandeau montre alors la rampe dans trois cents mètres et le col de la quatrième
 * heure, sans rien réduire à un trait.
 *
 * Le facteur [fine] est la distance en deçà de laquelle l'échelle reste à peu près
 * proportionnelle : c'est lui qui fixe le grain du premier plan, et lui seul. Le taux de
 * compression entre les deux bouts vaut `(fine + span) / fine` et se déduit donc du parcours
 * restant, sans autre réglage.
 *
 * Cette compression ne se voit pas d'elle-même : un œil qui suppose une échelle régulière
 * lit un faux relief, une bosse lointaine lui paraissant plus courte qu'elle n'est. Ce sont
 * les graduations de [ticks] qui la disent — leur espacement inégal est le seul aveu que la
 * carte ne soit pas plate. Un profil œil de poisson sans graduations serait un mensonge.
 */
class FisheyeScale(
    /** Distance restant à parcourir, en mètres : le bord droit du champ. */
    val span: Double,
    /** Distance sous laquelle l'échelle reste à peu près proportionnelle (m). */
    val fine: Double = FINE_METERS,
) {

    private val denominator: Double =
        if (span > 0.0 && fine > 0.0) ln1p(span / fine) else 0.0

    /** Faux quand il n'y a rien à projeter — parcours fini, ou profil absent. */
    val usable: Boolean get() = denominator > 0.0

    /**
     * Taux de compression entre le premier mètre et le dernier.
     *
     * Un rapport de deux cents veut dire qu'un mètre près du coureur occupe deux cents fois
     * la largeur d'un mètre près de l'arrivée. Sert surtout à s'assurer que le chiffre reste
     * dicible : la valeur elle-même n'entre dans aucun calcul.
     */
    val compression: Double get() = if (usable) (fine + span) / fine else 1.0

    /** Part de la largeur, de 0 au coureur à 1 à l'arrivée. */
    fun fractionAt(distanceAhead: Double): Double {
        if (!usable) return 0.0
        return ln1p(distanceAhead.coerceIn(0.0, span) / fine) / denominator
    }

    /** L'inverse : quelle distance tombe à cette part de la largeur. */
    fun distanceAt(fraction: Double): Double {
        if (!usable) return 0.0
        return (fine * expm1(fraction.coerceIn(0.0, 1.0) * denominator)).coerceIn(0.0, span)
    }

    /**
     * Les graduations à porter sous l'axe.
     *
     * Prises dans une échelle 1-2-5, celle des règles et des axes : les valeurs y sont
     * mémorables, et leurs images par un logarithme tombent déjà à peu près régulièrement,
     * de sorte qu'il en reste toujours quelques-unes après élagage.
     *
     * [minimumGap] est la part de largeur en deçà de laquelle deux graduations se
     * chevaucheraient : c'est au dessin de la connaître, lui seul sachant la largeur du champ
     * et le corps du texte. On écarte aussi celles qui frôlent le bord droit, où l'étiquette
     * sortirait du champ.
     */
    fun ticks(minimumGap: Double = MINIMUM_GAP, unit: Double = KILOMETER): List<Tick> {
        if (!usable || unit <= 0.0) return emptyList()
        val kept = ArrayList<Tick>()
        var previous = 0.0
        for (multiple in LADDER) {
            val step = multiple * unit
            if (step >= span) break
            val fraction = fractionAt(step)
            if (1.0 - fraction < minimumGap) break
            if (fraction - previous < minimumGap) continue
            kept.add(Tick(step, multiple, fraction))
            previous = fraction
        }
        return kept
    }

    /**
     * Une graduation.
     *
     * @property distance sa distance en mètres, pour la placer
     * @property value la même, comptée dans l'unité du coureur, pour l'écrire — « 0,5 », « 20 »
     * @property fraction sa place, en part de largeur
     */
    data class Tick(val distance: Double, val value: Double, val fraction: Double)

    companion object {
        /**
         * Le grain du premier plan, en mètres.
         *
         * Deux cents mètres : c'est la distance à laquelle une rampe cesse d'être une
         * information de navigation pour devenir une information d'effort — on ne choisit
         * plus son braquet à deux kilomètres. En deçà, l'échelle reste franche.
         */
        const val FINE_METERS = 200.0

        /** Écart minimal entre deux graduations, en part de largeur, à défaut de mieux. */
        const val MINIMUM_GAP = 0.10

        /** Le kilomètre, unité de graduation par défaut. */
        const val KILOMETER = 1_000.0

        /**
         * L'échelle 1-2-5, en multiples de l'unité du coureur, jusqu'à deux cents.
         *
         * En multiples et non en mètres : un axe gradué tous les cent mètres se lit « 0,1 mi,
         * 0,3 mi, 0,6 mi » pour qui roule en milles, ce qui n'est plus une échelle mais une
         * conversion. Les repères doivent être ronds dans l'unité qu'on lit, pas dans celle
         * du calcul.
         *
         * Deux cents unités suffisent : au-delà, plus aucun parcours ne se charge dans un
         * Karoo, et une graduation qu'on n'atteint jamais est du code qu'on ne vérifie jamais.
         */
        private val LADDER = doubleArrayOf(
            0.1, 0.2, 0.5,
            1.0, 2.0, 5.0,
            10.0, 20.0, 50.0,
            100.0, 200.0,
        )
    }
}
