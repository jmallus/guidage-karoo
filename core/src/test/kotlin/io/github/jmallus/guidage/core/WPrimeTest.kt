package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WPrimeTest {

    /** Au-dessus de la CP, chaque watt d'excédent coûte un joule par seconde. */
    @Test
    fun `au-dessus de la puissance critique la reserve se vide de l'excedent`() {
        val apres = WPrime.step(
            balance = 20_000.0,
            capacity = 20_000.0,
            criticalPower = 250.0,
            power = 350.0,
            seconds = 10.0,
        )

        assertEquals(19_000.0, apres, 1e-9)
    }

    /**
     * En dessous, la recharge est d'autant plus vive que la réserve est vide : c'est ce qui
     * distingue ce modèle d'une simple soustraction, et le seul terme qui vaille la peine.
     */
    @Test
    fun `en dessous la recharge s'accelere quand la reserve est basse`() {
        val presqueVide = WPrime.step(2_000.0, 20_000.0, 250.0, 150.0, 1.0) - 2_000.0
        val presquePleine = WPrime.step(18_000.0, 20_000.0, 250.0, 150.0, 1.0) - 18_000.0

        assertTrue("$presqueVide contre $presquePleine", presqueVide > presquePleine * 5)
    }

    @Test
    fun `la reserve ne depasse ni le plein ni le vide`() {
        assertEquals(20_000.0, WPrime.step(19_990.0, 20_000.0, 250.0, 0.0, 60.0), 1e-9)
        assertEquals(0.0, WPrime.step(500.0, 20_000.0, 250.0, 900.0, 30.0), 1e-9)
    }

    @Test
    fun `la reserve par defaut suit le poids`() {
        assertEquals(21_000.0, WPrime.defaultCapacity(70.0), 1e-9)
    }

    /** Sans paramètres, le suiveur ne dit rien plutôt que d'inventer une échelle. */
    @Test
    fun `le suiveur se tait sans puissance critique ni reserve`() {
        val suiveur = WPrimeTracker()

        assertFalse(suiveur.observe(0L, powerWatts = 300.0, criticalPower = null, capacityJoules = 20_000.0))
        assertFalse(suiveur.observe(1_000L, powerWatts = 300.0, criticalPower = 250.0, capacityJoules = null))
        assertNull(suiveur.balance)
        assertNull(suiveur.size)
    }

    @Test
    fun `le suiveur part plein et se vide au fil des pas`() {
        val suiveur = WPrimeTracker()

        assertFalse(suiveur.observe(0L, 350.0, 250.0, 20_000.0))
        assertEquals(20_000.0, suiveur.balance!!, 1e-9)

        assertTrue(suiveur.observe(5_000L, 350.0, 250.0, 20_000.0))
        assertEquals(19_500.0, suiveur.balance!!, 1e-9)
        assertEquals(20_000.0, suiveur.size!!, 1e-9)
    }

    /**
     * Une puissance absente vaut zéro : le Karoo se tait quand on ne pédale plus, et c'est
     * le moment où la réserve se recharge le plus vite.
     */
    @Test
    fun `une puissance absente recharge la reserve`() {
        val suiveur = WPrimeTracker()
        suiveur.observe(0L, 400.0, 250.0, 20_000.0)
        suiveur.observe(10_000L, 400.0, 250.0, 20_000.0)
        val creuse = suiveur.balance!!

        assertTrue(suiveur.observe(18_000L, null, 250.0, 20_000.0))
        assertTrue("${suiveur.balance} contre $creuse", suiveur.balance!! > creuse)
    }

    /** Un trou dans les mesures n'est ni de l'effort ni de la récupération. */
    @Test
    fun `un pas trop long est ecarte`() {
        val suiveur = WPrimeTracker()
        suiveur.observe(0L, 400.0, 250.0, 20_000.0)

        assertFalse(suiveur.observe(60_000L, 400.0, 250.0, 20_000.0))
        assertEquals(20_000.0, suiveur.balance!!, 1e-9)
    }

    /** Changer l'échelle en roulant repart d'une réserve pleine, faute de mieux. */
    @Test
    fun `un changement de reserve remet le plein`() {
        val suiveur = WPrimeTracker()
        suiveur.observe(0L, 400.0, 250.0, 20_000.0)
        suiveur.observe(5_000L, 400.0, 250.0, 20_000.0)

        suiveur.observe(10_000L, 400.0, 250.0, 24_000.0)
        assertEquals(24_000.0, suiveur.balance!!, 1e-9)
    }

    @Test
    fun `la remise a zero refait le plein`() {
        val suiveur = WPrimeTracker()
        suiveur.observe(0L, 400.0, 250.0, 20_000.0)
        suiveur.observe(8_000L, 400.0, 250.0, 20_000.0)
        assertTrue(suiveur.balance!! < 20_000.0)

        suiveur.reset()
        assertEquals(20_000.0, suiveur.balance!!, 1e-9)
        assertFalse(suiveur.observe(9_000L, 400.0, 250.0, 20_000.0))
    }
}
