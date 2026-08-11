package io.github.jmallus.guidage.ui

import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
import io.github.jmallus.guidage.core.ZoneRange
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RiderLocation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Itinéraire fictif utilisé lorsque le champ est affiché en mode aperçu
 * (écran d'édition des pages), afin qu'il ne reste pas vide.
 */
object PreviewData {

    const val DISTANCE_ALONG_ROUTE = 1_000.0

    /** Position et cap fictifs, pour que la minicarte ait de quoi s'orienter en aperçu. */
    val location = RiderLocation(GeoPoint(45.180, 5.720), heading = 30.0)

    /**
     * Tracé sinueux fictif partant de [location], pour que l'aperçu ressemble à un parcours
     * plutôt qu'à une ligne droite.
     */
    private val previewPath: List<GeoPoint> by lazy {
        (0..120).map { step ->
            val meters = step * 60.0
            val wander = sin(step / 6.0) * 250.0
            GeoPoint(
                lat = location.position.lat + (meters * cos(Math.toRadians(30.0)) + wander) / 110_540.0,
                lng = location.position.lng + (meters * sin(Math.toRadians(30.0)) - wander) / 78_700.0,
            )
        }
    }

    val route: Route by lazy {
        Route(
            name = "Aperçu",
            totalDistance = 12_000.0,
            profile = ElevationProfile(
                listOf(
                    ProfilePoint(0.0, 180.0),
                    ProfilePoint(1_000.0, 190.0),
                    ProfilePoint(2_000.0, 210.0),
                    ProfilePoint(3_000.0, 300.0),
                    ProfilePoint(4_000.0, 420.0),
                    ProfilePoint(5_000.0, 470.0),
                    ProfilePoint(6_000.0, 430.0),
                    ProfilePoint(8_000.0, 330.0),
                    ProfilePoint(10_000.0, 380.0),
                    ProfilePoint(12_000.0, 300.0),
                ),
            ),
            climbs = listOf(
                RouteClimb(startDistance = 2_400.0, length = 2_600.0, grade = 6.5, totalElevation = 260.0),
                RouteClimb(startDistance = 8_000.0, length = 2_000.0, grade = 2.5, totalElevation = 50.0),
            ),
            pois = listOf(
                RoutePoi(
                    id = "preview-water",
                    name = "Fontaine",
                    type = "water",
                    distanceAlongRoute = 2_000.0,
                    position = previewPath.getOrNull(16),
                ),
            ),
            path = previewPath,
        )
    }

    /**
     * Zones fictives, pour que l'aperçu montre la coloration par zone même chez un coureur
     * qui n'a pas encore réglé les siennes.
     */
    private val previewPowerZones = listOf(
        ZoneRange(0, 120), ZoneRange(121, 165), ZoneRange(166, 200), ZoneRange(201, 230),
        ZoneRange(231, 270), ZoneRange(271, 330), ZoneRange(331, 2_000),
    )

    private val previewHeartRateZones = listOf(
        ZoneRange(0, 120), ZoneRange(121, 140), ZoneRange(141, 155),
        ZoneRange(156, 170), ZoneRange(171, 220),
    )

    /**
     * Valeurs qui défilent dans l'aperçu du sélecteur de champs.
     *
     * Elles balaient volontairement plusieurs zones d'effort : c'est ce qui permet de voir,
     * avant de poser le champ sur une page, à quoi ressemblent les aplats de couleur et le
     * passage de l'encre au noir sur les teintes claires.
     */
    fun rideSamples(nowMilliseconds: Long): List<RideData> {
        val averageSpeed = 8.6
        return listOf(
            sample(7.9, averageSpeed, 150.0, 132.0, 84.0, 1.0, 24_300.0, nowMilliseconds, 62),
            sample(10.5, averageSpeed, 188.0, 149.0, 92.0, 4.0, 23_100.0, nowMilliseconds, 58),
            sample(6.2, averageSpeed, 262.0, 166.0, 71.0, 9.0, 21_800.0, nowMilliseconds, 55),
            sample(12.8, averageSpeed, 318.0, 178.0, 98.0, -3.0, 20_400.0, nowMilliseconds, 51),
        )
    }

    private fun sample(
        speed: Double,
        averageSpeed: Double,
        power: Double,
        heartRate: Double,
        cadence: Double,
        grade: Double,
        distanceRemaining: Double,
        nowMilliseconds: Long,
        minutesToArrival: Int,
    ) = RideData(
        speed = speed,
        averageSpeed = averageSpeed,
        power = power,
        heartRate = heartRate,
        cadence = cadence,
        grade = grade,
        distanceRemaining = distanceRemaining,
        arrivalTime = (nowMilliseconds + minutesToArrival * 60_000L).toDouble(),
        powerZones = previewPowerZones,
        heartRateZones = previewHeartRateZones,
    )
}
