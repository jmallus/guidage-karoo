package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomVirageTest {

    @Test
    fun `loin d'un virage la carte garde son cran`() {
        val z = ZoomVirage()
        assertEquals(PorteeCarte.of(MapZoom.NEAR), z.portee(MapZoom.NEAR, 600.0, 10_000.0))
        assertEquals(PorteeCarte.of(MapZoom.NEAR), z.portee(MapZoom.NEAR, null, 10_000.0))
    }

    @Test
    fun `a cent cinquante metres elle passe en gros plan`() {
        val z = ZoomVirage()
        assertEquals(ZoomVirage.GROS_PLAN, z.portee(MapZoom.FAR, 150.0, 10_000.0))
        assertEquals(ZoomVirage.GROS_PLAN, z.portee(MapZoom.FAR, 20.0, 10_130.0))
    }

    @Test
    fun `le virage passe elle tient encore quarante metres puis revient au cran choisi`() {
        val z = ZoomVirage()
        z.portee(MapZoom.MIDDLE, 100.0, 10_000.0) // virage à 10 100 m
        // Le Karoo annonce aussitôt le virage suivant, loin : le gros plan tient jusqu'à 10 140.
        assertEquals(ZoomVirage.GROS_PLAN, z.portee(MapZoom.MIDDLE, 900.0, 10_120.0))
        assertEquals(PorteeCarte.of(MapZoom.MIDDLE), z.portee(MapZoom.MIDDLE, 880.0, 10_141.0))
    }

    @Test
    fun `meme le cran le plus court se resserre`() {
        // Cent cinquante mètres de portée restent plus larges que le gros plan.
        assertEquals(ZoomVirage.GROS_PLAN, ZoomVirage().portee(MapZoom.CLOSE, 50.0, 0.0))
    }
}
