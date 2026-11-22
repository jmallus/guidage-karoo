package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnduranceTest {

    /* ---------------------------------------------------------------- batterie */

    @Test
    fun `la decharge se mesure sur la fenetre entiere`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, 90.0)
        batterie.observe(1_800_000L, 84.0)

        assertEquals(84.0, batterie.percent!!, 1e-9)
        assertEquals(12.0, batterie.perHour!!, 1e-9)
    }

    /** Sur quelques minutes, un niveau publié par points entiers ne dit rien de sa pente. */
    @Test
    fun `une fenetre trop courte ou sans perte ne dit rien`() {
        val trop = BatteryDrain()
        trop.observe(0L, 90.0)
        trop.observe(60_000L, 89.0)
        assertNull(trop.perHour)

        val plate = BatteryDrain()
        plate.observe(0L, 90.0)
        plate.observe(3_600_000L, 90.0)
        assertNull(plate.perHour)
    }

    /** Une recharge en route remet la mesure à plat plutôt que de rendre une pente négative. */
    @Test
    fun `une recharge repart de la`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, 50.0)
        batterie.observe(1_800_000L, 44.0)
        batterie.observe(3_600_000L, 95.0)
        assertNull(batterie.perHour)

        batterie.observe(7_200_000L, 89.0)
        assertEquals(6.0, batterie.perHour!!, 1e-9)
    }

    @Test
    fun `un niveau hors bornes est ignore`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, null)
        batterie.observe(1_000L, 140.0)

        assertNull(batterie.percent)
        assertNull(batterie.perHour)
    }
}
