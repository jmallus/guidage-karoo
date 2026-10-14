package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiviTest {

    // Un virage à angle droit : cent mètres plein nord, puis cent mètres plein est.
    private val depart = GeoPoint(45.0, 5.0)
    private val coin = Geo.advance(depart, 0.0, 100.0)
    private val fin = Geo.advance(coin, 90.0, 100.0)
    private val trace = listOf(depart, coin, fin)

    private fun suivre(
        position: GeoPoint,
        heading: Double? = 0.0,
        vitesse: Double? = 10.0,
        secondes: Double = 3.0,
        annoncee: Double? = null,
    ) = Suivi.position(
        path = trace,
        totalDistance = 200.0,
        position = position,
        heading = heading,
        distanceAlongRoute = annoncee,
        speedMetersPerSecond = vitesse,
        secondesSurTrace = secondes,
        secondesHorsTrace = secondes,
    )

    @Test
    fun `dans un virage, la marque tourne avec la route au lieu de filer tout droit`() {
        // À vingt mètres du coin, cap au nord : trente mètres en ligne droite sortiraient de
        // la route de dix mètres au-delà du coin.
        val suivie = suivre(Geo.advance(depart, 0.0, 80.0))
        assertTrue(suivie.surLeTrace)
        val attendu = Geo.advance(coin, 90.0, 10.0)
        assertEquals(0.0, Geo.distance(suivie.point, attendu), 0.5)
        assertEquals(90.0, suivie.heading!!, 1.0)
        assertEquals(110.0, suivie.distanceAlongRoute!!, 0.5)
    }

    @Test
    fun `un point un peu a cote de la route est pose dessus`() {
        val decale = Geo.advance(Geo.advance(depart, 0.0, 50.0), 90.0, 8.0)
        val suivie = suivre(decale, vitesse = 0.0)
        assertEquals(0.0, Geo.distance(suivie.point, Geo.advance(depart, 0.0, 50.0)), 0.5)
        assertEquals(50.0, suivie.distanceAlongRoute!!, 0.5)
    }

    @Test
    fun `hors de l'itineraire, on revient a la ligne droite`() {
        val loin = Geo.advance(Geo.advance(depart, 0.0, 50.0), 270.0, 200.0)
        val suivie = suivre(loin, heading = 0.0, annoncee = 42.0)
        assertFalse(suivie.surLeTrace)
        assertEquals(0.0, Geo.distance(suivie.point, Geo.advance(loin, 0.0, 30.0)), 0.5)
        // L'abscisse reste celle du Karoo : on n'en sait pas mieux.
        assertEquals(42.0, suivie.distanceAlongRoute!!, 1e-9)
    }

    @Test
    fun `l'abscisse suit la longueur annoncee, non celle du trace decode`() {
        val suivie = Suivi.position(
            path = trace,
            totalDistance = 220.0,
            position = Geo.advance(depart, 0.0, 50.0),
            heading = 0.0,
            distanceAlongRoute = null,
            speedMetersPerSecond = 0.0,
            secondesSurTrace = 0.0,
            secondesHorsTrace = 0.0,
        )
        assertEquals(55.0, suivie.distanceAlongRoute!!, 0.5)
    }

    @Test
    fun `au bout du trace, la marque s'arrete a l'arrivee`() {
        val suivie = suivre(Geo.advance(coin, 90.0, 95.0), heading = 90.0)
        assertEquals(0.0, Geo.distance(suivie.point, fin), 0.5)
        assertEquals(90.0, suivie.heading!!, 1e-9)
    }

    @Test
    fun `le cap se lit d'un point a l'autre`() {
        assertEquals(0.0, Geo.bearing(depart, coin), 0.1)
        assertEquals(90.0, Geo.bearing(coin, fin), 0.1)
        assertEquals(270.0, Geo.bearing(fin, coin), 0.1)
    }
}
