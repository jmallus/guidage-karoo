package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLevelTest {

    @Test
    fun `la variabilite est le rapport de la normalisee a la moyenne`() {
        assertEquals(1.15, RideLevel(averagePower = 200.0, normalizedPower = 230.0).variability!!, 1e-9)
        assertEquals(1.0, RideLevel(averagePower = 200.0, normalizedPower = 200.0).variability!!, 1e-9)
    }

    /** Sans capteur, ou à l'arrêt, il n'y a pas de rapport à former. */
    @Test
    fun `la variabilite se tait sans les deux puissances`() {
        assertNull(RideLevel(averagePower = 200.0).variability)
        assertNull(RideLevel(normalizedPower = 230.0).variability)
        assertNull(RideLevel(averagePower = 0.0, normalizedPower = 230.0).variability)
    }

    @Test
    fun `les parts de zone se rapportent au temps mesure`() {
        val niveau = RideLevel(heartRateZoneSeconds = listOf(300.0, 600.0, 100.0))

        assertEquals(listOf(0.3f, 0.6f, 0.1f), niveau.heartRateZoneShares)
        assertEquals(2, niveau.dominantHeartRateZone)
    }

    @Test
    fun `sans temps mesure il n'y a ni parts ni zone dominante`() {
        val vide = RideLevel(heartRateZoneSeconds = listOf(0.0, 0.0))

        assertEquals(emptyList<Float>(), vide.heartRateZoneShares)
        assertNull(vide.dominantHeartRateZone)
        assertNull(RideLevel.UNKNOWN.dominantHeartRateZone)
    }

    /**
     * L'horloge n'impute rien au premier appel : elle n'a pas encore de pas à mesurer.
     */
    @Test
    fun `l'horloge accumule le temps de la zone courante`() {
        val horloge = ZoneClock(zoneCount = 5)

        assertFalse(horloge.observe(0L, zone = 2))
        assertTrue(horloge.observe(4_000L, zone = 2))
        assertTrue(horloge.observe(9_000L, zone = 3))

        assertEquals(4.0, horloge.elapsed[1], 1e-9)
        assertEquals(5.0, horloge.elapsed[2], 1e-9)
        assertEquals(0.0, horloge.elapsed[0], 1e-9)
    }

    /**
     * Un trou dans les mesures n'est pas du temps passé dans la zone d'avant : l'appareil
     * s'est tu, et l'imputer fausserait le total là où il compte, sur les longues sorties.
     */
    @Test
    fun `un pas trop long est ecarte`() {
        val horloge = ZoneClock(zoneCount = 5)
        horloge.observe(0L, zone = 1)

        assertFalse(horloge.observe(60_000L, zone = 1))
        assertEquals(0.0, horloge.elapsed.sum(), 1e-9)

        // Le pas suivant repart du dernier instant vu, non du trou.
        assertTrue(horloge.observe(63_000L, zone = 1))
        assertEquals(3.0, horloge.elapsed[0], 1e-9)
    }

    /** Hors zone réglée, le pas est perdu à dessein plutôt qu'imputé à la zone 1. */
    @Test
    fun `un rang hors bornes n'impute rien`() {
        val horloge = ZoneClock(zoneCount = 5)
        horloge.observe(0L, zone = 0)

        assertFalse(horloge.observe(3_000L, zone = 0))
        assertFalse(horloge.observe(6_000L, zone = 9))
        assertEquals(0.0, horloge.elapsed.sum(), 1e-9)
    }

    @Test
    fun `la remise a zero efface le temps et le dernier instant`() {
        val horloge = ZoneClock(zoneCount = 5)
        horloge.observe(0L, zone = 1)
        horloge.observe(5_000L, zone = 1)
        horloge.reset()

        assertEquals(0.0, horloge.elapsed.sum(), 1e-9)
        assertFalse(horloge.observe(6_000L, zone = 1))
    }
}
