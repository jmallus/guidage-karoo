package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZoomTest {

    @Test
    fun `les trois portees se suivent en boucle`() {
        assertEquals(MapZoom.MIDDLE, MapZoom.NEAR.next())
        assertEquals(MapZoom.FAR, MapZoom.MIDDLE.next())
        assertEquals(MapZoom.NEAR, MapZoom.FAR.next())
    }

    @Test
    fun `la portee la plus courte est celle de depart`() {
        assertEquals(MapZoom.NEAR, MapZoom.fromOrdinal(0))
        assertEquals(200.0, MapZoom.NEAR.rangeMeters, 1e-9)
        assertEquals(500.0, MapZoom.MIDDLE.rangeMeters, 1e-9)
        assertEquals(1_000.0, MapZoom.FAR.rangeMeters, 1e-9)
        // Un réglage enregistré par une version qui en aurait davantage ne doit pas planter.
        assertEquals(MapZoom.NEAR, MapZoom.fromOrdinal(7))
        assertEquals(MapZoom.NEAR, MapZoom.fromOrdinal(-1))
    }

    @Test
    fun `les portees vont croissant`() {
        val portees = MapZoom.entries.map { it.rangeMeters }
        assertEquals("les crans ne sont pas rangés du plus court au plus long", portees.sorted(), portees)
        assertTrue("deux crans partagent une portée", portees.toSet().size == portees.size)
    }
}
