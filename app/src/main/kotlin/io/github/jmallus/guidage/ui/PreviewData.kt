package io.github.jmallus.guidage.ui

import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
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
}
