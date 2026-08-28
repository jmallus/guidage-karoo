package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RideContextTest {

    @Test
    fun `sans rien de particulier on roule`() {
        assertEquals(RideContext.CRUISE, RideContexts.candidate(ContextInputs(gradePercent = 0.5)))
    }

    @Test
    fun `la pente fait la montee, le Karoo aussi`() {
        assertEquals(RideContext.CLIMB, RideContexts.candidate(ContextInputs(gradePercent = 6.0)))
        assertEquals(
            RideContext.CLIMB,
            RideContexts.candidate(ContextInputs(onClimb = true, gradePercent = 1.0)),
        )
    }

    @Test
    fun `une descente sans virage reste du roulage`() {
        // Descendre ne suffit pas : c'est le virage qui fait la descente intéressante.
        assertEquals(RideContext.CRUISE, RideContexts.candidate(ContextInputs(gradePercent = -7.0)))
    }

    @Test
    fun `une descente avec un virage devant prend la main`() {
        val inputs = ContextInputs(gradePercent = -7.0, sharpBendDistance = 400.0)

        assertEquals(RideContext.DESCENT, RideContexts.candidate(inputs))
    }

    @Test
    fun `le virage passe devant la fontaine`() {
        // À soixante à l'heure, une fontaine attend, un virage non.
        val inputs = ContextInputs(
            gradePercent = -8.0,
            sharpBendDistance = 300.0,
            resupplyDistance = 200.0,
        )

        assertEquals(RideContext.DESCENT, RideContexts.candidate(inputs))
    }

    @Test
    fun `la fontaine passe devant la montee`() {
        // Elle se décide avant d'être dépassée ; la côte, elle, dure.
        val inputs = ContextInputs(onClimb = true, gradePercent = 7.0, resupplyDistance = 400.0)

        assertEquals(RideContext.RESUPPLY, RideContexts.candidate(inputs))
    }

    @Test
    fun `un ravitaillement lointain ne prend pas la main`() {
        val inputs = ContextInputs(gradePercent = 0.0, resupplyDistance = 4_000.0)

        assertEquals(RideContext.CRUISE, RideContexts.candidate(inputs))
    }

    /* ------------------------------------------------------------- la bascule */

    @Test
    fun `l etat ne bascule pas au premier releve`() {
        val selector = ContextSelector(holdSeconds = 8.0)

        assertEquals(RideContext.CRUISE, selector.update(1.0, RideContext.CLIMB))
        assertEquals(RideContext.CRUISE, selector.update(3.0, RideContext.CLIMB))
    }

    @Test
    fun `l etat bascule quand le candidat tient`() {
        val selector = ContextSelector(holdSeconds = 8.0)
        repeat(3) { selector.update(3.0, RideContext.CLIMB) }

        assertEquals(RideContext.CLIMB, selector.current)
    }

    @Test
    fun `une pente qui oscille ne fait pas clignoter le champ`() {
        val selector = ContextSelector(holdSeconds = 8.0)
        // Trois pour cent tout juste, franchis dans un sens puis dans l'autre : sans le
        // maintien, la moitié basse changerait deux fois par seconde.
        repeat(10) {
            selector.update(1.0, RideContext.CLIMB)
            selector.update(1.0, RideContext.CRUISE)
        }

        assertEquals(RideContext.CRUISE, selector.current)
    }

    @Test
    fun `la descente ne patiente pas`() {
        val selector = ContextSelector(holdSeconds = 8.0)

        assertEquals(RideContext.DESCENT, selector.update(0.5, RideContext.DESCENT))
    }

    @Test
    fun `le retour au calme repasse par le maintien`() {
        val selector = ContextSelector(holdSeconds = 8.0)
        selector.update(0.5, RideContext.DESCENT)

        assertEquals(RideContext.DESCENT, selector.update(4.0, RideContext.CRUISE))
        assertEquals(RideContext.CRUISE, selector.update(5.0, RideContext.CRUISE))
    }

    @Test
    fun `un candidat qui change remet le compteur a zero`() {
        val selector = ContextSelector(holdSeconds = 8.0)
        selector.update(6.0, RideContext.CLIMB)
        selector.update(6.0, RideContext.RESUPPLY)

        assertEquals("la montée ne doit pas avoir basculé", RideContext.CRUISE, selector.current)
    }
}
