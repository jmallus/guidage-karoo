package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbProgressTest {

    @Test
    fun `hors cote rien n est en cours`() {
        assertFalse(ClimbProgress.NONE.onClimb)
        assertNull(ClimbProgress.NONE.length)
        assertNull(ClimbProgress.NONE.label)
    }

    @Test
    fun `des champs a zero ne sont pas une cote`() {
        // Entre deux côtes, le Karoo continue de publier ses champs à zéro.
        val idle = ClimbProgress(distanceFromBottom = 0.0, distanceToTop = 0.0)

        assertFalse(idle.onClimb)
    }

    @Test
    fun `la progression se lit sur les deux bouts`() {
        val climb = ClimbProgress(distanceFromBottom = 400.0, distanceToTop = 600.0)

        assertTrue(climb.onClimb)
        assertEquals(1_000.0, climb.length!!, 1e-9)
        assertEquals(0.4, climb.progress, 1e-9)
    }

    @Test
    fun `le rang s ecrit avec son total`() {
        assertEquals("2/5", ClimbProgress(number = 2, totalClimbs = 5).label)
        assertEquals("2", ClimbProgress(number = 2).label)
        assertNull(ClimbProgress(number = 0, totalClimbs = 5).label)
    }
}
