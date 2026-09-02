package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ce que le champ « Avant la nuit » doit montrer, vérifié sur ses pixels.
 *
 * Le mot du verdict est la seule chose qui compte à trente à l'heure : il doit dominer
 * l'image, dans la couleur qui le dit. La frise doit porter le coucher — le trait jaune — même
 * quand aucune arrivée n'est encore connue. Et le champ ne doit rien casser sur une hauteur
 * de bande, où il ne peut montrer que le mot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NightRendererTest {

    private val palette get() = FieldPalette.of(RuntimeEnvironment.getApplication())

    private val timeline = NightTimeline(
        nowLabel = "maintenant",
        arrivalFraction = 0.62f,
        arrivalSpread = 0.1f,
        arrivalLabel = "arrivée 20:38 ± 14",
        sunsetFraction = 0.68f,
        sunsetLabel = "coucher 20:46",
        duskFraction = 0.86f,
        duskLabel = "nuit 21:20",
    )

    private fun model(verdict: NightVerdict, label: String) = NightFieldModel(
        title = "ARRIVÉE AVANT LA NUIT ?",
        verdict = verdict,
        verdictLabel = label,
        marginLabel = "8 min d'avance",
        uncertaintyLabel = "· estimation ± 14",
        timeline = timeline,
        worstCaseLabel = "AU PIRE, LA NUIT VOUS PREND",
        worstCaseValue = "à 1,8 km de l'arrivée",
        footnote = "Coucher calculé hors ligne pour 45,18° N · 5,72° E",
    )

    private fun image(model: NightFieldModel, largeur: Int = 478, hauteur: Int = 642): Bitmap =
        NightRenderer.render(largeur, hauteur, model, palette)

    private fun count(image: Bitmap, color: Int): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getPixel(x, y) == color) n++
        return n
    }

    @Test
    fun `le verdict domine dans sa couleur`() {
        val cases = listOf(
            NightVerdict.YES to "OUI",
            NightVerdict.TIGHT to "JUSTE",
            NightVerdict.NO to "NON",
        )
        for ((verdict, label) in cases) {
            val image = image(model(verdict, label))
            val accent = NightRenderer.verdictColor(verdict)
            val pixels = count(image, accent)
            assertTrue("$label : ${pixels} pixels de sa couleur, trop peu", pixels > 3_000)
            // Les deux autres couleurs de verdict n'ont rien à faire sur l'image.
            for (other in NightVerdict.values()) {
                if (other == verdict) continue
                assertEquals("$label porte la couleur de $other", 0, count(image, NightRenderer.verdictColor(other)))
            }
        }
    }

    @Test
    fun `la frise porte le coucher meme sans arrivee`() {
        val attente = NightFieldModel(
            title = "ARRIVÉE AVANT LA NUIT ?",
            timeline = timeline.copy(arrivalFraction = null, arrivalLabel = null, arrivalSpread = 0f),
            footnote = "Coucher calculé hors ligne pour 45,18° N · 5,72° E",
            emptyMessage = "Allure en cours d'apprentissage",
        )
        val image = image(attente)
        assertTrue("pas de trait jaune de coucher", count(image, KarooColors.LEMON_YELLOW) > 20)
        for (verdict in NightVerdict.values()) {
            assertEquals("un verdict s'affiche sans arrivée", 0, count(image, NightRenderer.verdictColor(verdict)))
        }
    }

    @Test
    fun `sans itineraire le champ ne dit que cela`() {
        val image = image(NightFieldModel(title = "ARRIVÉE AVANT LA NUIT ?", emptyMessage = "Pas d'itinéraire"))
        assertEquals("un coucher est dessiné sans itinéraire", 0, count(image, KarooColors.LEMON_YELLOW))
        assertTrue("le message n'est pas dessiné", count(image, palette.textSecondary) > 50)
    }

    @Test
    fun `sur une hauteur de bande le mot reste`() {
        val image = image(model(NightVerdict.TIGHT, "JUSTE"), hauteur = 160)
        assertTrue("le verdict a disparu", count(image, NightRenderer.TIGHT) > 500)
    }

    /** La bande du tableau de bord : le mot dans sa couleur, le coucher sur la frise. */
    @Test
    fun `la bande porte le mot et le coucher`() {
        val image = Bitmap.createBitmap(326, 87, Bitmap.Config.ARGB_8888)
        NightRenderer.drawBand(
            android.graphics.Canvas(image),
            android.graphics.RectF(0f, 0f, 326f, 87f),
            model(NightVerdict.TIGHT, "JUSTE"),
            palette,
        )
        assertTrue("le verdict manque", count(image, NightRenderer.TIGHT) > 300)
        assertTrue("le coucher manque", count(image, KarooColors.LEMON_YELLOW) > 10)
        // Le pied de page et le pire cas n'ont pas leur place dans une bande : leur texte
        // s'écrirait en teinte primaire, qui ne sert ici qu'au triangle du présent.
        assertTrue("du texte de pleine page s'est glissé dans la bande", count(image, palette.textPrimary) < 60)
    }

    @Test
    fun `une surface nulle ne casse rien`() {
        val image = image(model(NightVerdict.YES, "OUI"), largeur = 0, hauteur = 0)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }
}
