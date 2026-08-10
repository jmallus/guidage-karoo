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
