package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FisheyeScaleTest {

    @Test
    fun `les deux bouts tombent sur les deux bords`() {
        val echelle = FisheyeScale(span = 41_200.0)
        assertEquals(0.0, echelle.fractionAt(0.0), 1e-9)
        assertEquals(1.0, echelle.fractionAt(41_200.0), 1e-9)
    }

    @Test
    fun `la projection ne recule jamais`() {
        val echelle = FisheyeScale(span = 41_200.0)
        var precedent = -1.0
        var distance = 0.0
        while (distance <= 41_200.0) {
            val part = echelle.fractionAt(distance)
            assertTrue("recul à $distance m", part >= precedent)
            precedent = part
            distance += 25.0
        }
    }

    @Test
    fun `les distances hors parcours sont ramenées aux bords`() {
        val echelle = FisheyeScale(span = 10_000.0)
        assertEquals(0.0, echelle.fractionAt(-500.0), 1e-9)
        assertEquals(1.0, echelle.fractionAt(99_000.0), 1e-9)
    }

    @Test
    fun `aller et retour redonnent la distance`() {
        val echelle = FisheyeScale(span = 41_200.0)
        listOf(0.0, 150.0, 900.0, 2_000.0, 12_500.0, 41_200.0).forEach { distance ->
            val retour = echelle.distanceAt(echelle.fractionAt(distance))
            assertEquals(distance, retour, distance * 1e-6 + 1e-6)
        }
    }

    /**
     * Le point de tout l'exercice : voir la rampe et la journée sur la même bande.
     *
     * Une échelle proportionnelle donnerait aux deux premiers kilomètres cinq pour cent de la
     * largeur, où une rampe de trois cents mètres tient dans un pixel.
     */
    @Test
    fun `les deux premiers kilomètres prennent près de la moitié de la bande`() {
        val echelle = FisheyeScale(span = 41_200.0)
        val part = echelle.fractionAt(2_000.0)
        assertTrue("les deux premiers kilomètres n'occupent que $part", part > 0.4)
        assertTrue("ils occupent $part, il ne reste plus rien pour le lointain", part < 0.6)
    }

    @Test
    fun `le premier plan est cent fois plus fin que le lointain`() {
        val echelle = FisheyeScale(span = 41_200.0)
        val devant = echelle.fractionAt(100.0) - echelle.fractionAt(0.0)
        val loin = echelle.fractionAt(41_200.0) - echelle.fractionAt(41_100.0)
        assertTrue("rapport ${devant / loin}", devant / loin > 100.0)
    }

    @Test
    fun `le taux de compression se déduit du parcours restant`() {
        assertEquals(207.0, FisheyeScale(span = 41_200.0).compression, 1e-9)
        assertEquals(6.0, FisheyeScale(span = 1_000.0).compression, 1e-9)
    }

    @Test
    fun `un parcours fini ne se projette pas`() {
        val echelle = FisheyeScale(span = 0.0)
        assertTrue(!echelle.usable)
        assertEquals(0.0, echelle.fractionAt(500.0), 1e-9)
        assertEquals(0.0, echelle.distanceAt(0.5), 1e-9)
        assertTrue(echelle.ticks().isEmpty())
    }

    @Test
    fun `les graduations restent dans le parcours et dans l'ordre`() {
        val graduations = FisheyeScale(span = 41_200.0).ticks()
        assertTrue("aucune graduation", graduations.isNotEmpty())
        graduations.zipWithNext { avant, apres ->
            assertTrue(apres.distance > avant.distance)
            assertTrue(apres.fraction > avant.fraction)
        }
        graduations.forEach {
            assertTrue("${it.distance} m dépasse le parcours", it.distance < 41_200.0)
            assertTrue(it.fraction in 0.0..1.0)
        }
    }

    @Test
    fun `deux graduations ne se chevauchent jamais`() {
        val ecart = 0.14
        listOf(800.0, 5_000.0, 41_200.0, 180_000.0).forEach { parcours ->
            val graduations = FisheyeScale(span = parcours).ticks(minimumGap = ecart)
            var precedente = 0.0
            graduations.forEach {
                assertTrue(
                    "sur $parcours m, ${it.distance} m tombe trop près de la précédente",
                    it.fraction - precedente >= ecart,
                )
                precedente = it.fraction
            }
            assertTrue(
                "sur $parcours m, la dernière graduation touche le bord droit",
                graduations.all { 1.0 - it.fraction >= ecart },
            )
        }
    }

    /**
     * L'espacement inégal des graduations est ce qui rend la compression lisible : s'il
     * devenait régulier, la bande mentirait sans que rien ne le dise.
     */
    @Test
    fun `les graduations se resserrent vers l'arrivée`() {
        val graduations = FisheyeScale(span = 41_200.0).ticks()
        val ecarts = (listOf(0.0) + graduations.map { it.fraction })
            .zipWithNext { avant, apres -> apres - avant }
        assertTrue("moins de trois graduations", ecarts.size >= 3)
        assertTrue(
            "les graduations sont régulières : la compression ne se voit pas",
            ecarts.last() < ecarts.first(),
        )
    }

    @Test
    fun `un parcours court garde des graduations`() {
        val graduations = FisheyeScale(span = 900.0).ticks()
        assertTrue("un parcours de 900 m n'a plus de repère", graduations.isNotEmpty())
        assertTrue(graduations.all { it.distance < 900.0 })
    }

    /**
     * Les repères doivent être ronds dans l'unité qu'on lit. Gradué en mètres puis converti,
     * un axe impérial afficherait « 0,1 · 0,3 · 0,6 mi » : une conversion, pas une échelle.
     */
    @Test
    fun `les graduations sont rondes dans l'unité du coureur`() {
        val mille = 1_609.344
        val graduations = FisheyeScale(span = 40 * mille).ticks(unit = mille)
        assertTrue("aucune graduation", graduations.isNotEmpty())
        graduations.forEach {
            assertEquals(it.distance / mille, it.value, 1e-9)
            assertTrue(
                "${it.value} n'est pas une valeur ronde",
                it.value in listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0),
            )
        }
    }

    @Test
    fun `la valeur écrite est la distance comptée dans l'unité`() {
        val graduations = FisheyeScale(span = 41_200.0).ticks()
        graduations.forEach { assertEquals(it.distance / 1_000.0, it.value, 1e-9) }
    }
}
