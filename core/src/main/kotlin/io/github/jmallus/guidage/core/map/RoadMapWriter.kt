package io.github.jmallus.guidage.core.map

import kotlin.math.floor

/**
 * Construit un fichier de fond de carte à partir de tronçons.
 *
 * Tourne côté ordinateur — dans le CI, à partir d'un extrait Geofabrik — jamais sur
 * l'appareil.
 */
class RoadMapWriter(
    private val cellSize: Int = RoadMapFormat.DEFAULT_CELL_SIZE,
) {
    private val segments = mutableListOf<RoadSegment>()

    /**
     * Ajoute un objet, découpé s'il est trop long pour être rangé proprement.
     *
     * Les surfaces échappent au découpage : elles sont limitées cellule par cellule au
     * moment de l'écriture, un contour scindé en morceaux ne se remplissant plus.
     */
    fun add(segment: RoadSegment) {
        if (segment.kind.isArea) segments.add(segment) else chop(segment).forEach { segments.add(it) }
    }

    fun addAll(segments: Iterable<RoadSegment>) = segments.forEach { add(it) }

    val segmentCount: Int get() = segments.size

    /**
     * Découpe une polyligne en morceaux d'au plus [RoadMapFormat.MAX_SEGMENT_POINTS] points.
     *
     * Les morceaux partagent leur point de jonction, sans quoi le tracé présenterait un
     * trou à chaque coupure.
     */
    private fun chop(segment: RoadSegment): List<RoadSegment> {
        val limit = RoadMapFormat.MAX_SEGMENT_POINTS
        if (segment.size <= limit) return listOf(segment)

        val pieces = mutableListOf<RoadSegment>()
        var start = 0
        while (start < segment.size - 1) {
            val end = minOf(start + limit, segment.size)
            pieces.add(
                RoadSegment(
                    kind = segment.kind,
                    surface = segment.surface,
                    latitudes = segment.latitudes.copyOfRange(start, end),
                    longitudes = segment.longitudes.copyOfRange(start, end),
                ),
            )
            start = end - 1
        }
        return pieces
    }

    /** Écrit le fichier complet. Renvoie un tableau vide s'il n'y a rien à écrire. */
    fun build(): ByteArray {
        if (segments.isEmpty()) return ByteArray(0)

        val minLatitude = segments.minOf { it.latitudes.min() }
        val minLongitude = segments.minOf { it.longitudes.min() }
        val maxLatitude = segments.maxOf { it.latitudes.max() }
        val maxLongitude = segments.maxOf { it.longitudes.max() }

        val columns = cellCount(minLongitude, maxLongitude)
        val rows = cellCount(minLatitude, maxLatitude)

        // Chaque objet est rangé dans toutes les cellules que touche son rectangle. Les
        // lignes y entrent entières — le lecteur les dédoublonne par leur identifiant — et
        // les surfaces y entrent limitées à la cellule, sinon un bois de dix kilomètres
        // serait recopié dans chacune des cent cellules qu'il traverse.
        val buckets = Array(columns * rows) { mutableListOf<Stored>() }
        var nextIdentifier = 0
        val identifiers = HashMap<RoadSegment, Int>(segments.size)
        for (segment in segments) {
            val bounds = MapBounds.of(segment.latitudes, segment.longitudes)
            val firstColumn = cellIndex(bounds.minLongitude, minLongitude, columns)
            val lastColumn = cellIndex(bounds.maxLongitude, minLongitude, columns)
            val firstRow = cellIndex(bounds.minLatitude, minLatitude, rows)
            val lastRow = cellIndex(bounds.maxLatitude, minLatitude, rows)
            val identifier = if (segment.kind.isArea) {
                -1
            } else {
                identifiers.getOrPut(segment) { nextIdentifier++ }
            }
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) {
                    val cell = buckets[row * columns + column]
                    if (!segment.kind.isArea) {
                        cell.add(Stored(segment, identifier))
                        continue
                    }
                    val clipped = ClipPolygon.clip(
                        segment.latitudes,
                        segment.longitudes,
                        cellBounds(minLatitude, minLongitude, row, column),
                    ) ?: continue
                    cell.add(
                        Stored(
                            RoadSegment(segment.kind, segment.surface, clipped.first, clipped.second),
                            nextIdentifier++,
                        ),
                    )
                }
            }
        }

        val body = mutableListOf<Byte>()
        val offsets = IntArray(columns * rows + 1)
        buckets.forEachIndexed { index, cell ->
            offsets[index] = body.size
            val row = index / columns
            val column = index % columns
            val originLatitude = minLatitude + row * cellSize
            val originLongitude = minLongitude + column * cellSize
            cell.forEach {
                writeSegment(body, it.segment, it.identifier, originLatitude, originLongitude)
            }
        }
        offsets[offsets.size - 1] = body.size

        val out = ByteArray(RoadMapFormat.HEADER_SIZE + offsets.size * 4 + body.size)
        var at = 0
        RoadMapFormat.MAGIC.copyInto(out, at); at += RoadMapFormat.MAGIC.size
        out[at++] = RoadMapFormat.VERSION.toByte()
        out[at++] = 0
        at = putInt(out, at, minLatitude)
        at = putInt(out, at, minLongitude)
        at = putInt(out, at, maxLatitude)
        at = putInt(out, at, maxLongitude)
        at = putInt(out, at, cellSize)
        at = putInt(out, at, columns)
        at = putInt(out, at, rows)
        offsets.forEach { at = putInt(out, at, it) }
        body.forEachIndexed { index, byte -> out[at + index] = byte }
        return out
    }

    private fun writeSegment(
        out: MutableList<Byte>,
        segment: RoadSegment,
        identifier: Int,
        originLatitude: Int,
        originLongitude: Int,
    ) {
        out.add(RoadMapFormat.packHeader(segment.kind, segment.surface).toByte())
        Varint.write(out, identifier)
        Varint.write(out, segment.size)
        var previousLatitude = originLatitude
        var previousLongitude = originLongitude
        for (index in 0 until segment.size) {
            Varint.writeSigned(out, segment.latitudes[index] - previousLatitude)
            Varint.writeSigned(out, segment.longitudes[index] - previousLongitude)
            previousLatitude = segment.latitudes[index]
            previousLongitude = segment.longitudes[index]
        }
    }

    /** Un objet rangé dans une cellule, avec l'identifiant qui permet de le dédoublonner. */
    private data class Stored(val segment: RoadSegment, val identifier: Int)

    private fun cellBounds(minLatitude: Int, minLongitude: Int, row: Int, column: Int) = MapBounds(
        minLatitude = minLatitude + row * cellSize,
        minLongitude = minLongitude + column * cellSize,
        maxLatitude = minLatitude + (row + 1) * cellSize,
        maxLongitude = minLongitude + (column + 1) * cellSize,
    )

    private fun cellCount(min: Int, max: Int): Int =
        floor((max - min).toDouble() / cellSize).toInt() + 1

    private fun cellIndex(value: Int, min: Int, count: Int): Int =
        floor((value - min).toDouble() / cellSize).toInt().coerceIn(0, count - 1)

    private fun putInt(out: ByteArray, at: Int, value: Int): Int {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
        return at + 4
    }
}
