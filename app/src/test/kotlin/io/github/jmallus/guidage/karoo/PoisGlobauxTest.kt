package io.github.jmallus.guidage.karoo

import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.karoo.GuidanceProvider.Companion.withGlobalPois
import io.hammerhead.karooext.models.Symbol
import org.junit.Assert.assertEquals
import org.junit.Test

class PoisGlobauxTest {

    /** Une ligne droite vers l'est d'environ 11 km, à l'équateur pour que le calcul reste simple. */
    private val route = Route(
        name = "Ligne",
        totalDistance = 11_100.0,
        profile = null,
        path = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.1)),
    )

    @Test
    fun `un point global au bord de la trace rejoint le profil`() {
        val resto = Symbol.POI(id = "resto", lat = 0.0005, lng = 0.044, type = "food", name = "Resto")
        val complete = route.withGlobalPois(listOf(resto))

        val poi = complete.pois.single()
        assertEquals("resto", poi.id)
        assertEquals(4_900.0, poi.distanceAlongRoute, 100.0)
    }

    @Test
    fun `un point global loin de la trace est ignore`() {
        val loin = Symbol.POI(id = "loin", lat = 0.02, lng = 0.05, type = "food", name = "Loin")
        assertEquals(0, route.withGlobalPois(listOf(loin)).pois.size)
    }
}
