package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {

    private val origin = GeoPoint(45.0, 5.0)

    @Test
    fun `advance moves along the heading`() {
        val north = Geo.advance(origin, 0.0, 100.0)
        assertEquals(100.0, Geo.distance(origin, north), 0.5)
        assertEquals(origin.lng, north.lng, 1e-9)
        assertEquals(true, north.lat > origin.lat)

        val east = Geo.advance(origin, 90.0, 100.0)
        assertEquals(100.0, Geo.distance(origin, east), 0.5)
        assertEquals(origin.lat, east.lat, 1e-9)
        assertEquals(true, east.lng > origin.lng)

        // Une avance nulle laisse la position intacte.
        assertEquals(origin, Geo.advance(origin, 42.0, 0.0))
    }

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

    /**
     * Un tracé plein nord depuis l'origine, un point tous les cent mètres environ.
     *
     * Une ligne droite suffit : ce qu'on vérifie est la projection sur un segment et le
     * cumul le long du tracé, pas la géodésie.
     */
    private val traceNord = (0..10).map { GeoPoint(origin.lat + it * 0.001, origin.lng) }

    @Test
    fun `un point pose sur le trace donne sa distance depuis le depart`() {
        // Le troisième sommet, exactement.
        assertEquals(
            3 * 110.54,
            Geo.distanceAlongPath(traceNord, GeoPoint(origin.lat + 0.003, origin.lng), 50.0)!!,
            0.5,
        )
        // Au milieu d'un segment.
        assertEquals(
            2.5 * 110.54,
            Geo.distanceAlongPath(traceNord, GeoPoint(origin.lat + 0.0025, origin.lng), 50.0)!!,
            0.5,
        )
        assertEquals(0.0, Geo.distanceAlongPath(traceNord, traceNord.first(), 50.0)!!, 0.5)
    }

    @Test
    fun `un point ecarte du trace s y rattache tant qu il reste proche`() {
        // Cinquante mètres à l'est du cinquième sommet : le commerce au bord de la route.
        val ecarte = Geo.advance(GeoPoint(origin.lat + 0.005, origin.lng), 90.0, 50.0)
        assertEquals(5 * 110.54, Geo.distanceAlongPath(traceNord, ecarte, 100.0)!!, 1.0)
    }

    @Test
    fun `un point trop loin n est pas rattache`() {
        // Le village d'à côté, à cinq cents mètres : l'annoncer serait pire que se taire.
        val lointain = Geo.advance(GeoPoint(origin.lat + 0.005, origin.lng), 90.0, 500.0)
        assertNull(Geo.distanceAlongPath(traceNord, lointain, 250.0))
    }

    @Test
    fun `un point au dela d une extremite se rattache a celle ci`() {
        // Au sud du départ : il se pose sur le premier sommet, il ne prolonge pas le tracé
        // vers les distances négatives.
        val avantLeDepart = Geo.advance(origin, 180.0, 30.0)
        assertEquals(0.0, Geo.distanceAlongPath(traceNord, avantLeDepart, 50.0)!!, 0.5)
    }

    @Test
    fun `un trace sans segment ne rattache rien`() {
        assertNull(Geo.distanceAlongPath(emptyList(), origin, 100.0))
        assertNull(Geo.distanceAlongPath(listOf(origin), origin, 100.0))
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
