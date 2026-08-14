package io.github.jmallus.guidage.core.map

import kotlin.math.floor

/**
 * Lit un fond de carte, une fenêtre à la fois.
 *
 * Seuls l'entête et l'index sont gardés en mémoire ; le corps reste sur le disque et
 * n'est lu que par cellules. Sur une région entière, l'index tient en quelques centaines
 * de kilo-octets là où le fichier complet en pèse des dizaines de milliers.
 */
class RoadMapReader private constructor(
    private val source: ByteSource,
    val bounds: MapBounds,
    private val cellSize: Int,
    private val columns: Int,
    private val rows: Int,
    private val offsets: IntArray,
    private val bodyStart: Long,
) {

    /** Tronçons dont le rectangle englobant rencontre [window]. */
    fun segmentsIn(window: MapBounds): List<RoadSegment> {
        if (!bounds.intersects(window)) return emptyList()

        val firstColumn = cellIndex(window.minLongitude, bounds.minLongitude, columns)
        val lastColumn = cellIndex(window.maxLongitude, bounds.minLongitude, columns)
        val firstRow = cellIndex(window.minLatitude, bounds.minLatitude, rows)
        val lastRow = cellIndex(window.maxLatitude, bounds.minLatitude, rows)

        // Un tronçon rangé dans plusieurs cellules serait autrement rendu plusieurs fois.
        val seen = HashSet<Int>()
        val found = mutableListOf<RoadSegment>()
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                readCell(row, column, window, seen, found)
            }
        }
        return found
    }

    private fun readCell(
        row: Int,
        column: Int,
        window: MapBounds,
        seen: MutableSet<Int>,
        found: MutableList<RoadSegment>,
    ) {
        val index = row * columns + column
        val start = offsets[index]
        val end = offsets[index + 1]
        if (end <= start) return

        val bytes = source.read(bodyStart + start, end - start)
        val cursor = Varint.Cursor()
        val originLatitude = bounds.minLatitude + row * cellSize
        val originLongitude = bounds.minLongitude + column * cellSize

        while (cursor.offset < bytes.size) {
            val header = bytes[cursor.offset++].toInt() and 0xFF
            val kind = RoadMapFormat.unpackKind(header)
            val surface = RoadMapFormat.unpackSurface(header)
            val identifier = Varint.read(bytes, cursor)
            val count = Varint.read(bytes, cursor)

            val latitudes = IntArray(count)
            val longitudes = IntArray(count)
            var latitude = originLatitude
            var longitude = originLongitude
            for (point in 0 until count) {
                latitude += Varint.readSigned(bytes, cursor)
                longitude += Varint.readSigned(bytes, cursor)
                latitudes[point] = latitude
                longitudes[point] = longitude
            }
            if (kind == null || count < 2) continue

            // Un tronçon à cheval sur plusieurs cellules y figure autant de fois.
            if (!seen.add(identifier)) continue
            if (!MapBounds.of(latitudes, longitudes).intersects(window)) continue
            found.add(RoadSegment(kind, surface, latitudes, longitudes))
        }
    }

    private fun cellIndex(value: Int, min: Int, count: Int): Int =
        floor((value - min).toDouble() / cellSize).toInt().coerceIn(0, count - 1)

    companion object {
        /** Ouvre une source, ou renvoie null si ce n'est pas un fond de carte lisible. */
        fun open(source: ByteSource): RoadMapReader? {
            if (source.size < RoadMapFormat.HEADER_SIZE) return null
            val header = source.read(0, RoadMapFormat.HEADER_SIZE)
            if (!header.copyOfRange(0, RoadMapFormat.MAGIC.size).contentEquals(RoadMapFormat.MAGIC)) return null
            val version = header[6].toInt()
            if (version < RoadMapFormat.OLDEST_VERSION || version > RoadMapFormat.VERSION) return null

            var at = 8
            val minLatitude = readInt(header, at); at += 4
            val minLongitude = readInt(header, at); at += 4
            val maxLatitude = readInt(header, at); at += 4
            val maxLongitude = readInt(header, at); at += 4
            val cellSize = readInt(header, at); at += 4
            val columns = readInt(header, at); at += 4
            val rows = readInt(header, at)
            if (cellSize <= 0 || columns <= 0 || rows <= 0) return null

            val indexCount = columns * rows + 1
            val indexSize = indexCount.toLong() * 4
            if (source.size < RoadMapFormat.HEADER_SIZE + indexSize) return null
            val indexBytes = source.read(RoadMapFormat.HEADER_SIZE.toLong(), indexSize.toInt())
            val offsets = IntArray(indexCount) { readInt(indexBytes, it * 4) }

            return RoadMapReader(
                source = source,
                bounds = MapBounds(minLatitude, minLongitude, maxLatitude, maxLongitude),
                cellSize = cellSize,
                columns = columns,
                rows = rows,
                offsets = offsets,
                bodyStart = RoadMapFormat.HEADER_SIZE + indexSize,
            )
        }

        private fun readInt(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
