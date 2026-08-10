package io.github.jmallus.guidage.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VarintTest {

    @Test
    fun `zigzag maps small negatives onto small positives`() {
        assertEquals(0, Varint.zigZag(0))
        assertEquals(1, Varint.zigZag(-1))
        assertEquals(2, Varint.zigZag(1))
        assertEquals(3, Varint.zigZag(-2))
    }

    @Test
    fun `every representative integer survives a round trip`() {
        val values = listOf(0, 1, -1, 63, -64, 127, -128, 12_345, -12_345, Int.MAX_VALUE, Int.MIN_VALUE)
        val out = mutableListOf<Byte>()
        values.forEach { Varint.writeSigned(out, it) }

        val cursor = Varint.Cursor()
        val bytes = out.toByteArray()
        values.forEach { assertEquals(it, Varint.readSigned(bytes, cursor)) }
        assertEquals(bytes.size, cursor.offset)
    }

    @Test
    fun `small steps cost a single byte`() {
        val out = mutableListOf<Byte>()
        repeat(10) { Varint.writeSigned(out, 3) }
        assertEquals(10, out.size)
    }
}

class RoadMapTest {

    /** Une voie droite de [points] points, partant de (lat, lng) en micro-degrés. */
    private fun segment(
        kind: RoadKind,
        latitude: Int,
        longitude: Int,
        points: Int = 4,
        step: Int = 200,
        surface: RoadSurface = RoadSurface.UNKNOWN,
    ) = RoadSegment(
        kind = kind,
        surface = surface,
        latitudes = IntArray(points) { latitude + it * step },
        longitudes = IntArray(points) { longitude + it * step },
    )

    private fun readerOf(vararg segments: RoadSegment): RoadMapReader {
        val writer = RoadMapWriter()
        segments.forEach { writer.add(it) }
        val reader = RoadMapReader.open(ByteArraySource(writer.build()))
        assertNotNull("le fichier doit être lisible", reader)
        return reader!!
    }

    @Test
    fun `a segment survives writing and reading unchanged`() {
        val original = segment(RoadKind.TRACK, 49_110_000, 840_000, surface = RoadSurface.UNPAVED)
        val reader = readerOf(original)

        val found = reader.segmentsIn(MapBounds(49_100_000, 830_000, 49_130_000, 850_000))
        assertEquals(1, found.size)
        assertEquals(original, found.first())
    }

    @Test
    fun `class and surface come back as they went in`() {
        val trail = segment(RoadKind.PATH, 49_110_000, 840_000, surface = RoadSurface.UNPAVED)
        val road = segment(RoadKind.PRIMARY, 49_112_000, 840_000, surface = RoadSurface.PAVED)
        val reader = readerOf(trail, road)

        val found = reader.segmentsIn(MapBounds(49_100_000, 830_000, 49_130_000, 850_000))
        assertEquals(
            setOf(RoadKind.PATH to RoadSurface.UNPAVED, RoadKind.PRIMARY to RoadSurface.PAVED),
            found.map { it.kind to it.surface }.toSet(),
        )
    }

    @Test
    fun `a window only returns what it touches`() {
        val near = segment(RoadKind.RESIDENTIAL, 49_110_000, 840_000)
        val far = segment(RoadKind.RESIDENTIAL, 49_500_000, 1_400_000)
        val reader = readerOf(near, far)

        val found = reader.segmentsIn(MapBounds(49_105_000, 835_000, 49_115_000, 845_000))
        assertEquals(listOf(near), found)
    }

    @Test
    fun `a segment straddling two cells is returned once`() {
        // La cellule fait 20 000 micro-degrés : ce tronçon en traverse plusieurs.
        val straddling = RoadSegment(
            kind = RoadKind.SECONDARY,
            surface = RoadSurface.PAVED,
            latitudes = intArrayOf(49_100_000, 49_125_000),
            longitudes = intArrayOf(840_000, 865_000),
        )
        val reader = readerOf(straddling)

        val found = reader.segmentsIn(MapBounds(49_090_000, 830_000, 49_140_000, 880_000))
        assertEquals(1, found.size)
        assertEquals(straddling, found.first())
    }

    @Test
    fun `a long way is chopped without leaving a gap`() {
        val long = segment(RoadKind.TRACK, 49_100_000, 840_000, points = 100, step = 50)
        val writer = RoadMapWriter()
        writer.add(long)
        assertTrue("un tronçon de 100 points doit être découpé", writer.segmentCount > 1)

        val reader = RoadMapReader.open(ByteArraySource(writer.build()))!!
        val pieces = reader
            .segmentsIn(MapBounds(49_000_000, 800_000, 49_200_000, 900_000))
            .sortedBy { it.latitudes.first() }

        // Bout à bout, les morceaux redonnent la voie d'origine.
        val rebuilt = mutableListOf<Int>()
        pieces.forEachIndexed { index, piece ->
            val from = if (index == 0) 0 else 1
            (from until piece.size).forEach { rebuilt.add(piece.latitudes[it]) }
        }
        assertEquals(long.latitudes.toList(), rebuilt)
    }

    @Test
    fun `an empty map produces no file`() {
        assertEquals(0, RoadMapWriter().build().size)
    }

    @Test
    fun `anything that is not a road map is refused`() {
        assertNull(RoadMapReader.open(ByteArraySource(ByteArray(0))))
        assertNull(RoadMapReader.open(ByteArraySource(ByteArray(64) { 0x7F })))
    }

    @Test
    fun `deltas keep the file small`() {
        // Cent points espacés de onze mètres : deux octets par point suffisent largement.
        val writer = RoadMapWriter()
        writer.add(segment(RoadKind.TRACK, 49_100_000, 840_000, points = 100, step = 100))
        val bytes = writer.build()
        assertTrue("fichier de ${bytes.size} octets, attendu sous 700", bytes.size < 700)
    }
}

class RoadKindTest {

    @Test
    fun `highway tags map onto the kinds we keep`() {
        assertEquals(RoadKind.TRACK, RoadKind.fromHighwayTag("track"))
        assertEquals(RoadKind.PATH, RoadKind.fromHighwayTag("path"))
        assertEquals(RoadKind.PRIMARY, RoadKind.fromHighwayTag("primary_link"))
        assertNull(RoadKind.fromHighwayTag("proposed"))
        assertNull(RoadKind.fromHighwayTag("bus_guideway"))
    }

    @Test
    fun `tracks and paths are what gravel and mountain biking care about`() {
        assertTrue(RoadKind.TRACK.isTrail)
        assertTrue(RoadKind.PATH.isTrail)
        assertTrue(RoadKind.BRIDLEWAY.isTrail)
        assertTrue(!RoadKind.RESIDENTIAL.isTrail)
        assertTrue(!RoadKind.PRIMARY.isTrail)
    }

    @Test
    fun `surfaces sort into paved and unpaved`() {
        assertEquals(RoadSurface.UNPAVED, RoadSurface.fromSurfaceTag("gravel"))
        assertEquals(RoadSurface.UNPAVED, RoadSurface.fromSurfaceTag("compacted"))
        assertEquals(RoadSurface.PAVED, RoadSurface.fromSurfaceTag("asphalt"))
        assertEquals(RoadSurface.UNKNOWN, RoadSurface.fromSurfaceTag(null))
        assertEquals(RoadSurface.UNKNOWN, RoadSurface.fromSurfaceTag("quelque chose d'inconnu"))
    }

    @Test
    fun `every code survives the header packing`() {
        for (kind in RoadKind.entries) {
            for (surface in RoadSurface.entries) {
                val header = RoadMapFormat.packHeader(kind, surface)
                assertEquals(kind, RoadMapFormat.unpackKind(header))
                assertEquals(surface, RoadMapFormat.unpackSurface(header))
            }
        }
    }
}
