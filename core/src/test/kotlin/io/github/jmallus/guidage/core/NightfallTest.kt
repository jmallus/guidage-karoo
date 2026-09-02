package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le verdict « avant la nuit », jugé sur l'incertitude et non sur la seule moyenne.
 *
 * Une arrivée « à 20:38 ± 14 » pour un coucher à 20:46 n'est pas un oui : dans la moitié
 * défavorable des cas la nuit tombe avant l'arrivée. Ce que le coureur veut savoir, c'est
 * si même le cas défavorable passe — et sinon, à combien de l'arrivée la nuit le prend.
 */
class NightfallTest {

    private val now = 1_000_000_000_000L
    private fun sunsetIn(minutes: Int) = now + minutes * 60_000L
    private fun arrival(minutes: Double, margin: Double) = ArrivalEstimate(minutes * 60, margin * 60)

    @Test
    fun `oui quand meme le cas defavorable arrive avant le coucher`() {
        val a = Nightfall.assess(now, sunsetIn(60), arrival(40.0, 5.0), 10_000.0)
        assertEquals(Nightfall.Verdict.YES, a.verdict)
        assertEquals(20.0 * 60, a.marginSeconds, 1e-6)
        assertEquals(5.0 * 60, a.uncertaintySeconds, 1e-6)
        assertNull("rien à annoncer quand le pire passe", a.distanceLeftAtSunset)
    }

    @Test
    fun `juste quand le coucher tombe dans la fourchette`() {
        val a = Nightfall.assess(now, sunsetIn(60), arrival(52.0, 14.0), 10_000.0)
        assertEquals(Nightfall.Verdict.TIGHT, a.verdict)
        assertEquals(8.0 * 60, a.marginSeconds, 1e-6)
        // Au pire, l'arrivée est à 66 min : à la 60e, il reste 6/66 de la distance.
        assertEquals(10_000.0 * 6 / 66, a.distanceLeftAtSunset!!, 1e-6)
    }

    @Test
    fun `non quand meme le cas favorable arrive apres le coucher`() {
        val a = Nightfall.assess(now, sunsetIn(60), arrival(80.0, 5.0), 10_000.0)
        assertEquals(Nightfall.Verdict.NO, a.verdict)
        assertEquals(-20.0 * 60, a.marginSeconds, 1e-6)
        assertEquals(10_000.0 * 25 / 85, a.distanceLeftAtSunset!!, 1e-6)
    }

    @Test
    fun `un coucher deja passe laisse toute la distance dans la nuit`() {
        val a = Nightfall.assess(now, sunsetIn(-10), arrival(30.0, 5.0), 10_000.0)
        assertEquals(Nightfall.Verdict.NO, a.verdict)
        assertEquals(10_000.0, a.distanceLeftAtSunset!!, 1e-6)
    }

    @Test
    fun `sans incertitude le verdict se joue sur la moyenne`() {
        assertEquals(
            Nightfall.Verdict.YES,
            Nightfall.assess(now, sunsetIn(60), arrival(59.0, 0.0), 10_000.0).verdict,
        )
        assertEquals(
            Nightfall.Verdict.NO,
            Nightfall.assess(now, sunsetIn(60), arrival(61.0, 0.0), 10_000.0).verdict,
        )
    }
}
