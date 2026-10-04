package io.github.jmallus.guidage.extension

import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeniveleRestantTest {

    private val etat = GuidanceState(
        route = Route(
            name = "Col",
            totalDistance = 10_000.0,
            profile = ElevationProfile(
                listOf(ProfilePoint(0.0, 100.0), ProfilePoint(5_000.0, 400.0), ProfilePoint(10_000.0, 700.0)),
            ),
        ),
        distanceAlongRoute = 5_000.0,
    )

    @Test
    fun `le profil prend le relais quand le Karoo dit zero`() {
        assertEquals(300.0, LevelModels.restantJusquALArrivee(0.0, etat)!!, 0.1)
        assertEquals(300.0, LevelModels.restantJusquALArrivee(null, etat)!!, 0.1)
    }

    @Test
    fun `le chiffre du Karoo prime quand il en donne un`() {
        assertEquals(280.0, LevelModels.restantJusquALArrivee(280.0, etat)!!, 0.0)
    }

    @Test
    fun `sans itineraire le Karoo reste seul juge`() {
        assertEquals(0.0, LevelModels.restantJusquALArrivee(0.0, GuidanceState.IDLE)!!, 0.0)
        assertNull(LevelModels.restantJusquALArrivee(null, GuidanceState.IDLE))
    }
}
