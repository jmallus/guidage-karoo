package io.github.jmallus.guidage.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Une position géographique. */
data class GeoPoint(val lat: Double, val lng: Double)

/** Un point projeté dans le repère de l'écran : x vers la droite, y vers le haut, en mètres. */
data class PlanePoint(val x: Double, val y: Double)

/** Où un point se tient sur un tracé, voir [Geo.anchorOnPath]. */
data class PathAnchor(
    /** Le sommet du tracé qui suit le point : le segment d'accroche va de `index - 1` à `index`. */
    val index: Int,
    /** L'aplomb du point sur le tracé. */
    val point: GeoPoint,
    /** Distance de cet aplomb depuis le départ du tracé (m). */
    val distanceAlongPath: Double,
    /** Écart entre le point et son aplomb (m). */
    val deviation: Double,
)

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
    ): Double? = anchorOnPath(path, point, expectedAlong = null, tolerance = 0.0)
        ?.takeIf { it.deviation <= maxDeviation }
        ?.distanceAlongPath

    /**
     * Où [point] se tient sur [path] : son aplomb sur le tracé, le sommet qui le suit, la
     * distance depuis le départ et l'écart.
     *
     * C'est le point du tracé le plus proche — à une réserve près, qui est toute la raison
     * de [expectedAlong]. Un parcours peut emprunter deux fois la même route : une boucle qui
     * revient par où elle est partie, un aller-retour au bout d'une impasse. Le point le plus
     * proche y est deux fois le même, à des kilomètres d'écart en abscisse, et prendre le
     * premier venu revient à placer le coureur au **départ** quand il arrive. Le sens du
     * tracé s'inverse alors sous lui : ce qui reste à faire devient tout le parcours, et les
     * jalons le renvoient d'où il vient. Une sortie réelle l'a montré, au dernier carrefour.
     *
     * L'appareil, lui, sait où l'on en est — il annonce la distance restante. Elle se décale
     * de quelques dizaines de mètres et ne peut pas servir d'aplomb ; mais pour départager
     * deux passages à des kilomètres l'un de l'autre, elle est sans appel. Parmi les segments
     * qui se tiennent à moins de [tolerance] du meilleur, on retient donc celui dont
     * l'abscisse est la plus proche de [expectedAlong]. Sans attente, ou sans concurrent
     * dans la tolérance, c'est simplement le plus proche.
     *
     * Rend null quand le tracé n'a pas de segment.
     */
    fun anchorOnPath(
        path: List<GeoPoint>,
        point: GeoPoint,
        expectedAlong: Double?,
        tolerance: Double,
    ): PathAnchor? {
        if (path.size < 2) return null

        // Premier passage : le plus proche, sans réserve.
        var meilleur: PathAnchor? = null
        eachProjection(path, point) { candidat ->
            if (meilleur == null || candidat.deviation < meilleur!!.deviation) meilleur = candidat
        }
        val proche = meilleur ?: return null
        if (expectedAlong == null) return proche

        // Second passage : parmi ceux qui font aussi bien à la tolérance près, le plus
        // conforme à l'abscisse attendue. Le tracé se relit en entier, ce qui coûte moins que
        // de garder tous les candidats du premier passage pour ne les trier qu'une fois.
        var retenu = proche
        val seuil = proche.deviation + tolerance
        eachProjection(path, point) { candidat ->
            if (candidat.deviation <= seuil &&
                abs(candidat.distanceAlongPath - expectedAlong) < abs(retenu.distanceAlongPath - expectedAlong)
            ) {
                retenu = candidat
            }
        }
        return retenu
    }

    /** La projection de [point] sur chaque segment de [path], dans l'ordre du tracé. */
    private inline fun eachProjection(path: List<GeoPoint>, point: GeoPoint, visit: (PathAnchor) -> Unit) {
        var parcouru = 0.0
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
            visit(PathAnchor(i, interpolate(debut, fin, t), parcouru + t * longueur, ecart))
            parcouru += longueur
        }
    }

    /**
     * La portion de [path] comprise entre deux distances depuis son départ.
     *
     * Les deux bouts sont interpolés sur le segment qu'ils coupent, et non arrondis au sommet
     * le plus proche : sur un tracé dont les points sont espacés de cinquante mètres, arrondir
     * déplacerait la frontière d'autant, et une bascule de revêtement se verrait au mauvais
     * endroit — à l'échelle de la minicarte, cinquante mètres sont un cinquième de l'écran.
     *
     * Rend une liste vide quand l'intervalle est vide ou hors du tracé.
     */
    fun pathBetween(path: List<GeoPoint>, from: Double, to: Double): List<GeoPoint> {
        if (path.size < 2 || to <= from) return emptyList()

        val portion = mutableListOf<GeoPoint>()
        var parcouru = 0.0
        for (i in 1 until path.size) {
            val segment = distance(path[i - 1], path[i])
            if (segment <= 0.0) continue
            val fin = parcouru + segment

            if (fin > from && parcouru < to) {
                // Le premier point est le début du segment, ou le point coupé quand la
                // portion commence au milieu de celui-ci.
                if (portion.isEmpty()) {
                    val part = ((from - parcouru) / segment).coerceIn(0.0, 1.0)
                    portion += interpolate(path[i - 1], path[i], part)
                }
                if (fin <= to) {
                    portion += path[i]
                } else {
                    portion += interpolate(path[i - 1], path[i], ((to - parcouru) / segment).coerceIn(0.0, 1.0))
                    break
                }
            }
            parcouru = fin
        }
        return portion.takeIf { it.size >= 2 }.orEmpty()
    }

    /** Le point situé à la fraction [part] du segment allant de [from] à [to]. */
    private fun interpolate(from: GeoPoint, to: GeoPoint, part: Double): GeoPoint = GeoPoint(
        lat = from.lat + (to.lat - from.lat) * part,
        lng = from.lng + (to.lng - from.lng) * part,
    )

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
