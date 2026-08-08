package io.github.jmallus.guidage.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Une position géographique. */
data class GeoPoint(val lat: Double, val lng: Double)

/** Un point projeté dans le repère de l'écran : x vers la droite, y vers le haut, en mètres. */
data class PlanePoint(val x: Double, val y: Double)

/**
 * Projection locale des positions autour du coureur, et rotation « cap en haut ».
 *
 * Sur les quelques kilomètres affichés par la minicarte, une projection équirectangulaire
 * centrée sur la position courante est suffisamment exacte (l'erreur reste très inférieure
 * à l'épaisseur du trait) et évite d'embarquer une bibliothèque cartographique.
 */
object Geo {

    /** Mètres par degré de latitude. */
    const val METERS_PER_DEGREE_LATITUDE = 110_540.0

    /** Mètres par degré de longitude à l'équateur. */
    const val METERS_PER_DEGREE_LONGITUDE = 111_320.0

    /**
     * Projette [point] dans un repère métrique centré sur [origin],
     * orienté est (x) / nord (y).
     */
    fun project(origin: GeoPoint, point: GeoPoint): PlanePoint {
        val east = (point.lng - origin.lng) * METERS_PER_DEGREE_LONGITUDE * cos(Math.toRadians(origin.lat))
        val north = (point.lat - origin.lat) * METERS_PER_DEGREE_LATITUDE
        return PlanePoint(east, north)
    }

    /**
     * Fait pivoter un point est/nord pour que le cap [headingDegrees] pointe vers le haut,
     * comme sur un GPS de voiture. 0° = nord, 90° = est.
     *
     * Avec un cap nul, le repère est inchangé.
     */
    fun rotateToHeading(point: PlanePoint, headingDegrees: Double): PlanePoint {
        val heading = Math.toRadians(headingDegrees)
        return PlanePoint(
            x = point.x * cos(heading) - point.y * sin(heading),
            y = point.x * sin(heading) + point.y * cos(heading),
        )
    }

    /** Projette puis oriente d'un seul geste. */
    fun toTrackUpPlane(origin: GeoPoint, headingDegrees: Double, point: GeoPoint): PlanePoint =
        rotateToHeading(project(origin, point), headingDegrees)

    /** Distance approchée entre deux positions (m). */
    fun distance(from: GeoPoint, to: GeoPoint): Double {
        val projected = project(from, to)
        return hypot(projected.x, projected.y)
    }

    /**
     * Échelle « ronde » à afficher sous la barre d'échelle, la plus grande qui tienne
     * dans [maxMeters] : 100 m, 200 m, 500 m, 1 km, 2 km…
     */
    fun niceScale(maxMeters: Double): Double {
        val candidates = listOf(
            10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
            1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
        )
        return candidates.lastOrNull { it <= maxMeters } ?: candidates.first()
    }
}
