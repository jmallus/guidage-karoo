package io.github.jmallus.guidage.extension

import io.github.jmallus.guidage.core.RideLevel
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ce que montre une case de bilan dans le sélecteur de champs du Karoo.
 *
 * Le simulateur ne le dit pas : il joue une sortie, jamais l'aperçu. Or c'est à l'aperçu
 * qu'on choisit une case, et il affichait « pas de cardiofréquencemètre » au lieu du champ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LevelPreviewTest {

    /**
     * Le relevé d'un Karoo au repos, sans capteur : ni vide ni exploitable.
     *
     * L'appareil y met toujours la FTP, la charge et des compteurs de zones à zéro. C'est ce
     * qui trompait l'ancienne condition, qui n'attendait l'aperçu que d'un bilan vide.
     */
    private val auRepos = RideData(
        level = RideLevel(
            heartRateZoneSeconds = listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            wPrimeBalance = 21_000.0,
            wPrimeCapacity = 21_000.0,
            criticalPower = 250.0,
            totalSeconds = 0.0,
            movingSeconds = 0.0,
            batteryPercent = 87.0,
        ),
    )

    @Test
    fun `chaque case montre un exemple en aperçu, même sur un appareil au repos`() {
        val context = RuntimeEnvironment.getApplication()
        for (variante in Bilan.entries) {
            val modele = LevelModels.build(context, variante, GuidanceSnapshot(), auRepos, preview = true)
            assertNull("${variante.typeId} : ${modele.emptyMessage}", modele.emptyMessage)
        }
    }
}
