package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZonesTest {

    private val powerZones = listOf(
        ZoneRange(0, 120),
        ZoneRange(121, 165),
        ZoneRange(166, 200),
        ZoneRange(201, 230),
        ZoneRange(231, 270),
        ZoneRange(271, 330),
        ZoneRange(331, 2000),
    )

    private val heartRateZones = listOf(
        ZoneRange(0, 120),
        ZoneRange(121, 140),
        ZoneRange(141, 155),
        ZoneRange(156, 170),
        ZoneRange(171, 220),
    )

    @Test
    fun `a value lands in the zone whose upper bound it first fits under`() {
        assertEquals(1, Zones.zoneOf(90.0, powerZones))
        assertEquals(3, Zones.zoneOf(200.0, powerZones))
        assertEquals(4, Zones.zoneOf(201.0, powerZones))
    }

    @Test
    fun `a value above every bound stays in the highest zone`() {
        assertEquals(7, Zones.zoneOf(2500.0, powerZones))
    }

    @Test
    fun `without configured zones there is no zone and no color`() {
        assertEquals(0, Zones.zoneOf(200.0, emptyList()))
        assertNull(Zones.powerColor(200.0, emptyList()))
        assertNull(Zones.heartRateColor(150.0, emptyList()))
    }

    @Test
    fun `power and heart rate pick their color from the Karoo palette`() {
        assertEquals(Zones.POWER_COLORS[2], Zones.powerColor(180.0, powerZones))
        assertEquals(Zones.HEART_RATE_COLORS[4], Zones.heartRateColor(180.0, heartRateZones))
    }

    @Test
    fun `la pente reprend la palette de puissance du Karoo`() {
        assertEquals(Zones.POWER_COLORS[0], Zones.gradeColor(0.5))
        assertEquals(Zones.POWER_COLORS[1], Zones.gradeColor(2.0))
        assertEquals(Zones.POWER_COLORS[2], Zones.gradeColor(7.9))
        assertEquals(Zones.POWER_COLORS[3], Zones.gradeColor(8.0))
        assertEquals(Zones.POWER_COLORS[4], Zones.gradeColor(11.0))
        assertEquals(Zones.POWER_COLORS[5], Zones.gradeColor(14.0))
        assertEquals(Zones.POWER_COLORS[6], Zones.gradeColor(25.0))
    }

    @Test
    fun `une descente n a pas de couleur dans cette palette`() {
        assertNull(Zones.gradeColor(-0.1))
        assertNull(Zones.gradeColor(-8.0))
    }

    @Test
    fun `speed is green at or above the ride average and red below`() {
        assertEquals(Zones.ABOVE_AVERAGE, Zones.speedColor(9.0, 8.0))
        assertEquals(Zones.ABOVE_AVERAGE, Zones.speedColor(8.0, 8.0))
        assertEquals(Zones.BELOW_AVERAGE, Zones.speedColor(7.9, 8.0))
    }

    @Test
    fun `speed stays neutral while the ride average is meaningless`() {
        assertNull(Zones.speedColor(9.0, null))
        assertNull(Zones.speedColor(9.0, 0.0))
    }
}
