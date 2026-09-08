package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnduranceTest {

    /** Deux heures d'effort, la seconde avec un cœur plus haut pour la même puissance. */
    private fun deuxMoities(
        puissanceA: Double,
        coeurA: Double,
        puissanceB: Double,
        coeurB: Double,
        secondes: Long = 7_200L,
    ): DriftTracker {
        val suiveur = DriftTracker()
        var instant = 0L
        while (instant <= secondes * 1_000L) {
            val premiere = instant * 2 <= secondes * 1_000L
            suiveur.observe(
                instant,
                if (premiere) puissanceA else puissanceB,
                if (premiere) coeurA else coeurB,
            )
            instant += 5_000L
        }
        return suiveur
    }

    @Test
    fun `un coeur qui monte pour la meme puissance donne une derive positive`() {
        val derive = deuxMoities(200.0, 140.0, 200.0, 150.0).drift()!!

        // 200/140 = 1,4286 ; 200/150 = 1,3333 ; la perte vaut 1 - 140/150.
        assertEquals(1.0 - 140.0 / 150.0, derive.ratio, 1e-3)
        assertTrue("${derive.seconds} s mesurées", derive.seconds > 7_000.0)
    }

    /** Un coureur qui s'installe après l'échauffement dérive à l'envers, et c'est normal. */
    @Test
    fun `une seconde moitie plus efficace donne une derive negative`() {
        val derive = deuxMoities(200.0, 150.0, 200.0, 140.0).drift()!!

        assertTrue("${derive.ratio}", derive.ratio < 0.0)
    }

    @Test
    fun `une sortie regulière ne derive pas`() {
        assertEquals(0.0, deuxMoities(210.0, 145.0, 210.0, 145.0).drift()!!.ratio, 1e-9)
    }

    /** Sous une heure, l'échauffement seul produirait un chiffre spectaculaire et faux. */
    @Test
    fun `sous une heure d'effort la derive se tait`() {
        assertNull(deuxMoities(200.0, 140.0, 200.0, 150.0, secondes = 2_400L).drift())
    }

    /**
     * Un arrêt n'est pas un effort : le cœur y reste haut sans puissance, et l'imputer à la
     * seconde moitié inventerait une dérive là où il n'y a qu'une pause pipi.
     */
    @Test
    fun `un arret n'est pas verse`() {
        val suiveur = DriftTracker()
        suiveur.observe(0L, 200.0, 140.0)

        assertFalse(suiveur.observe(5_000L, 0.0, 150.0))
        assertFalse(suiveur.observe(10_000L, null, 150.0))
        assertFalse(suiveur.observe(15_000L, 200.0, null))
        assertEquals(0.0, suiveur.seconds, 1e-9)
    }

    /** Un trou dans les mesures n'est pas du temps d'effort. */
    @Test
    fun `un pas trop long est ecarte`() {
        val suiveur = DriftTracker()
        suiveur.observe(0L, 200.0, 140.0)

        assertFalse(suiveur.observe(60_000L, 200.0, 140.0))
        assertEquals(0.0, suiveur.seconds, 1e-9)
    }

    /**
     * Les seaux fusionnent au lieu de s'accumuler : la mémoire reste bornée sur une sortie
     * de plusieurs jours, et le verdict ne bouge pas pour autant.
     */
    @Test
    fun `une tres longue sortie garde sa derive sans garder ses relevés`() {
        val huitHeures = deuxMoities(200.0, 140.0, 200.0, 150.0, secondes = 28_800L)
        val derive = huitHeures.drift()!!

        assertEquals(1.0 - 140.0 / 150.0, derive.ratio, 5e-3)
        assertTrue("${derive.seconds} s mesurées", derive.seconds > 28_000.0)
    }

    @Test
    fun `la remise a zero efface tout`() {
        val suiveur = deuxMoities(200.0, 140.0, 200.0, 150.0)
        suiveur.reset()

        assertEquals(0.0, suiveur.seconds, 1e-9)
        assertNull(suiveur.drift())
    }

    /* ---------------------------------------------------------------- batterie */

    @Test
    fun `la decharge se mesure sur la fenetre entiere`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, 90.0)
        batterie.observe(1_800_000L, 84.0)

        assertEquals(84.0, batterie.percent!!, 1e-9)
        assertEquals(12.0, batterie.perHour!!, 1e-9)
    }

    /** Sur quelques minutes, un niveau publié par points entiers ne dit rien de sa pente. */
    @Test
    fun `une fenetre trop courte ou sans perte ne dit rien`() {
        val trop = BatteryDrain()
        trop.observe(0L, 90.0)
        trop.observe(60_000L, 89.0)
        assertNull(trop.perHour)

        val plate = BatteryDrain()
        plate.observe(0L, 90.0)
        plate.observe(3_600_000L, 90.0)
        assertNull(plate.perHour)
    }

    /** Une recharge en route remet la mesure à plat plutôt que de rendre une pente négative. */
    @Test
    fun `une recharge repart de la`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, 50.0)
        batterie.observe(1_800_000L, 44.0)
        batterie.observe(3_600_000L, 95.0)
        assertNull(batterie.perHour)

        batterie.observe(7_200_000L, 89.0)
        assertEquals(6.0, batterie.perHour!!, 1e-9)
    }

    @Test
    fun `un niveau hors bornes est ignore`() {
        val batterie = BatteryDrain()
        batterie.observe(0L, null)
        batterie.observe(1_000L, 140.0)

        assertNull(batterie.percent)
        assertNull(batterie.perHour)
    }
}
