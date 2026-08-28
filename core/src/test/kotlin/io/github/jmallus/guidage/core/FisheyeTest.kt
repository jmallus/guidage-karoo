package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FisheyeTest {

    private val scale = FisheyeScale.of(totalMeters = 41_200.0)!!

    @Test
    fun `les deux bouts tombent sur les deux bords`() {
        assertEquals(0.0, scale.position(0.0), 1e-12)
        assertEquals(1.0, scale.position(41_200.0), 1e-12)
        assertEquals("au-delà de l'arrivée, on reste au bord", 1.0, scale.position(90_000.0), 1e-12)
        assertEquals("derrière le coureur, on reste au bord", 0.0, scale.position(-500.0), 1e-12)
    }

    @Test
    fun `la portee fine occupe la part demandee`() {
        assertEquals(
            FisheyeScale.DEFAULT_FINE_SHARE,
            scale.position(FisheyeScale.DEFAULT_FINE_METERS),
            1e-6,
        )
    }

    @Test
    fun `une autre part se demande et s obtient`() {
        val serree = FisheyeScale.of(totalMeters = 100_000.0, fineMeters = 1_000.0, fineShare = 0.30)!!

        assertEquals(0.30, serree.position(1_000.0), 1e-6)
    }

    @Test
    fun `le proche est dilate et le lointain comprime`() {
        // Les cinq cents premiers mètres prennent plus de place que les vingt derniers
        // kilomètres : c'est toute la proposition.
        val premiers = scale.position(500.0) - scale.position(0.0)
        val derniers = scale.position(41_200.0) - scale.position(20_000.0)

        assertTrue("le proche ne prend que $premiers", premiers > derniers)
    }

    @Test
    fun `la projection ne recule jamais`() {
        var precedent = -1.0
        var distance = 0.0
        while (distance <= 41_200.0) {
            val position = scale.position(distance)
            assertTrue("la position recule à $distance m", position >= precedent)
            precedent = position
            distance += 100.0
        }
    }

    @Test
    fun `l inverse rend la distance de depart`() {
        listOf(0.05, 0.2, 0.37, 0.5, 0.75, 0.99).forEach { position ->
            assertEquals(position, scale.position(scale.distanceAt(position)), 1e-9)
        }
    }

    @Test
    fun `un itineraire court ne se comprime pas`() {
        // Deux kilomètres de portée fine sur trois de parcours : une échelle droite en
        // donnerait déjà les deux tiers, il n'y a rien à gagner à la tordre.
        assertNull(FisheyeScale.of(totalMeters = 3_000.0))
        assertNull(FisheyeScale.of(totalMeters = 0.0))
        assertNotNull(FisheyeScale.of(totalMeters = 20_000.0))
    }

    @Test
    fun `les graduations tiennent dans le parcours`() {
        val ticks = FisheyeScale.ticks(41_200.0)

        assertEquals(listOf(100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0), ticks)
        assertTrue("aucune graduation au-delà de l'arrivée", ticks.all { it < 41_200.0 })
    }

    @Test
    fun `au loin, doubler la distance coute la meme largeur`() {
        // C'est la signature d'une échelle logarithmique : passé la portée fine, deux
        // rapports identiques prennent la même place. Près du coureur, au contraire, la
        // projection redevient droite et cent mètres valent cent mètres.
        val cinqADix = scale.position(10_000.0) - scale.position(5_000.0)
        val dixAVingt = scale.position(20_000.0) - scale.position(10_000.0)

        assertEquals(cinqADix, dixAVingt, 0.02)
    }
}
