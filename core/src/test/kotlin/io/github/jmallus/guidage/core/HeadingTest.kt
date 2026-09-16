package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTest {

    private val depart = GeoPoint(49.0, 1.0)

    @Test
    fun `le premier cap rapporte est retenu tel quel`() {
        val cap = SteadyHeading()
        assertNull(cap.observe(depart, null))
        assertEquals(37.0, cap.observe(depart, 37.0)!!, 1e-9)
    }

    @Test
    fun `a l'arret, le cap rapporte est ignore et le dernier reste`() {
        val cap = SteadyHeading()
        cap.observe(depart, 37.0)
        // Le point tremble d'un ou deux mètres, et l'appareil annonce un demi-tour.
        val tremble = Geo.advance(depart, 120.0, 2.0)
        assertEquals(37.0, cap.observe(tremble, 217.0)!!, 1e-9)
        assertEquals(37.0, cap.observe(depart, 200.0)!!, 1e-9)
    }

    @Test
    fun `en mouvement, le cap suit ce que l'appareil rapporte`() {
        val cap = SteadyHeading()
        cap.observe(depart, 37.0)
        val plusLoin = Geo.advance(depart, 37.0, SteadyHeading.MIN_MOVE_METERS + 1.0)
        assertEquals(42.0, cap.observe(plusLoin, 42.0)!!, 1e-9)
        // Sans cap rapporté, le dernier reste, même en mouvement.
        val encore = Geo.advance(plusLoin, 42.0, 20.0)
        assertEquals(42.0, cap.observe(encore, null)!!, 1e-9)
    }

    @Test
    fun `l'ancre suit la derniere prise, pas le dernier point`() {
        val cap = SteadyHeading()
        cap.observe(depart, 10.0)
        // Trois petits pas de quatre mètres : aucun ne franchit le seuil depuis l'ancre
        // tout seul, mais leur somme le franchit, et le cap doit alors se reprendre.
        var position = depart
        var retenu = 10.0
        repeat(3) {
            position = Geo.advance(position, 10.0, 4.0)
            retenu = cap.observe(position, 20.0)!!
        }
        assertEquals(20.0, retenu, 1e-9)
    }
}
