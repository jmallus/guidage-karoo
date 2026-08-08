package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {

    // Itinéraire de 10 km : plat jusqu'à 2 km, montée de 2 km à 4 km (+200 m),
    // descente jusqu'à 6 km, puis montée de 8 km à 9 km (+50 m).
    private val profile = ElevationProfile(
        listOf(
            ProfilePoint(0.0, 100.0),
            ProfilePoint(2000.0, 100.0),
            ProfilePoint(4000.0, 300.0),
            ProfilePoint(6000.0, 150.0),
            ProfilePoint(8000.0, 150.0),
            ProfilePoint(9000.0, 200.0),
            ProfilePoint(10000.0, 180.0),
        ),
    )

    private val route = Route(
        name = "Col de test",
        totalDistance = 10000.0,
        profile = profile,
        climbs = listOf(
            RouteClimb(startDistance = 2000.0, length = 2000.0, grade = 10.0, totalElevation = 200.0),
            RouteClimb(startDistance = 8000.0, length = 1000.0, grade = 5.0, totalElevation = 50.0),
        ),
        pois = listOf(
            RoutePoi("water-1", "Fontaine", "water", 3000.0),
            RoutePoi("food-1", "Ravito", "food", 7000.0),
        ),
    )

    @Test
    fun `next climb before starting it`() {
        val status = Guidance.climbStatus(route, 500.0)!!

        assertFalse(status.onClimb)
        assertEquals(1, status.number)
        assertEquals(2, status.totalClimbs)
        assertEquals(1500.0, status.distanceToStart, 1e-6)
        assertEquals(3500.0, status.distanceToTop, 1e-6)
        assertEquals(200.0, status.elevationToTop, 1e-6)
        assertEquals(0.0, status.progress, 1e-6)
    }

    @Test
    fun `climb in progress reports remaining work`() {
        val status = Guidance.climbStatus(route, 3000.0)!!

        assertTrue(status.onClimb)
        assertEquals(0.0, status.distanceToStart, 1e-6)
        assertEquals(1000.0, status.distanceToTop, 1e-6)
        // Altitude à 3 km = 200 m, sommet à 300 m.
        assertEquals(100.0, status.elevationToTop, 1e-6)
        assertEquals(0.5, status.progress, 1e-6)
    }

    @Test
    fun `after a climb the next one is returned`() {
        val status = Guidance.climbStatus(route, 5000.0)!!

        assertFalse(status.onClimb)
        assertEquals(2, status.number)
        assertEquals(3000.0, status.distanceToStart, 1e-6)
    }

    @Test
    fun `no climb left at the end of the route`() {
        assertNull(Guidance.climbStatus(route, 9500.0))
    }

    @Test
    fun `route without climbs has no climb status`() {
        assertNull(Guidance.climbStatus(route.copy(climbs = emptyList()), 100.0))
    }

    @Test
    fun `elevation to top falls back on climb total without profile`() {
        val noProfile = route.copy(profile = null)

        val status = Guidance.climbStatus(noProfile, 3000.0)!!

        assertEquals(100.0, status.elevationToTop, 1e-6)
    }

    @Test
    fun `next poi is the closest one ahead`() {
        val first = Guidance.nextPoi(route, 1000.0)!!
        assertEquals("water-1", first.poi.id)
        assertEquals(2000.0, first.distance, 1e-6)

        val second = Guidance.nextPoi(route, 3500.0)!!
        assertEquals("food-1", second.poi.id)
        assertEquals(3500.0, second.distance, 1e-6)

        assertNull(Guidance.nextPoi(route, 8000.0))
    }

    @Test
    fun `profile window is bounded by the route length`() {
        val window = Guidance.profileWindow(route, 9500.0, lookahead = 5000.0)

        assertEquals(9500.0, window.start, 1e-6)
        assertEquals(10000.0, window.end, 1e-6)
        assertFalse(window.isEmpty)
        assertEquals(500.0, window.distanceSpan, 1e-6)
    }

    @Test
    fun `profile window keeps a minimum elevation span on flat terrain`() {
        val window = Guidance.profileWindow(route, 0.0, lookahead = 1000.0)

        assertEquals(Guidance.MIN_ELEVATION_SPAN, window.elevationSpan, 1e-6)
    }

    @Test
    fun `profile window includes look behind context`() {
        val window = Guidance.profileWindow(route, 3000.0, lookahead = 1000.0, lookbehind = 500.0)

        assertEquals(2500.0, window.start, 1e-6)
        assertEquals(4000.0, window.end, 1e-6)
        assertEquals(300.0, window.maxElevation, 1e-6)
    }

    @Test
    fun `profile window without profile is empty`() {
        val window = Guidance.profileWindow(route.copy(profile = null), 1000.0, lookahead = 2000.0)

        assertTrue(window.isEmpty)
    }

    @Test
    fun `route graph window covers the whole route without lookahead`() {
        val window = Guidance.routeGraphWindow(route, 3_000.0, null)

        assertEquals(0.0, window.start, 1e-6)
        assertEquals(10_000.0, window.end, 1e-6)
        assertEquals(100.0, window.minElevation, 1e-6)
        assertEquals(300.0, window.maxElevation, 1e-6)
    }

    @Test
    fun `route graph window with a zoom starts at the current position`() {
        val window = Guidance.routeGraphWindow(route, 3_000.0, 2_000.0)

        assertEquals(3_000.0, window.start, 1e-6)
        assertEquals(5_000.0, window.end, 1e-6)
    }

    @Test
    fun `route graph window is clamped at the end of the route`() {
        val window = Guidance.routeGraphWindow(route, 9_000.0, 20_000.0)

        assertEquals(9_000.0, window.start, 1e-6)
        assertEquals(10_000.0, window.end, 1e-6)
    }
}
