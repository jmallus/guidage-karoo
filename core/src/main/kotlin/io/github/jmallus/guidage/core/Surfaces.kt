package io.github.jmallus.guidage.core

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Ce qu'on roule, réduit à ce qui change la façon de rouler.
 *
 * Le détail des dizaines de valeurs du tag `surface` n'apprendrait rien de plus : ce qui
 * compte est de savoir si l'on va quitter le bitume, et pour combien de temps.
 */
enum class SurfaceClass {
    /** Route revêtue, du chemin communal à la départementale. */
    ROAD,

    /** Chemin, sentier, piste : ce qui se roule en gravel. */
    TRAIL,

    /** Voie verte, piste cyclable en site propre. */
    CYCLEWAY,

    /** Aucune voie du fond de carte ne passe assez près pour se prononcer. */
    UNKNOWN,
    ;

    companion object {
        fun of(kind: RoadKind?, surface: RoadSurface): SurfaceClass = when {
            kind == null -> UNKNOWN
            kind == RoadKind.CYCLEWAY -> CYCLEWAY
            kind.isTrail -> TRAIL
            surface == RoadSurface.UNPAVED -> TRAIL
            else -> ROAD
        }
    }
}

/** Une portion d'itinéraire d'un seul tenant. */
data class SurfaceRun(
    /** Distance de son début depuis le départ de l'itinéraire (m). */
    val fromDistance: Double,
    val toDistance: Double,
    val surface: SurfaceClass,
) {
    val length: Double get() = toDistance - fromDistance
}

/**
 * Reconnaît le revêtement de l'itinéraire à venir, en le posant sur le fond hors ligne.
 *
 * C'est la seule des vues qui demande d'apparier deux jeux de données : le tracé, qui vient
 * du Karoo, et les voies, qui viennent de la carte embarquée. L'appariement est fait de
 * proche en proche — on échantillonne le tracé, on cherche pour chaque échantillon la voie
 * la plus proche — et tout le soin est dans ce qui suit : sans regroupement, la traversée
 * d'un carrefour ferait apparaître trois mètres de chemin au milieu d'une départementale.
 */
object Surfaces {

    /** Pas d'échantillonnage du tracé (m). */
    const val SAMPLE_METERS = 40.0

    /** Distance au-delà de laquelle une voie n'est plus celle qu'on suit (m). */
    const val MATCH_METERS = 25.0

    /**
     * Longueur en deçà de laquelle une portion est absorbée par sa voisine (m).
     *
     * Cent cinquante mètres : c'est ce qu'il faut pour que la bascule vaille d'être
     * annoncée. En deçà, ce n'est pas un changement de revêtement, c'est un carrefour, un
     * pont, ou une voie parallèle attrapée au passage.
     */
    const val MIN_RUN_METERS = 150.0

    /**
     * Les portions à venir, de la position courante jusqu'à [lookahead] mètres devant.
     *
     * [pathDistanceOffset] dit à quelle distance de l'itinéraire correspond le premier point
     * du tracé — le Karoo compte l'un et l'autre séparément, et l'écart, de quelques mètres,
     * ne se voit pas à cette échelle mais doit être dit plutôt que supposé nul.
     */
    fun ahead(
        path: List<GeoPoint>,
        segments: List<RoadSegment>,
        distanceAlongRoute: Double,
        lookahead: Double,
        pathDistanceOffset: Double = 0.0,
        sampleMeters: Double = SAMPLE_METERS,
        matchMeters: Double = MATCH_METERS,
        minRunMeters: Double = MIN_RUN_METERS,
    ): List<SurfaceRun> {
        if (path.size < 2 || lookahead <= 0.0) return emptyList()

        val from = distanceAlongRoute - pathDistanceOffset
        val samples = sample(path, from, from + lookahead, sampleMeters)
        if (samples.isEmpty()) return emptyList()

        val grid = Grid(path.first(), matchMeters)
        // La densification est plus fine que la tolérance : sans quoi le point le plus
        // proche pourrait se trouver à une demi-densification du tracé et sortir de la
        // tolérance alors que la voie, elle, passe dessus.
        segments.forEach { grid.add(it, matchMeters / 2.0) }

        val classified = samples.map { sample ->
            val found = grid.nearest(sample.x, sample.y, matchMeters)
            sample.distance + pathDistanceOffset to SurfaceClass.of(found?.kind, found?.surface ?: RoadSurface.UNKNOWN)
        }
        return runs(classified, sampleMeters, minRunMeters)
    }

    /** Un échantillon du tracé : sa position dans le plan local et sa distance. */
    private data class Sample(val x: Double, val y: Double, val distance: Double)

    private fun sample(path: List<GeoPoint>, from: Double, to: Double, step: Double): List<Sample> {
        val origin = path.first()
        val plane = path.map { Geo.project(origin, it) }
        val samples = mutableListOf<Sample>()
        var travelled = 0.0
        var next = maxOf(from, 0.0)
        for (index in 1 until plane.size) {
            val a = plane[index - 1]
            val b = plane[index]
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length <= 0.0) continue
            while (next <= travelled + length && next <= to) {
                val ratio = (next - travelled) / length
                samples += Sample(
                    x = a.x + (b.x - a.x) * ratio,
                    y = a.y + (b.y - a.y) * ratio,
                    distance = next,
                )
                next += step
            }
            travelled += length
            if (next > to) break
        }
        return samples
    }

    /**
     * Regroupe les échantillons en portions, puis absorbe celles qui sont trop courtes.
     *
     * L'absorption se fait vers la portion précédente, et non vers la plus longue voisine :
     * c'est ce qui garantit qu'une portion annoncée ne se déplace pas d'un rafraîchissement
     * à l'autre selon ce qui se trouve derrière elle.
     */
    private fun runs(
        classified: List<Pair<Double, SurfaceClass>>,
        sampleMeters: Double,
        minRunMeters: Double,
    ): List<SurfaceRun> {
        if (classified.isEmpty()) return emptyList()

        val raw = mutableListOf<SurfaceRun>()
        var start = classified.first().first
        var current = classified.first().second
        classified.forEach { (distance, surface) ->
            if (surface != current) {
                raw += SurfaceRun(start, distance, current)
                start = distance
                current = surface
            }
        }
        raw += SurfaceRun(start, classified.last().first + sampleMeters, current)

        val merged = mutableListOf<SurfaceRun>()
        raw.forEach { run ->
            val previous = merged.lastOrNull()
            if (previous != null && run.length < minRunMeters) {
                merged[merged.lastIndex] = previous.copy(toDistance = run.toDistance)
            } else if (previous != null && previous.surface == run.surface) {
                merged[merged.lastIndex] = previous.copy(toDistance = run.toDistance)
            } else {
                merged += run
            }
        }
        // La première portion peut être trop courte sans avoir de voisine derrière elle :
        // elle est alors absorbée par celle qui suit, faute de mieux.
        if (merged.size > 1 && merged.first().length < minRunMeters) {
            val second = merged[1]
            merged[1] = second.copy(fromDistance = merged.first().fromDistance)
            merged.removeAt(0)
        }
        return merged
    }

    /** Ce qu'une voie apprend sur un point du tracé. */
    private data class Found(val kind: RoadKind, val surface: RoadSurface)

    /**
     * Grille de recherche du plus proche voisin.
     *
     * Sans elle, chaque échantillon se compare à tous les points de toutes les voies du
     * corridor : sur trois kilomètres de campagne, c'est un million de distances à chaque
     * rafraîchissement, ce que l'appareil ne tiendrait pas. Les voies sont donc densifiées
     * puis rangées dans des cases de la taille de la tolérance, et un échantillon ne
     * regarde que les neuf cases autour de lui.
     */
    private class Grid(private val origin: GeoPoint, matchMeters: Double) {

        private val cellSize = matchMeters.coerceAtLeast(1.0)
        private val cells = HashMap<Long, MutableList<Triple<Double, Double, Found>>>()

        fun add(segment: RoadSegment, densifyMeters: Double) {
            // Les surfaces — bois, eau, bâti — sont des contours, pas des voies : les
            // apparier au tracé ferait rouler le coureur sur la lisière d'une forêt.
            if (segment.kind.isArea || segment.kind == RoadKind.STREAM) return
            val found = Found(segment.kind, segment.surface)

            var previous: PlanePoint? = null
            for (index in 0 until segment.size) {
                val point = Geo.project(
                    origin,
                    GeoPoint(
                        lat = segment.latitudes[index] / MICRO_DEGREES,
                        lng = segment.longitudes[index] / MICRO_DEGREES,
                    ),
                )
                previous?.let { start ->
                    // On densifie : deux nœuds d'OSM peuvent être distants de deux cents
                    // mètres sur une ligne droite, et le point le plus proche du tracé se
                    // trouve alors entre les deux, là où il n'y a rien à trouver.
                    val length = hypot(point.x - start.x, point.y - start.y)
                    var walked = densifyMeters
                    while (walked < length) {
                        val ratio = walked / length
                        put(start.x + (point.x - start.x) * ratio, start.y + (point.y - start.y) * ratio, found)
                        walked += densifyMeters
                    }
                }
                put(point.x, point.y, found)
                previous = point
            }
        }

        fun nearest(x: Double, y: Double, within: Double): Found? {
            var best: Found? = null
            var bestDistance = within
            val cellX = floor(x / cellSize).toInt()
            val cellY = floor(y / cellSize).toInt()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    cells[key(cellX + dx, cellY + dy)]?.forEach { (px, py, found) ->
                        val distance = hypot(px - x, py - y)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = found
                        }
                    }
                }
            }
            return best
        }

        private fun put(x: Double, y: Double, found: Found) {
            val cell = key(floor(x / cellSize).toInt(), floor(y / cellSize).toInt())
            cells.getOrPut(cell) { mutableListOf() } += Triple(x, y, found)
        }

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)

        private companion object {
            const val MICRO_DEGREES = 1_000_000.0
        }
    }
}
