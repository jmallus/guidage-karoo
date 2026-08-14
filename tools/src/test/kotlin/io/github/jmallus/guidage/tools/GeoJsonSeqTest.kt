package io.github.jmallus.guidage.tools

import io.github.jmallus.guidage.core.map.ByteArraySource
import io.github.jmallus.guidage.core.map.MapBounds
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadMapReader
import io.github.jmallus.guidage.core.map.RoadMapWriter
import io.github.jmallus.guidage.core.map.RoadSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonSeqTest {

    private fun feature(properties: String, coordinates: String) =
        """{"type":"Feature","properties":$properties,"geometry":{"type":"LineString","coordinates":$coordinates}}"""

    @Test
    fun `a track becomes a segment with its surface`() {
        val segment = GeoJsonSeq.toSegment(
            feature(
                """{"highway":"track","surface":"gravel"}""",
                """[[0.840000,49.110000],[0.841000,49.111000]]""",
            ),
        )
        assertNotNull(segment)
        assertEquals(RoadKind.TRACK, segment!!.kind)
        assertEquals(RoadSurface.UNPAVED, segment.surface)
        assertEquals(2, segment.size)
        // GeoJSON écrit la longitude en premier : c'est le piège de ce format.
        assertEquals(49_110_000, segment.latitudes[0])
        assertEquals(840_000, segment.longitudes[0])
    }

    @Test
    fun `paths and bridleways are kept - that is the point of the whole thing`() {
        listOf("path", "bridleway", "cycleway", "track").forEach { tag ->
            val segment = GeoJsonSeq.toSegment(
                feature("""{"highway":"$tag"}""", """[[0.84,49.11],[0.85,49.12]]"""),
            )
            assertNotNull("$tag doit être retenu", segment)
            assertEquals("$tag doit compter comme chemin", true, segment!!.kind.isTrail)
        }
    }

    @Test
    fun `what is not a road is dropped`() {
        assertNull(GeoJsonSeq.toSegment(""))
        assertNull(GeoJsonSeq.toSegment("   "))
        assertNull(GeoJsonSeq.toSegment("pas du json"))
        // Une clôture n'est pas une voie.
        assertNull(GeoJsonSeq.toSegment(feature("""{"barrier":"fence"}""", """[[0.84,49.11],[0.85,49.12]]""")))
        // Une valeur de highway qu'on ne garde pas.
        assertNull(GeoJsonSeq.toSegment(feature("""{"highway":"proposed"}""", """[[0.84,49.11],[0.85,49.12]]""")))
    }

    @Test
    fun `a point geometry is not a way`() {
        assertNull(
            GeoJsonSeq.toSegment(
                """{"type":"Feature","properties":{"highway":"track"},"geometry":{"type":"Point","coordinates":[0.84,49.11]}}""",
            ),
        )
    }

    @Test
    fun `points that round onto each other are collapsed`() {
        // Trois positions à moins d'un micro-degré les unes des autres : un seul point utile.
        val segment = GeoJsonSeq.toSegment(
            feature(
                """{"highway":"residential"}""",
                """[[0.8400001,49.1100001],[0.8400002,49.1100002],[0.8410000,49.1110000]]""",
            ),
        )
        assertNotNull(segment)
        assertEquals(2, segment!!.size)
    }

    @Test
    fun `a way collapsing to a single point is dropped`() {
        assertNull(
            GeoJsonSeq.toSegment(
                feature("""{"highway":"track"}""", """[[0.8400001,49.1100001],[0.8400002,49.1100002]]"""),
            ),
        )
    }

    private fun polygon(properties: String, rings: String) =
        """{"type":"Feature","properties":$properties,"geometry":{"type":"Polygon","coordinates":$rings}}"""

    /** Un anneau carré de [side] degrés de côté, refermé sur lui-même. */
    private fun ring(longitude: Double, latitude: Double, side: Double): String {
        val corners = listOf(
            longitude to latitude,
            longitude + side to latitude,
            longitude + side to latitude + side,
            longitude to latitude + side,
            longitude to latitude,
        )
        return corners.joinToString(",", prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
    }

    @Test
    fun `un plan d eau devient une surface`() {
        val segment = GeoJsonSeq.toSegment(
            polygon("""{"natural":"water"}""", "[${ring(0.840, 49.110, 0.001)}]"),
        )
        assertNotNull(segment)
        assertEquals(RoadKind.WATER, segment!!.kind)
        // Le point de fermeture ne sert à rien : un contour se referme tout seul.
        assertEquals(4, segment.size)
    }

    @Test
    fun `un bois en plusieurs morceaux en donne autant de surfaces`() {
        val rings = "[[${ring(0.840, 49.110, 0.001)}],[${ring(0.850, 49.120, 0.001)}]]"
        val segments = GeoJsonSeq.toSegments(
            """{"type":"Feature","properties":{"landuse":"forest"},"geometry":{"type":"MultiPolygon","coordinates":$rings}}""",
        )
        assertEquals(2, segments.size)
        assertEquals(listOf(RoadKind.FOREST, RoadKind.FOREST), segments.map { it.kind })
    }

    @Test
    fun `une mare ne se distinguerait pas d un defaut de l ecran`() {
        // Trente mètres sur vingt : sous le seuil, donc écartée.
        assertNull(GeoJsonSeq.toSegment(polygon("""{"natural":"water"}""", "[${ring(0.840, 49.110, 0.0003)}]")))
    }

    @Test
    fun `un champ n est pas une surface qu on garde`() {
        assertNull(GeoJsonSeq.toSegment(polygon("""{"landuse":"farmland"}""", "[${ring(0.840, 49.110, 0.002)}]")))
    }

    @Test
    fun `une riviere devient une ligne`() {
        val segment = GeoJsonSeq.toSegment(
            feature("""{"waterway":"river"}""", """[[0.840,49.110],[0.842,49.112]]"""),
        )
        assertNotNull(segment)
        assertEquals(RoadKind.STREAM, segment!!.kind)
    }

    @Test
    fun `la simplification ne garde que ce qui se voit`() {
        // Onze points alignés : les neuf du milieu n'apprennent rien.
        val latitudes = IntArray(11) { it * 100 }
        val straight = GeoJsonSeq.simplify(latitudes, IntArray(11), 8.0)
        assertEquals(2, straight.first.size)

        // Un décrochement de deux cents mètres, lui, se voit : le sommet est gardé.
        val bent = GeoJsonSeq.simplify(latitudes, IntArray(11) { if (it == 5) 2_000 else 0 }, 8.0)
        assertTrue("le sommet doit survivre", bent.second.contains(2_000))

        // Le même décrochement de onze centimètres ne se voit pas.
        val flat = GeoJsonSeq.simplify(latitudes, IntArray(11) { if (it == 5) 1 else 0 }, 8.0)
        assertEquals(2, flat.first.size)
    }

    @Test
    fun `un contour simplifie garde sa surface`() {
        // Un carré de cent dix mètres de côté, avec un point superflu au milieu de chaque côté.
        val segment = GeoJsonSeq.toSegment(
            polygon(
                """{"natural":"wood"}""",
                """[[[0.840000,49.110000],[0.840500,49.110000],[0.841000,49.110000],
                    [0.841000,49.110500],[0.841000,49.111000],[0.840500,49.111000],
                    [0.840000,49.111000],[0.840000,49.110500],[0.840000,49.110000]]]""",
            ),
        )
        assertNotNull(segment)
        assertEquals(4, segment!!.size)
    }

    @Test
    fun `a stream of features round-trips through the file format`() {
        val lines = listOf(
            feature("""{"highway":"track","surface":"gravel"}""", """[[0.840,49.110],[0.842,49.112]]"""),
            feature("""{"highway":"primary","surface":"asphalt"}""", """[[0.850,49.120],[0.852,49.122]]"""),
            feature("""{"barrier":"fence"}""", """[[0.860,49.130],[0.862,49.132]]"""),
        )

        val writer = RoadMapWriter()
        lines.mapNotNull(GeoJsonSeq::toSegment).forEach(writer::add)

        val reader = RoadMapReader.open(ByteArraySource(writer.build()))!!
        val found = reader.segmentsIn(MapBounds(49_000_000, 800_000, 49_200_000, 900_000))

        assertEquals(2, found.size)
        assertEquals(
            setOf(RoadKind.TRACK to RoadSurface.UNPAVED, RoadKind.PRIMARY to RoadSurface.PAVED),
            found.map { it.kind to it.surface }.toSet(),
        )
    }
}
