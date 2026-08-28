package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResupplyTest {

    private val eau = setOf("water", "aid_station")

    private fun route(vararg pois: RoutePoi, longueur: Double = 60_000.0) = Route(
        name = "Traversée",
        totalDistance = longueur,
        profile = null,
        climbs = emptyList(),
        pois = pois.toList(),
    )

    private fun poi(id: String, distance: Double, type: String = "water") =
        RoutePoi(id = id, name = null, type = type, distanceAlongRoute = distance)

    @Test
    fun `le prochain point et le dernier passe se lisent des deux cotes`() {
        val route = route(poi("a", 5_000.0), poi("b", 9_000.0), poi("c", 12_000.0))

        val status = Resupply.status(route, distanceAlongRoute = 10_000.0, types = eau)

        assertEquals("c", status.next!!.poi.id)
        assertEquals(2_000.0, status.next!!.distance, 1e-9)
        assertEquals(1_000.0, status.sinceLast!!, 1e-9)
    }

    @Test
    fun `sans point derriere on ne compte rien depuis rien`() {
        val route = route(poi("a", 5_000.0))

        assertNull(Resupply.status(route, distanceAlongRoute = 1_000.0, types = eau).sinceLast)
    }

    @Test
    fun `la traversee est annoncee par le dernier point qui la precede`() {
        // Trois points serrés, puis quarante kilomètres de rien jusqu'à l'arrivée.
        val route = route(poi("a", 4_000.0), poi("b", 8_000.0), poi("c", 20_000.0))

        val crossing = Resupply.status(route, distanceAlongRoute = 1_000.0, types = eau).crossing!!

        assertEquals("le dernier point utile n'est pas le bon", "c", crossing.lastPoi!!.poi.id)
        assertEquals(40_000.0, crossing.length, 1e-9)
        assertEquals("la distance au point n'est pas prise depuis le coureur", 19_000.0, crossing.lastPoi!!.distance, 1e-9)
    }

    @Test
    fun `un itineraire bien pourvu n annonce rien`() {
        val route = route(
            poi("a", 4_000.0), poi("b", 14_000.0), poi("c", 24_000.0),
            poi("d", 34_000.0), poi("e", 44_000.0), poi("f", 54_000.0),
            longueur = 60_000.0,
        )

        assertNull(Resupply.status(route, distanceAlongRoute = 0.0, types = eau).crossing)
    }

    @Test
    fun `la premiere traversee est retenue et non la plus longue`() {
        // Vingt kilomètres, puis trente : c'est la première qui se décide en premier.
        val route = route(poi("a", 2_000.0), poi("b", 22_000.0), longueur = 52_000.0)

        val crossing = Resupply.status(route, distanceAlongRoute = 0.0, types = eau).crossing!!

        assertEquals("a", crossing.lastPoi!!.poi.id)
        assertEquals(20_000.0, crossing.length, 1e-9)
    }

    @Test
    fun `sans plus rien devant la traversee se compte depuis le coureur`() {
        val route = route(poi("a", 4_000.0), longueur = 60_000.0)

        val crossing = Resupply.status(route, distanceAlongRoute = 20_000.0, types = eau).crossing!!

        assertNull("il n'y a plus de point à annoncer", crossing.lastPoi)
        assertEquals(40_000.0, crossing.length, 1e-9)
    }

    @Test
    fun `la fin de parcours toute proche n est pas une traversee`() {
        val route = route(poi("a", 4_000.0), longueur = 10_000.0)

        assertNull(Resupply.status(route, distanceAlongRoute = 5_000.0, types = eau).crossing)
    }

    @Test
    fun `seuls les types demandes comptent`() {
        // Un sommet et un point de vue ne remplissent pas un bidon : s'ils comptaient, les
        // écarts seraient courts et aucune traversée ne serait annoncée.
        val route = route(
            poi("eau", 5_000.0),
            poi("vue", 20_000.0, type = "viewpoint"),
            poi("sommet", 40_000.0, type = "summit"),
            longueur = 60_000.0,
        )

        val status = Resupply.status(route, distanceAlongRoute = 0.0, types = eau)

        assertEquals("eau", status.next!!.poi.id)
        assertNotNull("la traversée après le point d'eau n'est pas vue", status.crossing)
        assertEquals("eau", status.crossing!!.lastPoi!!.poi.id)
        assertEquals(55_000.0, status.crossing!!.length, 1e-9)
    }

    @Test
    fun `sans type retenu le calcul ne dit rien`() {
        val route = route(poi("a", 4_000.0))

        assertEquals(ResupplyStatus.NONE, Resupply.status(route, 0.0, types = emptySet()))
    }

    @Test
    fun `la station service vaut le point d eau si on la demande`() {
        val route = route(poi("essence", 20_000.0, type = "gas_station"), longueur = 60_000.0)

        val avec = Resupply.status(route, 0.0, types = eau + "gas_station")
        val sans = Resupply.status(route, 0.0, types = eau)

        assertEquals("essence", avec.next!!.poi.id)
        assertNull(sans.next)
    }
}
