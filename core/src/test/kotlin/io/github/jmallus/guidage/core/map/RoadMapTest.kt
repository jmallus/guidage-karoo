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

    /** Un contour rectangulaire, en micro-degrés, coin sud-ouest en (lat, lng). */
    private fun area(
        kind: RoadKind,
        latitude: Int,
        longitude: Int,
        heightMicroDegrees: Int = 1_000,
        widthMicroDegrees: Int = 1_000,
    ) = RoadSegment(
        kind = kind,
        surface = RoadSurface.UNKNOWN,
        latitudes = intArrayOf(latitude, latitude, latitude + heightMicroDegrees, latitude + heightMicroDegrees),
        longitudes = intArrayOf(longitude, longitude + widthMicroDegrees, longitude + widthMicroDegrees, longitude),
    )

    @Test
    fun `une surface qui tient dans une cellule revient intacte`() {
        val pond = area(RoadKind.WATER, 49_110_000, 840_000)
        val reader = readerOf(pond)

        val found = reader.segmentsIn(MapBounds(49_100_000, 830_000, 49_130_000, 850_000))
        assertEquals(1, found.size)
        assertEquals(pond, found.first())
    }

    @Test
    fun `une surface n est jamais decoupee en morceaux de polyligne`() {
        // Cinquante points de contour : une ligne serait tronçonnée, un bois ne doit pas
        // l'être — ses morceaux ne se rempliraient plus.
        val wood = RoadSegment(
            kind = RoadKind.FOREST,
            surface = RoadSurface.UNKNOWN,
            latitudes = IntArray(50) { 49_110_000 + (if (it < 25) it else 49 - it) * 40 },
            longitudes = IntArray(50) { 840_000 + it * 40 },
        )
        val writer = RoadMapWriter()
        writer.add(wood)

        assertEquals(1, writer.segmentCount)
    }

    @Test
    fun `une surface a cheval sur deux cellules est limitee a chacune`() {
        // La cellule fait 20 000 micro-degrés : ce bois occupe deux rangées.
        val wood = area(RoadKind.FOREST, 49_100_000, 840_000, heightMicroDegrees = 30_000)
        val reader = readerOf(wood)

        val found = reader.segmentsIn(MapBounds(49_090_000, 830_000, 49_140_000, 850_000))
        assertEquals(2, found.size)
        // Aucun morceau ne déborde de sa cellule, et les deux se rejoignent à la limite.
        val south = found.first { it.latitudes.min() == 49_100_000 }
        val north = found.first { it.latitudes.min() == 49_120_000 }
        assertEquals(49_120_000, south.latitudes.max())
        assertEquals(49_130_000, north.latitudes.max())
        assertTrue("le bois reste un bois", found.all { it.kind == RoadKind.FOREST })
    }

    @Test
    fun `un fichier de la version precedente reste lisible`() {
        val writer = RoadMapWriter()
        writer.add(segment(RoadKind.TRACK, 49_110_000, 840_000))
        val bytes = writer.build()
        bytes[6] = RoadMapFormat.OLDEST_VERSION.toByte()

        val reader = RoadMapReader.open(ByteArraySource(bytes))
        assertNotNull("les anciens fichiers doivent rester lisibles", reader)
        assertEquals(1, reader!!.segmentsIn(MapBounds(49_100_000, 830_000, 49_130_000, 850_000)).size)
    }

    @Test
    fun `un fichier d une version future est refuse`() {
        val writer = RoadMapWriter()
        writer.add(segment(RoadKind.TRACK, 49_110_000, 840_000))
        val bytes = writer.build()
        bytes[6] = (RoadMapFormat.VERSION + 1).toByte()

        assertNull(RoadMapReader.open(ByteArraySource(bytes)))
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
    fun `les tags de surface donnent l eau, le bois et le bati`() {
        assertEquals(RoadKind.WATER, RoadKind.fromAreaTags(natural = "water", landuse = null, waterway = null))
        assertEquals(RoadKind.WATER, RoadKind.fromAreaTags(natural = null, landuse = "reservoir", waterway = null))
        assertEquals(RoadKind.WATER, RoadKind.fromAreaTags(natural = null, landuse = null, waterway = "riverbank"))
        assertEquals(RoadKind.FOREST, RoadKind.fromAreaTags(natural = "wood", landuse = null, waterway = null))
        assertEquals(RoadKind.FOREST, RoadKind.fromAreaTags(natural = null, landuse = "forest", waterway = null))
        assertEquals(RoadKind.BUILT_UP, RoadKind.fromAreaTags(natural = null, landuse = "residential", waterway = null))
        // Un champ ou un stade couvrirait l'écran sans rien apprendre.
        assertNull(RoadKind.fromAreaTags(natural = null, landuse = "farmland", waterway = null))
        assertNull(RoadKind.fromAreaTags(natural = null, landuse = null, waterway = null))
    }

    @Test
    fun `ce qui se remplit se distingue de ce qui se trace`() {
        assertTrue(RoadKind.WATER.isArea)
        assertTrue(RoadKind.FOREST.isArea)
        assertTrue(RoadKind.BUILT_UP.isArea)
        // Un ruisseau est une ligne, si mince qu'il n'a pas de rive.
        assertTrue(!RoadKind.STREAM.isArea)
        assertEquals(RoadKind.STREAM, RoadKind.fromWaterwayTag("stream"))
        assertEquals(RoadKind.STREAM, RoadKind.fromWaterwayTag("river"))
        assertNull(RoadKind.fromWaterwayTag("drain"))
        assertTrue(!RoadKind.TRACK.isArea)
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
