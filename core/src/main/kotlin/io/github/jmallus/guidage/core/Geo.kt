package io.github.jmallus.guidage.core

import kotlin.math.abs
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

    /**
     * Avance une position de [meters] dans la direction [headingDegrees] (0° = nord).
     *
     * Sert à rattraper le retard du point GPS : le Karoo rapporte une position déjà vieille
     * de quelques secondes, ce qui, à trente kilomètres à l'heure, place le coureur une
     * bonne vingtaine de mètres derrière lui-même. On prolonge donc son mouvement en ligne
     * droite depuis le dernier point connu, ce qui est exact tant qu'il ne tourne pas et
     * reste bien meilleur que l'attendre.
     */
    fun advance(origin: GeoPoint, headingDegrees: Double, meters: Double): GeoPoint {
        if (meters == 0.0) return origin
        val heading = Math.toRadians(headingDegrees)
        val north = meters * cos(heading)
        val east = meters * sin(heading)
        val cosine = cos(Math.toRadians(origin.lat)).let { if (abs(it) < 0.01) 0.01 else it }
        return GeoPoint(
            lat = origin.lat + north / METERS_PER_DEGREE_LATITUDE,
            lng = origin.lng + east / (METERS_PER_DEGREE_LONGITUDE * cosine),
        )
    }

    /** Distance approchée entre deux positions (m). */
    fun distance(from: GeoPoint, to: GeoPoint): Double {
        val projected = project(from, to)
        return hypot(projected.x, projected.y)
    }

    /**
     * Où [point] tombe le long de [path], en mètres depuis le départ — ou null s'il en est
     * trop loin.
     *
     * Le Karoo attache les points d'intérêt à l'itinéraire par proximité et annonce lui-même
     * leur distance ; mais ce champ est facultatif, et il arrive qu'il soit vide alors que le
     * point est bien là. On refait alors le calcul : c'est la même question — sur quel segment
     * du tracé ce point se pose-t-il, et à combien du départ.
     *
     * [maxDeviation] écarte ce qui n'est pas sur l'itinéraire. Un commerce à cinquante mètres
     * de la route en est ; celui du village d'à côté n'en est pas, et l'annoncer comme
     * ravitaillement enverrait le coureur là où il n'y a rien.
     */
    fun distanceAlongPath(
        path: List<GeoPoint>,
        point: GeoPoint,
        maxDeviation: Double,
    ): Double? {
        if (path.size < 2) return null

        var parcouru = 0.0
        var meilleureDistance = 0.0
        var meilleurEcart = Double.MAX_VALUE

        for (i in 1 until path.size) {
            val debut = path[i - 1]
            val fin = path[i]
            // Le repère est centré sur le début du segment : sur quelques dizaines de mètres,
            // la projection équirectangulaire est exacte bien au-delà de ce qu'on mesure.
            val versFin = project(debut, fin)
            val versPoint = project(debut, point)
            val longueur = hypot(versFin.x, versFin.y)
            if (longueur <= 0.0) continue

            // Position du pied de la perpendiculaire sur le segment, bornée à ses extrémités :
            // un point situé au-delà d'un bout s'y rattache, il ne prolonge pas le segment.
            val t = ((versPoint.x * versFin.x + versPoint.y * versFin.y) / (longueur * longueur))
                .coerceIn(0.0, 1.0)
            val ecart = hypot(versPoint.x - t * versFin.x, versPoint.y - t * versFin.y)
            if (ecart < meilleurEcart) {
                meilleurEcart = ecart
                meilleureDistance = parcouru + t * longueur
            }
            parcouru += longueur
        }

        return meilleureDistance.takeIf { meilleurEcart <= maxDeviation }
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
