package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FormatTest {

    private val fr = Locale.FRANCE
    private val us = Locale.US

    @Test
    fun `metric distances switch to kilometers above 1 km`() {
        assertEquals("450 m", Format.distance(452.0, Units.METRIC, fr))
        assertEquals("2,4 km", Format.distance(2400.0, Units.METRIC, fr))
        assertEquals("2.4 km", Format.distance(2400.0, Units.METRIC, us))
    }

    @Test
    fun `imperial distances use feet then miles`() {
        assertEquals("330 ft", Format.distance(100.0, Units.IMPERIAL, us))
        assertEquals("1.0 mi", Format.distance(1609.344, Units.IMPERIAL, us))
    }

    @Test
    fun `long distance always uses the big unit`() {
        assertEquals("0,5 km", Format.longDistance(450.0, Units.METRIC, fr))
        assertEquals("0.3 mi", Format.longDistance(450.0, Units.IMPERIAL, us))
    }

    @Test
    fun `elevation formatting`() {
        assertEquals("120 m", Format.elevation(119.6, Units.METRIC))
        assertEquals("394 ft", Format.elevation(120.0, Units.IMPERIAL))
    }

    @Test
    fun `grade formatting`() {
        assertEquals("6,5 %", Format.grade(6.54, fr))
        assertEquals("6.5 %", Format.grade(6.54, us))
        assertEquals("7 %", Format.shortGrade(6.54))
    }

    @Test
    fun `speed converts from meters per second`() {
        // 10 m/s = 36 km/h = 22,4 mph
        assertEquals("36,0", Format.speed(10.0, Units.METRIC, fr))
        assertEquals("22.4", Format.speed(10.0, Units.IMPERIAL, us))
        assertEquals("km/h", Format.speedUnit(Units.METRIC))
        assertEquals("mph", Format.speedUnit(Units.IMPERIAL))
    }

    @Test
    fun `clock formats a unix instant as hours and minutes`() {
        // 2026-08-08T16:48:30Z
        val epochMs = 1_786_265_310_000.0

        assertEquals("16:48", Format.clock(epochMs, ZoneId.of("UTC")))
        assertEquals("18:48", Format.clock(epochMs, ZoneId.of("Europe/Paris")))
    }
}
