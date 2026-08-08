package io.github.jmallus.guidage.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Système d'unités choisi par le coureur dans son profil Karoo. */
enum class Units { METRIC, IMPERIAL }

/**
 * Formatage des valeurs affichées dans les champs de données.
 *
 * La locale est passée explicitement pour rester testable (séparateur décimal).
 */
object Format {

    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048

    /** Distance courte/longue avec l'unité adaptée : « 450 m », « 2,4 km », « 0.3 mi ». */
    fun distance(meters: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        return when (units) {
            Units.METRIC ->
                if (abs(meters) < 1000) {
                    "${roundTo(meters, 10).roundToInt()} m"
                } else {
                    String.format(locale, "%.1f km", meters / 1000)
                }
            Units.IMPERIAL -> {
                val feet = meters / METERS_PER_FOOT
                if (abs(feet) < 1000) {
                    "${roundTo(feet, 10).roundToInt()} ft"
                } else {
                    String.format(locale, "%.1f mi", meters / METERS_PER_MILE)
                }
            }
        }
    }

    /** Distance toujours exprimée dans la grande unité : « 2,4 km », « 1.5 mi ». */
    fun longDistance(meters: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        return when (units) {
            Units.METRIC -> String.format(locale, "%.1f km", meters / 1000)
            Units.IMPERIAL -> String.format(locale, "%.1f mi", meters / METERS_PER_MILE)
        }
    }

    /** Dénivelé : « 120 m » ou « 394 ft ». */
    fun elevation(meters: Double, units: Units): String {
        return when (units) {
            Units.METRIC -> "${meters.roundToInt()} m"
            Units.IMPERIAL -> "${(meters / METERS_PER_FOOT).roundToInt()} ft"
        }
    }

    /** Pente : « 6,5 % ». */
    fun grade(percent: Double, locale: Locale = Locale.getDefault()): String {
        return String.format(locale, "%.1f %%", percent)
    }

    /** Pente sans décimale, pour les petites surfaces : « 7 % ». */
    fun shortGrade(percent: Double): String = "${percent.roundToInt()} %"

    private fun roundTo(value: Double, step: Int): Double {
        return (value / step).roundToInt().toDouble() * step
    }
}
