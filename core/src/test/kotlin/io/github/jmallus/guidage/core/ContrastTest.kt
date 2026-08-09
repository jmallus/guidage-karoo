package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ContrastTest {

    @Test
    fun `black text wins on the light zone colors`() {
        // Jaune tempo et saumon seuil : le blanc y est illisible.
        assertEquals(Contrast.BLACK, Contrast.bestTextColor(Zones.POWER_COLORS[2]))
        assertEquals(Contrast.BLACK, Contrast.bestTextColor(Zones.POWER_COLORS[3]))
        assertEquals(Contrast.BLACK, Contrast.bestTextColor(Zones.POWER_COLORS[1]))
    }

    @Test
    fun `white text wins on the dark zone colors`() {
        assertEquals(Contrast.WHITE, Contrast.bestTextColor(Zones.POWER_COLORS[0]))
        assertEquals(Contrast.WHITE, Contrast.bestTextColor(Zones.POWER_COLORS[5]))
        assertEquals(Contrast.WHITE, Contrast.bestTextColor(Zones.POWER_COLORS[6]))
        assertEquals(Contrast.WHITE, Contrast.bestTextColor(Zones.BELOW_AVERAGE))
    }

    @Test
    fun `polarity is signed - dark on light is positive, light on dark is negative`() {
        assertTrue(Contrast.apca(Contrast.BLACK, Contrast.WHITE) > 0)
        assertTrue(Contrast.apca(Contrast.WHITE, Contrast.BLACK) < 0)
    }

    @Test
    fun `identical colors have no contrast`() {
        assertEquals(0.0, Contrast.apca(Zones.ABOVE_AVERAGE, Zones.ABOVE_AVERAGE), 1e-9)
    }

    @Test
    fun `black on white matches the APCA reference value`() {
        // Valeur de référence du dépôt SAPC-APCA pour la paire extrême.
        assertTrue(abs(Contrast.apca(Contrast.BLACK, Contrast.WHITE) - 106.04) < 0.5)
    }
}
