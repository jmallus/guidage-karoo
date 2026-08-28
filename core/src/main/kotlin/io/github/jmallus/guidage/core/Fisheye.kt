package io.github.jmallus.guidage.core

import kotlin.math.expm1
import kotlin.math.ln1p

/**
 * Échelle de distance non linéaire : le proche au pas fin, le lointain comprimé.
 *
 * Le champ « Profil à venir » a une portée réglable, et ce réglage est l'aveu d'un choix
 * impossible : voir le mur qui arrive, ou voir la journée. Une échelle logarithmique
 * supprime le choix. Les deux premiers kilomètres prennent près de la moitié de la largeur,
 * le reste se tasse derrière, et un seul bandeau montre la rampe dans trois cents mètres et
 * le col de la quatrième heure.
 *
 * La compression ne se devine pas : c'est aux graduations de la dire, sans quoi l'œil lit un
 * relief faux. [ticks] donne les distances rondes à porter sous l'axe ; leur espacement
 * inégal est l'information.
 */
class FisheyeScale private constructor(
    /** Distance couverte par l'échelle, du coureur à l'arrivée (m). */
    val totalMeters: Double,
    /** Facteur de la projection : plus il est petit, plus le lointain se comprime. */
    val factor: Double,
) {

    private val denominator: Double = ln1p(totalMeters / factor)

    /** Position relative (0 au coureur, 1 à l'arrivée) d'une distance devant lui. */
    fun position(aheadMeters: Double): Double {
        if (aheadMeters <= 0.0) return 0.0
        if (aheadMeters >= totalMeters) return 1.0
        return ln1p(aheadMeters / factor) / denominator
    }

    /** L'inverse : ce que porte une position donnée. C'est par là que dessine le rendu. */
    fun distanceAt(position: Double): Double {
        if (position <= 0.0) return 0.0
        if (position >= 1.0) return totalMeters
        return factor * expm1(position * denominator)
    }

    companion object {

        /** Portion vue au pas fin : les deux premiers kilomètres (m). */
        const val DEFAULT_FINE_METERS = 2_000.0

        /** Part de la largeur qui leur revient. */
        const val DEFAULT_FINE_SHARE = 0.45

        /**
         * Échelle couvrant [totalMeters], ou null quand elle n'apprendrait rien.
         *
         * Sur un itinéraire plus court que la portée fine, ou à peine plus long, comprimer
         * un lointain qui n'existe pas ne ferait que fausser la lecture : le rendu retombe
         * alors sur une échelle droite.
         */
        fun of(
            totalMeters: Double,
            fineMeters: Double = DEFAULT_FINE_METERS,
            fineShare: Double = DEFAULT_FINE_SHARE,
        ): FisheyeScale? {
            if (totalMeters <= 0.0 || fineMeters <= 0.0) return null
            // En deçà, la part demandée est celle qu'une échelle droite donne déjà.
            if (fineMeters / totalMeters >= fineShare) return null

            return FisheyeScale(totalMeters, solveFactor(totalMeters, fineMeters, fineShare))
        }

        /**
         * Cherche le facteur qui donne à [fineMeters] la part [fineShare] de la largeur.
         *
         * Le rapport `ln(1 + f/k) / ln(1 + D/k)` décroît continûment de 1 — quand k tend vers
         * zéro et que les deux logarithmes se confondent — jusqu'à `f/D`, l'échelle droite,
         * quand k devient grand. Une part comprise entre ces deux bornes a donc une solution
         * et une seule, qu'on approche par dichotomie : il n'en existe pas de forme close.
         */
        private fun solveFactor(total: Double, fine: Double, share: Double): Double {
            var low = total / 1_000_000.0
            var high = total * 1_000.0
            repeat(BISECTION_STEPS) {
                val middle = (low + high) / 2.0
                if (shareAt(middle, total, fine) > share) low = middle else high = middle
            }
            return (low + high) / 2.0
        }

        private fun shareAt(factor: Double, total: Double, fine: Double): Double {
            val denominator = ln1p(total / factor)
            if (denominator <= 0.0) return 1.0
            return ln1p(fine / factor) / denominator
        }

        private const val BISECTION_STEPS = 80

        /** Distances rondes proposées comme graduations, en mètres. */
        private val NICE_METRIC = listOf(
            100.0, 200.0, 500.0,
            1_000.0, 2_000.0, 5_000.0,
            10_000.0, 20_000.0, 50_000.0,
            100_000.0, 200_000.0, 500_000.0,
        )

        /** Les mêmes, rondes en milles : un coureur en impérial ne lit pas 1,6 mi. */
        private val NICE_IMPERIAL = listOf(
            0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
        ).map { it * Format.METERS_PER_MILE }

        /**
         * Graduations rondes tenant dans [totalMeters], de la plus proche à la plus lointaine.
         *
         * Le rendu écarte ensuite celles qui se toucheraient : c'est lui qui connaît la
         * largeur en pixels, et le nombre de graduations lisibles n'en dépend que.
         */
        fun ticks(totalMeters: Double, units: Units = Units.METRIC): List<Double> {
            val nice = if (units == Units.METRIC) NICE_METRIC else NICE_IMPERIAL
            return nice.filter { it < totalMeters }
        }
    }
}
