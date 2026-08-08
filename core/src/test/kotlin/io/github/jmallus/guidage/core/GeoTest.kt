package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    private val origin = GeoPoint(45.0, 5.0)

    @Test
    fun `projection places north and east correctly`() {
        val north = Geo.project(origin, GeoPoint(45.001, 5.0))
        assertEquals(0.0, north.x, 1e-6)
        assertEquals(110.54, north.y, 0.01)

        val east = Geo.project(origin, GeoPoint(45.0, 5.001))
        // À 45° de latitude, un millième de degré de longitude vaut ~78,7 m.
        assertEquals(78.7, east.x, 0.5)
        assertEquals(0.0, east.y, 1e-6)
    }

    @Test
    fun `heading of zero leaves the frame unchanged`() {
        val point = PlanePoint(100.0, 200.0)

        val rotated = Geo.rotateToHeading(point, 0.0)

        assertEquals(100.0, rotated.x, 1e-9)
        assertEquals(200.0, rotated.y, 1e-9)
    }

    @Test
    fun `travelling east puts the east direction up`() {
        // Cap 90° : ce qui est à l'est doit se retrouver devant, donc en haut.
        val ahead = Geo.rotateToHeading(PlanePoint(100.0, 0.0), 90.0)
        assertEquals(0.0, ahead.x, 1e-6)
        assertEquals(100.0, ahead.y, 1e-6)

        // Et le nord passe à gauche.
        val left = Geo.rotateToHeading(PlanePoint(0.0, 100.0), 90.0)
        assertEquals(-100.0, left.x, 1e-6)
        assertEquals(0.0, left.y, 1e-6)
    }

    @Test
    fun `travelling south puts the south direction up`() {
        val ahead = Geo.rotateToHeading(PlanePoint(0.0, -100.0), 180.0)

        assertEquals(0.0, ahead.x, 1e-6)
        assertEquals(100.0, ahead.y, 1e-6)
    }

    @Test
    fun `rotation preserves distances`() {
        val point = PlanePoint(300.0, -400.0)

        listOf(0.0, 37.0, 90.0, 213.0, 359.0).forEach { heading ->
            val rotated = Geo.rotateToHeading(point, heading)
            assertEquals(500.0, Math.hypot(rotated.x, rotated.y), 1e-6)
        }
    }

    @Test
    fun `track up projection combines both steps`() {
        val point = GeoPoint(45.001, 5.0)

        val combined = Geo.toTrackUpPlane(origin, 90.0, point)
        val stepByStep = Geo.rotateToHeading(Geo.project(origin, point), 90.0)

        assertEquals(stepByStep.x, combined.x, 1e-9)
        assertEquals(stepByStep.y, combined.y, 1e-9)
    }

    @Test
    fun `distance between two points`() {
        assertEquals(110.54, Geo.distance(origin, GeoPoint(45.001, 5.0)), 0.01)
        assertEquals(0.0, Geo.distance(origin, origin), 1e-9)
    }

    @Test
    fun `nice scale picks the largest round value that fits`() {
        assertEquals(500.0, Geo.niceScale(900.0), 1e-9)
        assertEquals(1_000.0, Geo.niceScale(1_000.0), 1e-9)
        assertEquals(2_000.0, Geo.niceScale(4_999.0), 1e-9)
        // Sous la plus petite valeur possible, on retombe sur celle-ci.
        assertEquals(10.0, Geo.niceScale(1.0), 1e-9)
    }
}
