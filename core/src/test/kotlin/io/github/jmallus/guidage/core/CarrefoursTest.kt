package io.github.jmallus.guidage.core

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrefoursTest {

    /**
     * Une position, en mètres est et nord depuis l'origine.
     *
     * À l'équateur la projection garde la même échelle sur les deux axes : deux cents mètres
     * restent deux cents.
     */
    private fun p(est: Double, nord: Double) = GeoPoint(
        lat = nord / Geo.METERS_PER_DEGREE_LATITUDE,
        lng = est / Geo.METERS_PER_DEGREE_LONGITUDE,
    )

    private fun voie(vararg points: Pair<Double, Double>, kind: RoadKind = RoadKind.TERTIARY) = RoadSegment(
        kind = kind,
        surface = RoadSurface.PAVED,
        latitudes = points.map { (p(it.first, it.second).lat * 1e6).toInt() }.toIntArray(),
        longitudes = points.map { (p(it.first, it.second).lng * 1e6).toInt() }.toIntArray(),
    )

    @Test
    fun `quitter une route qui continue tout droit pour tourner a droite est une manoeuvre`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 200.0), p(200.0, 200.0))
        val voies = listOf(
            voie(0.0 to -50.0, 0.0 to 400.0),
            voie(0.0 to 200.0, 250.0 to 200.0),
        )
        val m = Carrefours.prochaine(trace, voies, 0.0, 400.0)!!
        assertEquals(200.0, m.distance, 10.0)
        assertEquals(90.0, m.carrefour.virage, 10.0)
        assertEquals(1, m.carrefour.cote)
        assertEquals(1, m.carrefour.branches.size)
        assertEquals(0.0, m.carrefour.branches.single(), 10.0)
        assertEquals(1, m.rang)
    }

    @Test
    fun `traverser un croisement tout droit n'en est pas une`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 400.0))
        val voies = listOf(
            voie(0.0 to -50.0, 0.0 to 450.0),
            voie(-200.0 to 200.0, 200.0 to 200.0),
        )
        val carrefours = Carrefours.entre(trace, voies, 0.0, 400.0)
        assertEquals(1, carrefours.size)
        assertEquals(2, carrefours.single().branches.size)
        assertFalse(carrefours.single().manoeuvre)
        assertNull(Carrefours.prochaine(trace, voies, 0.0, 400.0))
    }

    @Test
    fun `une voie a droite avant celle qu'on prend en fait la deuxieme a droite`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 250.0), p(200.0, 250.0))
        val voies = listOf(
            voie(0.0 to -50.0, 0.0 to 450.0),
            voie(0.0 to 120.0, 200.0 to 120.0),
            voie(0.0 to 250.0, 250.0 to 250.0),
        )
        val m = Carrefours.prochaine(trace, voies, 0.0, 400.0)!!
        assertEquals(250.0, m.distance, 10.0)
        assertEquals(2, m.rang)
        assertEquals(1, m.voiesAvant.size)
        assertEquals(120.0, m.voiesAvant.single().first, 10.0)
    }

    @Test
    fun `une voie qui longe le trace de travers n'est pas un carrefour`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 400.0))
        // Huit mètres à côté : le fond de carte et le routeur ne tombent jamais pile.
        val voies = listOf(voie(8.0 to -50.0, 8.0 to 450.0))
        assertTrue(Carrefours.entre(trace, voies, 0.0, 400.0).isEmpty())
    }

    @Test
    fun `suivre une route qui s'inflechit quand un chemin file tout droit est une manoeuvre`() {
        val coude = p(0.0, 200.0)
        val sortie = Geo.advance(coude, 35.0, 200.0)
        val trace = listOf(p(0.0, 0.0), coude, sortie)
        val sortieMetres = Geo.project(p(0.0, 0.0), sortie)
        val voies = listOf(
            voie(0.0 to -50.0, 0.0 to 200.0, sortieMetres.x to sortieMetres.y),
            voie(0.0 to 200.0, 0.0 to 450.0, kind = RoadKind.TRACK),
        )
        val m = Carrefours.prochaine(trace, voies, 0.0, 400.0)!!
        assertEquals(35.0, m.carrefour.virage, 10.0)
        assertTrue(m.carrefour.manoeuvre)
    }

    @Test
    fun `un virage sans autre voie n'est pas un carrefour`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 200.0), p(200.0, 200.0))
        val voies = listOf(voie(0.0 to -50.0, 0.0 to 200.0, 250.0 to 200.0))
        assertNull(Carrefours.prochaine(trace, voies, 0.0, 400.0))
    }

    @Test
    fun `les surfaces et les cours d'eau ne font pas de carrefour`() {
        val trace = listOf(p(0.0, 0.0), p(0.0, 400.0))
        val voies = listOf(
            voie(0.0 to -50.0, 0.0 to 450.0),
            voie(-200.0 to 200.0, 200.0 to 200.0, kind = RoadKind.STREAM),
            voie(-200.0 to 100.0, 200.0 to 100.0, kind = RoadKind.FOREST),
        )
        assertTrue(Carrefours.entre(trace, voies, 0.0, 400.0).isEmpty())
    }
}
