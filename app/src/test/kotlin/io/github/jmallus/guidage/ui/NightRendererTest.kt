package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Color
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

    /** La hauteur que le dernier rang du tableau de bord laisse à la bande, sur un Karoo 3. */
    private val hauteurBande = 128

    /** Une bande courte, où la frise ne tient plus à une taille lisible. */
    private val hauteurBandeCourte = 87

    private val timeline = NightTimeline(
        nowLabel = "maintenant",
        arrivalFraction = 0.62f,
        arrivalSpread = 0.1f,
        arrivalLabel = "arrivée 20:38 ± 14",
        arrivalShortLabel = "arrivée 20:38",
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

    /**
     * L'empreinte d'une couleur : les pixels qu'elle a touchés, quelle que soit leur opacité.
     *
     * On comptait les pixels strictement égaux à la couleur, c'est-à-dire les seuls entièrement
     * opaques. Sur du texte lissé, ce sont les rares pixels intérieurs d'un jambage : leur
     * nombre ne suit pas la taille du texte, il saute. Un contrôle qui comparait deux tailles
     * par ce compte tombait à pile ou face — et il est tombé. Les champs arrivent transparents,
     * si bien qu'un pixel de bord porte la couleur du texte avec une opacité partielle : compter
     * la couleur en ignorant l'opacité donne l'aire de l'encre, qui, elle, suit la taille.
     */
    private fun count(image: Bitmap, color: Int): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            if (Color.alpha(pixel) >= OPACITE_MINIMALE && (pixel and RVB) == (color and RVB)) n++
        }
        return n
    }

    /** En deçà, le pixel est une trace de lissage plutôt que de l'encre. */
    private val OPACITE_MINIMALE = 40

    private val RVB = 0x00FFFFFF

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

    /** Une bande dessinée à la taille voulue, sur toute la largeur du pied. */
    private fun bande(model: NightFieldModel, hauteur: Int, largeur: Int = 466): Bitmap {
        val image = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        NightRenderer.drawBand(
            android.graphics.Canvas(image),
            android.graphics.RectF(0f, 0f, largeur.toFloat(), hauteur.toFloat()),
            model,
            palette,
        )
        return image
    }

    /** L'empreinte d'une couleur dans les [part] premiers centièmes de la hauteur. */
    private fun countTop(image: Bitmap, color: Int, part: Double): Int {
        var n = 0
        for (y in 0 until (image.height * part).toInt()) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (Color.alpha(pixel) >= OPACITE_MINIMALE && (pixel and RVB) == (color and RVB)) n++
            }
        }
        return n
    }

    /**
     * La bande du tableau de bord porte la frise dès qu'elle peut l'écrire lisiblement.
     *
     * Le verdict est compté dans le seul rang du haut, et non sur l'image entière : la frise
     * porte elle aussi la couleur du verdict — le trait d'arrivée et son heure — et un total
     * ne dirait plus si le mot est là.
     */
    @Test
    fun `la bande porte le mot et la frise`() {
        val image = bande(model(NightVerdict.TIGHT, "JUSTE"), hauteur = hauteurBande)
        assertTrue("le verdict manque", countTop(image, NightRenderer.TIGHT, 0.30) > 150)
        assertTrue("la frise manque", count(image, KarooColors.LEMON_YELLOW) > 20)
    }

    /**
     * Une bande trop courte pour la frise écrit les deux heures en toutes lettres.
     *
     * C'est la règle du plancher appliquée à un dessin plutôt qu'à un texte : une frise qu'on
     * ne lirait pas ne dit pas moins bien ce qu'elle montre, elle ne dit rien, et prend la
     * place de ce qui parlerait. Le rail jaune et le triangle blanc en sont les deux marques.
     */
    @Test
    fun `une bande courte abandonne la frise pour les heures`() {
        val image = bande(model(NightVerdict.TIGHT, "JUSTE"), hauteur = hauteurBandeCourte)
        assertEquals("un rail de frise subsiste", 0, count(image, KarooColors.LEMON_YELLOW))
        assertEquals("le triangle du présent subsiste", 0, count(image, palette.textPrimary))
        assertTrue("la ligne des heures manque", count(image, palette.textSecondary) > 200)
    }

    /** La hauteur d'encre d'une couleur, en pixels : de sa première à sa dernière rangée. */
    private fun hauteurEncre(image: Bitmap, color: Int): Int {
        var haut = -1
        var bas = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (Color.alpha(pixel) >= OPACITE_MINIMALE && (pixel and RVB) == (color and RVB)) {
                    if (haut < 0) haut = y
                    bas = y
                    break
                }
            }
        }
        return if (haut < 0) 0 else bas - haut + 1
    }

    /**
     * La ligne des heures respecte le plancher de lisibilité, et le suit quand il monte.
     *
     * C'est le contrôle qui porte tout le réglage. Les textes se dimensionnaient contre la
     * place disponible, avec des planchers en pixels : sur un écran de quinze points au
     * millimètre, ils laissaient passer des heures d'un demi-millimètre d'encre, nettes sur
     * une capture regardée de près et invisibles sur un vélo. Le corps se déduit maintenant
     * d'une hauteur d'encre voulue, et ce contrôle la mesure là où elle se lit : sur les
     * pixels. La bande courte s'y prête, la frise n'y tenant à aucun des deux planchers.
     */
    @Test
    fun `la ligne des heures tient le plancher de lisibilite`() {
        val modele = model(NightVerdict.TIGHT, "JUSTE")
        for ((mm, minimum) in listOf(0.85f to 11, 1.15f to 15)) {
            val image = Bitmap.createBitmap(466, hauteurBandeCourte, Bitmap.Config.ARGB_8888)
            NightRenderer.drawBand(
                android.graphics.Canvas(image),
                android.graphics.RectF(0f, 0f, 466f, hauteurBandeCourte.toFloat()),
                modele,
                palette,
                mm,
            )
            val encre = hauteurEncre(image, palette.textSecondary)
            assertTrue("au plancher de $mm mm, les heures ne font que $encre px d'encre", encre >= minimum)
        }
    }

    @Test
    fun `une surface nulle ne casse rien`() {
        val image = image(model(NightVerdict.YES, "OUI"), largeur = 0, hauteur = 0)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }
}
