package io.github.jmallus.guidage.tools

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.toMicroDegrees
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos

/**
 * Lecture du GeoJSON par lignes produit par `osmium export`.
 *
 * Un objet JSON complet par ligne : le fichier se lit d'un bout à l'autre sans jamais
 * tenir en mémoire, ce qui compte quand une région en pèse plusieurs giga-octets.
 */
object GeoJsonSeq {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convertit une ligne en objets de fond de carte.
     *
     * Une ligne donne au plus un objet, sauf les multi-polygones — un bois en plusieurs
     * morceaux — qui en donnent un par morceau. Sont écartés : les lignes vides, les
     * géométries qu'on ne sait pas dessiner, les tags qu'on ne garde pas, et tout ce qui se
     * réduit à un point ou à un mouchoir de poche une fois les coordonnées arrondies.
     */
    fun toSegments(line: String): List<RoadSegment> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val feature = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()
        val geometry = feature["geometry"]?.jsonObject ?: return emptyList()
        val properties = feature["properties"]?.jsonObject ?: return emptyList()
        val type = geometry["type"]?.jsonPrimitive?.content ?: return emptyList()
        val coordinates = geometry["coordinates"]?.jsonArray ?: return emptyList()

        return when (type) {
            "LineString" -> listOfNotNull(lineSegment(properties, coordinates))
            "Polygon" -> listOfNotNull(areaSegment(properties, outerRing(coordinates)))
            "MultiPolygon" -> coordinates.mapNotNull { polygon ->
                areaSegment(properties, outerRing(polygon.jsonArray))
            }
            else -> emptyList()
        }
    }

    /** Un seul objet, pour le cas courant — commodité de lecture et des tests. */
    fun toSegment(line: String): RoadSegment? = toSegments(line).firstOrNull()

    /** Voie ou cours d'eau : une ligne, gardée telle quelle. */
    private fun lineSegment(properties: JsonObject, coordinates: JsonArray): RoadSegment? {
        val kind = properties.tag("highway")?.let { RoadKind.fromHighwayTag(it) }
            ?: properties.tag("waterway")?.let { RoadKind.fromWaterwayTag(it) }
            ?: return null
        val points = points(coordinates) ?: return null
        if (points.first.size < 2) return null
        return RoadSegment(kind, surfaceOf(properties), points.first, points.second)
    }

    /**
     * Surface : le contour extérieur, simplifié.
     *
     * Un bois d'OpenStreetMap suit la lisière au mètre près, ce qui fait des milliers de
     * points pour une tache de quelques pixels. La simplification ramène le contour à ce
     * qui se voit — une dizaine de mètres — et les surfaces trop petites sont écartées :
     * une mare de vingt mètres ne se distinguerait pas d'un défaut de l'écran.
     *
     * La simplification a lieu avant que le point de fermeture ne soit retiré : c'est lui
     * qui dit à l'algorithme qu'il a affaire à une boucle et non à une ligne, faute de quoi
     * le dernier point de l'anneau — un point quelconque de la lisière — serait retenu comme
     * une extrémité.
     */
    private fun areaSegment(properties: JsonObject, coordinates: JsonArray?): RoadSegment? {
        if (coordinates == null) return null
        val kind = RoadKind.fromAreaTags(
            natural = properties.tag("natural"),
            landuse = properties.tag("landuse"),
            waterway = properties.tag("waterway"),
        ) ?: return null
        val points = points(coordinates) ?: return null
        val simplified = simplify(points.first, points.second, SIMPLIFY_TOLERANCE_METERS)
        val contour = opened(simplified)
        if (contour.first.size < 3) return null
        if (area(contour.first, contour.second) < minimumArea(kind)) return null
        return RoadSegment(kind, RoadSurface.UNKNOWN, contour.first, contour.second)
    }

    /**
     * Retire le point de fermeture d'un anneau GeoJSON.
     *
     * Un anneau répète son premier point à la fin ; un contour, lui, se referme tout seul.
     * Garder la répétition coûterait un point par surface et laisserait un côté de longueur
     * nulle au découpage.
     */
    private fun opened(points: Pair<IntArray, IntArray>): Pair<IntArray, IntArray> {
        val (latitudes, longitudes) = points
        val last = latitudes.size - 1
        if (last < 1) return points
        if (latitudes[0] != latitudes[last] || longitudes[0] != longitudes[last]) return points
        return latitudes.copyOfRange(0, last) to longitudes.copyOfRange(0, last)
    }

    /** Le premier anneau d'un polygone est son contour ; les suivants sont ses trous. */
    private fun outerRing(polygon: JsonArray): JsonArray? =
        polygon.firstOrNull()?.jsonArray

    private fun JsonObject.tag(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun surfaceOf(properties: JsonObject): RoadSurface =
        RoadSurface.fromSurfaceTag(properties.tag("surface"))

    /** Coordonnées en micro-degrés, points confondus écartés. */
    private fun points(coordinates: JsonArray): Pair<IntArray, IntArray>? {
        val latitudes = ArrayList<Int>(coordinates.size)
        val longitudes = ArrayList<Int>(coordinates.size)
        for (point in coordinates) {
            val pair = point.jsonArray
            if (pair.size < 2) continue
            // GeoJSON écrit la longitude avant la latitude.
            val longitude = pair[0].jsonPrimitive.content.toDoubleOrNull() ?: continue
            val latitude = pair[1].jsonPrimitive.content.toDoubleOrNull() ?: continue
            val latitudeMicro = latitude.toMicroDegrees()
            val longitudeMicro = longitude.toMicroDegrees()
            // L'arrondi au micro-degré peut confondre deux points voisins : les garder
            // ferait des segments de longueur nulle, sans effet visible mais qui pèsent.
            if (latitudes.isNotEmpty() &&
                latitudes.last() == latitudeMicro &&
                longitudes.last() == longitudeMicro
            ) {
                continue
            }
            latitudes.add(latitudeMicro)
            longitudes.add(longitudeMicro)
        }
        if (latitudes.isEmpty()) return null
        return latitudes.toIntArray() to longitudes.toIntArray()
    }

    /**
     * Simplification de Douglas et Peucker : on ne garde que les points qui s'écartent de
     * plus de [toleranceMeters] de la corde qui les enjambe.
     */
    internal fun simplify(
        latitudes: IntArray,
        longitudes: IntArray,
        toleranceMeters: Double,
    ): Pair<IntArray, IntArray> {
        if (latitudes.size <= 3) return latitudes to longitudes
        val keep = BooleanArray(latitudes.size)
        keep[0] = true
        keep[latitudes.size - 1] = true
        val cosine = cos(Math.toRadians(latitudes[0] / 1_000_000.0))
        simplifyRange(latitudes, longitudes, 0, latitudes.size - 1, toleranceMeters, cosine, keep)

        val outLatitudes = ArrayList<Int>(latitudes.size)
        val outLongitudes = ArrayList<Int>(longitudes.size)
        latitudes.indices.filter { keep[it] }.forEach {
            outLatitudes.add(latitudes[it])
            outLongitudes.add(longitudes[it])
        }
        return outLatitudes.toIntArray() to outLongitudes.toIntArray()
    }

    private fun simplifyRange(
        latitudes: IntArray,
        longitudes: IntArray,
        first: Int,
        last: Int,
        toleranceMeters: Double,
        cosine: Double,
        keep: BooleanArray,
    ) {
        if (last <= first + 1) return
        var farthest = -1
        var farthestDistance = 0.0
        for (index in first + 1 until last) {
            val distance = distanceToChord(latitudes, longitudes, first, last, index, cosine)
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthest = index
            }
        }
        if (farthest < 0 || farthestDistance < toleranceMeters) return
        keep[farthest] = true
        simplifyRange(latitudes, longitudes, first, farthest, toleranceMeters, cosine, keep)
        simplifyRange(latitudes, longitudes, farthest, last, toleranceMeters, cosine, keep)
    }

    private fun distanceToChord(
        latitudes: IntArray,
        longitudes: IntArray,
        first: Int,
        last: Int,
        index: Int,
        cosine: Double,
    ): Double {
        val x0 = (longitudes[first] - longitudes[index]) * METERS_PER_MICRO_DEGREE * cosine
        val y0 = (latitudes[first] - latitudes[index]) * METERS_PER_MICRO_DEGREE
        val x1 = (longitudes[last] - longitudes[index]) * METERS_PER_MICRO_DEGREE * cosine
        val y1 = (latitudes[last] - latitudes[index]) * METERS_PER_MICRO_DEGREE
        val chord = Math.hypot(x1 - x0, y1 - y0)
        if (chord < 1e-9) return Math.hypot(x0, y0)
        // Deux fois l'aire du triangle, divisée par la base : la hauteur.
        return abs(x0 * y1 - y0 * x1) / chord
    }

    /** Aire approchée d'un contour, en mètres carrés. */
    internal fun area(latitudes: IntArray, longitudes: IntArray): Double {
        if (latitudes.size < 3) return 0.0
        val cosine = cos(Math.toRadians(latitudes[0] / 1_000_000.0))
        var sum = 0.0
        for (index in latitudes.indices) {
            val next = (index + 1) % latitudes.size
            val x0 = longitudes[index] * METERS_PER_MICRO_DEGREE * cosine
            val y0 = latitudes[index] * METERS_PER_MICRO_DEGREE
            val x1 = longitudes[next] * METERS_PER_MICRO_DEGREE * cosine
            val y1 = latitudes[next] * METERS_PER_MICRO_DEGREE
            sum += x0 * y1 - x1 * y0
        }
        return abs(sum) / 2
    }

    /** Un micro-degré de latitude vaut onze centimètres. */
    private const val METERS_PER_MICRO_DEGREE = 0.11054

    /** Écart en deçà duquel un point de contour n'apprend rien (m). */
    private const val SIMPLIFY_TOLERANCE_METERS = 8.0

    /** Surface en deçà de laquelle une tache ne se distingue plus de rien (m²). */
    private const val MINIMUM_AREA_SQUARE_METERS = 2_000.0

    /**
     * Seuil des cultures et prairies, plus haut que les autres (m²).
     *
     * La campagne est découpée en parcelles par milliers, et chacune pèse dans le fichier
     * alors qu'aucune ne se distingue de sa voisine : elles ont toutes la même teinte, et
     * leurs limites ne se voient pas. Écarter les petites ne laisse donc pas un trou mais le
     * fond de la carte, presque de la même couleur. Un hectare est la limite en deçà de
     * laquelle la question ne se pose plus.
     */
    private const val MINIMUM_FARMLAND_SQUARE_METERS = 10_000.0

    private fun minimumArea(kind: RoadKind): Double =
        if (kind == RoadKind.FARMLAND) MINIMUM_FARMLAND_SQUARE_METERS else MINIMUM_AREA_SQUARE_METERS
}
