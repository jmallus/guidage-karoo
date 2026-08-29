package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

    private val profile = ElevationProfile(
        listOf(
            ProfilePoint(0.0, 100.0),
            ProfilePoint(1000.0, 100.0),
            ProfilePoint(2000.0, 200.0),
            ProfilePoint(3000.0, 150.0),
        ),
    )

    @Test
    fun `interpolates elevation between points`() {
        assertEquals(100.0, profile.elevationAt(500.0)!!, 1e-6)
        assertEquals(150.0, profile.elevationAt(1500.0)!!, 1e-6)
        assertEquals(175.0, profile.elevationAt(2500.0)!!, 1e-6)
    }

    @Test
    fun `clamps outside of bounds`() {
        assertEquals(100.0, profile.elevationAt(-100.0)!!, 1e-6)
        assertEquals(150.0, profile.elevationAt(9999.0)!!, 1e-6)
        assertNull(ElevationProfile.EMPTY.elevationAt(10.0))
    }

    @Test
    fun `slice includes interpolated bounds`() {
        val slice = profile.slice(1500.0, 2500.0)

        assertEquals(3, slice.size)
        assertEquals(1500.0, slice.first().distance, 1e-6)
        assertEquals(150.0, slice.first().elevation, 1e-6)
        assertEquals(2000.0, slice[1].distance, 1e-6)
        assertEquals(2500.0, slice.last().distance, 1e-6)
        assertEquals(175.0, slice.last().elevation, 1e-6)
    }

    @Test
    fun `slice outside profile is empty`() {
        assertTrue(profile.slice(5000.0, 6000.0).isEmpty())
        assertTrue(profile.slice(1000.0, 1000.0).isEmpty())
    }

    @Test
    fun `ascent only counts positive deltas`() {
        assertEquals(100.0, profile.ascentBetween(0.0, 3000.0), 1e-6)
        assertEquals(0.0, profile.ascentBetween(2000.0, 3000.0), 1e-6)
        assertEquals(50.0, profile.ascentBetween(1000.0, 1500.0), 1e-6)
    }

    @Test
    fun `grade between two distances`() {
        assertEquals(10.0, profile.gradeBetween(1000.0, 2000.0)!!, 1e-6)
        assertEquals(-5.0, profile.gradeBetween(2000.0, 3000.0)!!, 1e-6)
        assertNull(profile.gradeBetween(1000.0, 1000.0))
    }

    @Test
    fun `builds from encoded distance elevation pairs`() {
        val encoded = PolylineTest.encode(
            listOf(Pair(0.0, 100.0), Pair(1000.0, 180.0), Pair(2000.0, 120.0)),
            1,
        )

        val decoded = ElevationProfile.fromEncoded(encoded)

        assertNotNull(decoded)
        assertEquals(3, decoded!!.points.size)
        assertEquals(2000.0, decoded.totalDistance, 0.11)
        assertEquals(180.0, decoded.maxElevation, 0.11)
        assertEquals(100.0, decoded.minElevation, 0.11)
    }

    @Test
    fun `null or empty encoded profile yields null`() {
        assertNull(ElevationProfile.fromEncoded(null))
        assertNull(ElevationProfile.fromEncoded(""))
    }

    @Test
    fun `points are sorted by distance`() {
        val unsorted = ElevationProfile(
            listOf(ProfilePoint(500.0, 10.0), ProfilePoint(0.0, 5.0)),
        )

        assertEquals(0.0, unsorted.points.first().distance, 1e-6)
        assertEquals(500.0, unsorted.totalDistance, 1e-6)
    }
}
