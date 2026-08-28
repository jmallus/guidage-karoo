package io.github.jmallus.guidage.core

import java.time.Instant
import java.time.ZoneId
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

    /**
     * Conversions impériales, publiques : le choix des graduations en dépend, et il vaut
     * mieux une constante partagée qu'une seconde écriture du même nombre ailleurs.
     */
    const val METERS_PER_MILE = 1609.344
    const val METERS_PER_FOOT = 0.3048

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

    /**
     * Vitesse, à partir des m/s fournis par le Karoo : « 27,4 ».
     * L'unité est retournée séparément par [speedUnit], pour l'afficher en plus petit.
     */
    fun speed(metersPerSecond: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        val value = when (units) {
            Units.METRIC -> metersPerSecond * 3.6
            Units.IMPERIAL -> metersPerSecond * 3600 / METERS_PER_MILE
        }
        return String.format(locale, "%.1f", value)
    }

    fun speedUnit(units: Units): String = when (units) {
        Units.METRIC -> "km/h"
        Units.IMPERIAL -> "mph"
    }

    /**
     * Heure d'horloge à partir d'un instant Unix en millisecondes : « 16:48 ».
     *
     * Le Karoo exprime l'heure d'arrivée estimée de cette façon.
     */
    fun clock(epochMilliseconds: Double, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(epochMilliseconds.toLong()).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }

    private fun roundTo(value: Double, step: Int): Double {
        return (value / step).roundToInt().toDouble() * step
    }
}
