package io.github.jmallus.guidage.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Le coucher du soleil, calculé sur l'appareil.
 *
 * L'extension ne parle à personne : pas d'éphémérides téléchargées, pas de service. Le
 * calcul est celui de la NOAA, bon à une ou deux minutes près — bien en deçà de
 * l'incertitude de l'heure d'arrivée qu'on lui compare.
 *
 * Deux instants sont rendus : le coucher proprement dit, quand le bord du soleil passe sous
 * l'horizon (zénith 90,833° avec la réfraction), et la fin du crépuscule civil (zénith 96°),
 * moment où l'on ne lit plus la route sans lumière. C'est le premier qui sert de référence au
 * verdict, le second dit combien de temps il reste pour rentrer à la lampe.
 */
object Sun {

    data class Times(
        /** Le prochain coucher (ms epoch), toujours dans le futur de l'instant demandé. */
        val sunsetMillis: Long,
        /**
         * La fin du crépuscule civil qui suit ce coucher (ms epoch), ou null quand le soleil se
         * couche sans que la nuit civile ne vienne — les nuits blanches des hautes latitudes.
         */
        val duskMillis: Long?,
    )

    private const val ZENITH_SUNSET = 90.833
    private const val ZENITH_CIVIL_DUSK = 96.0
    private const val DAY_MILLIS = 86_400_000L

    /**
     * Le prochain coucher vu de [position] après [nowMillis], ou null quand le soleil ne se
     * couche pas ces jours-ci — soleil de minuit, ou nuit polaire, pour laquelle il n'y a
     * pas davantage de coucher à attendre.
     */
    fun next(position: GeoPoint, nowMillis: Long): Times? {
        // Le coucher en UTC peut tomber la veille ou le lendemain du jour civil local : on
        // regarde de part et d'autre plutôt que de deviner le fuseau.
        val today = Math.floorDiv(nowMillis, DAY_MILLIS)
        for (day in today - 1..today + 2) {
            val sunset = eventMillis(position, day, ZENITH_SUNSET) ?: continue
            if (sunset <= nowMillis) continue
            val dusk = eventMillis(position, day, ZENITH_CIVIL_DUSK)?.takeIf { it > sunset }
            return Times(sunset, dusk)
        }
        return null
    }

    /**
     * L'instant où le soleil descend sous le zénith [zenith] au cours du jour UTC [epochDay],
     * en minutes depuis 0 h UTC converties en ms epoch ; null s'il n'y passe pas.
     *
     * Le calcul dépend faiblement de l'heure à laquelle on l'évalue : une première passe à midi
     * donne l'heure approchée, une seconde à cette heure-là la corrige.
     */
    private fun eventMillis(position: GeoPoint, epochDay: Long, zenith: Double): Long? {
        var minutes = 720.0
        repeat(2) {
            minutes = settingMinutes(position, epochDay, minutes, zenith) ?: return null
        }
        return epochDay * DAY_MILLIS + (minutes * 60_000.0).toLong()
    }

    /** Minutes UTC après 0 h du jour [epochDay] auxquelles le soleil passe sous [zenith]. */
    private fun settingMinutes(position: GeoPoint, epochDay: Long, atMinutes: Double, zenith: Double): Double? {
        // Jour julien à l'instant évalué, puis siècles juliens depuis J2000.
        val jd = epochDay + 2_440_587.5 + atMinutes / 1_440.0
        val t = (jd - 2_451_545.0) / 36_525.0

        val meanLong = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val centre = sin(rad(meanAnomaly)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * meanAnomaly)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * meanAnomaly)) * 0.000289
        val trueLong = meanLong + centre
        val omega = 125.04 - 1_934.136 * t
        val apparentLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = meanObliquity + 0.00256 * cos(rad(omega))
        val declination = asin(sin(rad(obliquity)) * sin(rad(apparentLong)))

        val y = tan(rad(obliquity / 2)).let { it * it }
        val equationOfTime = 4.0 * deg(
            y * sin(2 * rad(meanLong)) -
                2 * eccentricity * sin(rad(meanAnomaly)) +
                4 * eccentricity * y * sin(rad(meanAnomaly)) * cos(2 * rad(meanLong)) -
                0.5 * y * y * sin(4 * rad(meanLong)) -
                1.25 * eccentricity * eccentricity * sin(2 * rad(meanAnomaly)),
        )

        val lat = rad(position.lat)
        val cosHourAngle = cos(rad(zenith)) / (cos(lat) * cos(declination)) - tan(lat) * tan(declination)
        if (cosHourAngle < -1.0 || cosHourAngle > 1.0) return null
        val hourAngle = deg(acos(cosHourAngle))

        val solarNoon = 720.0 - 4.0 * position.lng - equationOfTime
        return solarNoon + 4.0 * hourAngle
    }

    private fun rad(degrees: Double) = degrees * PI / 180.0

    private fun deg(radians: Double) = radians * 180.0 / PI
}
