package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {

    private val route = Route(
        name = "Col de test",
        totalDistance = 10000.0,
        profile = null,
        climbs = listOf(
            RouteClimb(startDistance = 2000.0, length = 2000.0, grade = 10.0, totalElevation = 200.0),
            RouteClimb(startDistance = 8000.0, length = 100.0, grade = 5.0, totalElevation = 5.0),
        ),
        pois = listOf(RoutePoi("water-1", "Fontaine", "water", 3000.0)),
    )

    private fun state(along: Double) = GuidanceState(route, along, route.totalDistance - along)

    @Test
    fun `climb approach fires once within the alert distance`() {
        val engine = AlertEngine(AlertSettings(climbDistance = 300.0, poiEnabled = false))

        assertTrue(engine.evaluate(state(1000.0)).isEmpty())

        val alerts = engine.evaluate(state(1800.0))
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.CLIMB_APPROACH, alerts.first().kind)
        assertEquals(1, alerts.first().climb!!.number)

        assertTrue(engine.evaluate(state(1900.0)).isEmpty())
    }

    @Test
    fun `summit alert fires close to the top`() {
        val engine = AlertEngine(
            AlertSettings(climbEnabled = false, summitDistance = 200.0, poiEnabled = false),
        )

        assertTrue(engine.evaluate(state(3000.0)).isEmpty())

        val alerts = engine.evaluate(state(3900.0))
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.CLIMB_TOP, alerts.first().kind)
    }

    @Test
    fun `short climbs are ignored`() {
        val engine = AlertEngine(
            AlertSettings(climbDistance = 300.0, poiEnabled = false, minimumClimbLength = 200.0),
        )

        // Deuxième côte : 100 m seulement, sous le seuil.
        assertTrue(engine.evaluate(state(7800.0)).isEmpty())
    }

    @Test
    fun `poi alert fires once`() {
        val engine = AlertEngine(AlertSettings(climbEnabled = false, summitEnabled = false, poiDistance = 500.0))

        assertTrue(engine.evaluate(state(2000.0)).isEmpty())

        val alerts = engine.evaluate(state(2600.0))
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.POI_APPROACH, alerts.first().kind)
        assertEquals("water-1", alerts.first().poi!!.poi.id)

        assertTrue(engine.evaluate(state(2700.0)).isEmpty())
    }

    @Test
    fun `alerts can fire again after switching route`() {
        val engine = AlertEngine(AlertSettings(poiEnabled = false))

        assertEquals(1, engine.evaluate(state(1800.0)).size)

        val otherRoute = route.copy(name = "Autre parcours")
        val alerts = engine.evaluate(GuidanceState(otherRoute, 1800.0, 8200.0))

        assertEquals(1, alerts.size)
    }

    @Test
    fun `stopping navigation resets the engine`() {
        val engine = AlertEngine(AlertSettings(poiEnabled = false))

        assertEquals(1, engine.evaluate(state(1800.0)).size)
        assertTrue(engine.evaluate(GuidanceState.IDLE).isEmpty())
        assertEquals(1, engine.evaluate(state(1800.0)).size)
    }

    @Test
    fun `disabled alerts never fire`() {
        val engine = AlertEngine(
            AlertSettings(climbEnabled = false, summitEnabled = false, poiEnabled = false),
        )

        assertTrue(engine.evaluate(state(1800.0)).isEmpty())
        assertTrue(engine.evaluate(state(2600.0)).isEmpty())
        assertTrue(engine.evaluate(state(3900.0)).isEmpty())
    }
}
