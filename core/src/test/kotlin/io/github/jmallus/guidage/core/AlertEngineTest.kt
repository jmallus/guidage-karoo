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
    fun `poi alert fires once`() {
        val engine = AlertEngine(AlertSettings(poiDistance = 500.0))

        assertTrue(engine.evaluate(state(2000.0)).isEmpty())

        val alerts = engine.evaluate(state(2600.0))
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.POI_APPROACH, alerts.first().kind)
        assertEquals("water-1", alerts.first().poi!!.poi.id)

        assertTrue(engine.evaluate(state(2700.0)).isEmpty())
    }

    @Test
    fun `alerts can fire again after switching route`() {
        val engine = AlertEngine(AlertSettings(poiDistance = 500.0))

        assertEquals(1, engine.evaluate(state(2600.0)).size)

        val otherRoute = route.copy(name = "Autre parcours")
        val alerts = engine.evaluate(GuidanceState(otherRoute, 2600.0, 7400.0))

        assertEquals(1, alerts.size)
    }

    @Test
    fun `stopping navigation resets the engine`() {
        val engine = AlertEngine(AlertSettings(poiDistance = 500.0))

        assertEquals(1, engine.evaluate(state(2600.0)).size)
        assertTrue(engine.evaluate(GuidanceState.IDLE).isEmpty())
        assertEquals(1, engine.evaluate(state(2600.0)).size)
    }

    @Test
    fun `disabled alerts never fire`() {
        val engine = AlertEngine(AlertSettings(poiEnabled = false))

        assertTrue(engine.evaluate(state(1800.0)).isEmpty())
        assertTrue(engine.evaluate(state(2600.0)).isEmpty())
        assertTrue(engine.evaluate(state(3900.0)).isEmpty())
    }

    /**
     * Approcher une côte, l'entamer et en atteindre le sommet ne déclenche plus rien.
     *
     * Les trois abscisses sont celles qui faisaient partir les deux annonces supprimées :
     * 1 800 m, à deux cents mètres du pied ; 3 000 m, en pleine montée ; 3 900 m, à cent
     * mètres du sommet. Le contrôle n'a de sens qu'ainsi placé — sur un itinéraire quelconque
     * il passerait tout seul, sans rien dire de ce qu'on a voulu enlever.
     */
    @Test
    fun `climbs no longer raise any alert`() {
        val engine = AlertEngine(AlertSettings(poiEnabled = false))

        listOf(1_800.0, 3_000.0, 3_900.0).forEach { along ->
            assertTrue("une côte annonce encore quelque chose à $along m", engine.evaluate(state(along)).isEmpty())
        }
    }

    /* ------------------------------------------------ la dernière eau avant la traversée */

    private val traversee = Route(
        name = "Causse",
        totalDistance = 60_000.0,
        profile = null,
        climbs = emptyList(),
        pois = listOf(
            RoutePoi("water-1", "Fontaine", "water", 4_000.0),
            RoutePoi("water-2", "Lavoir", "water", 18_000.0),
        ),
    )

    @Test
    fun `le dernier point avant la traversee est annonce a part`() {
        val engine = AlertEngine(
            AlertSettings(poiDistance = 500.0),
            resupplyTypes = setOf("water"),
        )
        fun etat(along: Double) = GuidanceState(traversee, along, traversee.totalDistance - along)

        // Le premier point n'annonce que lui-même : quatorze kilomètres jusqu'au suivant,
        // ce n'est pas une traversée.
        assertEquals(
            listOf(AlertKind.POI_APPROACH),
            engine.evaluate(etat(3_600.0)).map { it.kind },
        )

        // Le second est le dernier avant quarante-deux kilomètres de rien : les deux
        // annonces tombent ensemble, l'une nommant le lavoir, l'autre disant de s'y arrêter.
        val alerts = engine.evaluate(etat(17_600.0))
        assertEquals(
            listOf(AlertKind.POI_APPROACH, AlertKind.RESUPPLY_LAST),
            alerts.map { it.kind },
        )
        assertEquals(42_000.0, alerts.last().crossing!!.length, 1e-9)
    }

    @Test
    fun `la traversee ne s annonce qu une fois`() {
        val engine = AlertEngine(
            AlertSettings(poiDistance = 500.0),
            resupplyTypes = setOf("water"),
        )
        fun etat(along: Double) = GuidanceState(traversee, along, traversee.totalDistance - along)

        assertTrue(engine.evaluate(etat(17_600.0)).any { it.kind == AlertKind.RESUPPLY_LAST })
        assertTrue(engine.evaluate(etat(17_800.0)).none { it.kind == AlertKind.RESUPPLY_LAST })
    }

    @Test
    fun `sans type de ravitaillement rien n est annonce`() {
        val engine = AlertEngine(AlertSettings(poiDistance = 500.0))
        val etat = GuidanceState(traversee, 17_600.0, traversee.totalDistance - 17_600.0)

        assertTrue(engine.evaluate(etat).none { it.kind == AlertKind.RESUPPLY_LAST })
    }
}
