package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Test
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
}
