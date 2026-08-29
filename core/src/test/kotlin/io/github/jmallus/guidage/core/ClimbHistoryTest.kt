package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbHistoryTest {

    private fun climb(start: Double, length: Double = 1_000.0) =
        RouteClimb(startDistance = start, length = length, grade = 5.0, totalElevation = 50.0)

    private fun route(vararg climbs: RouteClimb) = Route(
        name = "Boucle du bocage",
        totalDistance = 60_000.0,
        profile = null,
        climbs = climbs.toList(),
    )

    @Test
    fun `garde les cotes deja passees`() {
        val history = ClimbHistory()
        history.remember(route(climb(5_000.0), climb(20_000.0), climb(40_000.0)))

        // Le Karoo ne rapporte plus que les deux dernières.
        val merged = history.remember(route(climb(20_000.0), climb(40_000.0)))

        assertEquals(3, merged.climbs.size)
        assertEquals(listOf(5_000.0, 20_000.0, 40_000.0), merged.climbs.map { it.startDistance })
    }

    @Test
    fun `la numerotation ne recule pas quand la liste maigrit`() {
        val history = ClimbHistory()
        val complete = history.remember(route(climb(5_000.0), climb(20_000.0), climb(40_000.0)))
        val before = Guidance.climbStatus(complete, 1_000.0)!!
        assertEquals(1, before.number)
        assertEquals(3, before.totalClimbs)

        // La deuxième côte est entamée : le Karoo l'a déjà retirée de sa liste.
        val pruned = history.remember(route(climb(40_000.0)))
        val after = Guidance.climbStatus(pruned, 20_500.0)!!

        assertEquals(3, after.totalClimbs)
        assertEquals(2, after.number)
        assertTrue(after.onClimb)
    }

    @Test
    fun `la cote en cours reste connue une fois entamee`() {
        val history = ClimbHistory()
        history.remember(route(climb(5_000.0, length = 2_000.0)))

        // Dès qu'on y entre, le Karoo la retire de sa liste.
        val merged = history.remember(route())
        val status = Guidance.climbStatus(merged, 5_500.0)

        assertTrue(status != null && status.onClimb)
        assertEquals(1, status!!.number)
    }

    @Test
    fun `un nouvel itineraire repart de zero`() {
        val history = ClimbHistory()
        history.remember(route(climb(5_000.0), climb(20_000.0)))

        val other = Route(
            name = "Sortie du dimanche",
            totalDistance = 30_000.0,
            profile = null,
            climbs = listOf(climb(2_000.0)),
        )
        val merged = history.remember(other)

        assertEquals(listOf(2_000.0), merged.climbs.map { it.startDistance })
    }

    @Test
    fun `un pied legerement deplace ne compte pas deux fois`() {
        val history = ClimbHistory()
        history.remember(route(climb(5_000.0)))

        val merged = history.remember(route(climb(5_030.0)))

        assertEquals(1, merged.climbs.size)
        assertEquals(5_000.0, merged.climbs.first().startDistance, 1e-9)
    }

    @Test
    fun `un itineraire sans cote reste sans cote`() {
        val history = ClimbHistory()
        val merged = history.remember(route())

        assertTrue(merged.climbs.isEmpty())
    }
}
