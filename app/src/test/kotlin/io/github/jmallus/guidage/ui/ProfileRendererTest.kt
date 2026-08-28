package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ce que le profil œil de poisson doit montrer, vérifié sur les pixels qu'il produit.
 *
 * L'échelle comprimée se prête mal à la relecture de code : une erreur de sens dans la
 * projection, une colonne oubliée ou un relief lointain avalé se compilent tous parfaitement
 * et ne se voient que sur l'image. Les contrôles portent donc sur les deux promesses de la
 * vue — le premier plan est franc, et le lointain n'a pas disparu — et sur ce que la
 * compression ne doit pas coûter : une silhouette continue et des graduations irrégulières.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileRendererTest {

    private val palette get() = FieldPalette.of(RuntimeEnvironment.getApplication())

    /**
     * Un parcours de quarante kilomètres avec une rampe courte tout de suite et un col loin.
     *
     * Les deux échelles doivent être dans la donnée, sans quoi la projection n'a rien à
     * démontrer : c'est précisément leur cohabitation qui rendait le réglage de portée
     * impossible à régler.
     */
    private val route = Route(
        name = "Rampe et col",
        totalDistance = 40_000.0,
        profile = ElevationProfile(
            buildList {
                add(ProfilePoint(0.0, 100.0))
                add(ProfilePoint(300.0, 130.0))   // rampe courte, 10 %
                add(ProfilePoint(900.0, 120.0))
                add(ProfilePoint(20_000.0, 200.0))
                add(ProfilePoint(30_000.0, 900.0)) // le col
                add(ProfilePoint(40_000.0, 250.0))
            },
        ),
    )

    private fun image(largeur: Int = 320, hauteur: Int = 140): Bitmap = ProfileRenderer.render(
        largeur,
        hauteur,
        ProfileFieldModel(
            window = Guidance.profileToFinish(route, 0.0),
            rangeLabel = "40,0 km",
            ascentLabel = "+780 m",
        ),
        palette,
    )

    /** Colonnes où la silhouette monte au-dessus du fond, par ordre de gauche à droite. */
    private fun crete(image: Bitmap): List<Int> = (0 until image.width).map { x ->
        (0 until image.height).firstOrNull { y -> image.getPixel(x, y) != 0 } ?: image.height
    }

    /** La promesse : le col de la fin n'est pas avalé par la compression. */
    @Test
    fun `le col lointain reste le point haut de la bande`() {
        val image = image()
        val sommets = crete(image)
        val plusHaut = sommets.withIndex().filter { it.value < image.height }.minByOrNull { it.value }
        assertTrue("aucune silhouette dessinée", plusHaut != null)

        val attendu = FisheyeScale(40_000.0).fractionAt(30_000.0) * image.width
        assertTrue(
            "le sommet est en ${plusHaut!!.index}, attendu vers $attendu",
            abs(plusHaut.index - attendu) < image.width * 0.08,
        )
    }

    /**
     * Chaque colonne prend la couleur de la pente qu'elle couvre, et non celle d'une autre.
     *
     * C'est le défaut qu'a eu le rendu par segments : là où l'échelle comprime, cent segments
     * du relevé tombaient dans la même colonne et se repeignaient l'un l'autre, le lointain
     * finissant tout entier de la teinte du dernier segment tiré. Un profil d'une seule
     * couleur compile parfaitement.
     */
    @Test
    fun `la bande porte plusieurs teintes de pente`() {
        val teintes = teintesDeRemplissage(image())
        assertTrue("le profil n'a que ${teintes.size} teinte(s)", teintes.size >= 3)
    }

    /** Et quand le coureur refuse la coloration, une seule teinte : celle du neutre. */
    @Test
    fun `sans coloration par la pente la bande est d'une seule teinte`() {
        val image = ProfileRenderer.render(
            320,
            140,
            ProfileFieldModel(
                window = Guidance.profileToFinish(route, 0.0),
                colorByGrade = false,
            ),
            palette,
        )
        val teintes = teintesDeRemplissage(image)
        assertTrue("teintes trouvées : $teintes", teintes == setOf(FieldPalette.NEUTRAL))
    }

    /**
     * Les teintes du remplissage, relevées au ras du bas de la bande.
     *
     * En bas et non sur la crête : la silhouette y est soulignée d'un trait blanc, et lire
     * la couleur juste sous lui reviendrait à lire le trait.
     */
    private fun teintesDeRemplissage(image: Bitmap): Set<Int> {
        val sommets = crete(image)
        return (0 until image.width).mapNotNull { x ->
            if (sommets[x] >= image.height) return@mapNotNull null
            val bas = (0 until image.height).last { image.getPixel(x, it) != 0 }
            image.getPixel(x, bas).takeIf { it != palette.outline }
        }.toSet()
    }

    /**
     * Aucune colonne vide sous la silhouette.
     *
     * Le rendu dessinait autrefois un rectangle par segment du relevé ; là où l'échelle
     * comprime, cent segments tombaient dans la même colonne et les colonnes voisines
     * restaient vides — le profil lointain se criblait de trous. Passer aux colonnes a
     * précisément corrigé cela, et rien d'autre ne le vérifierait.
     */
    @Test
    fun `la silhouette est continue sur toute la largeur`() {
        val image = image()
        val vides = crete(image).drop(4).dropLast(4).count { it == image.height }
        assertTrue("$vides colonnes sans relief", vides == 0)
    }

    /** Un champ très court perd les chiffres de l'axe, mais jamais le dessin. */
    @Test
    fun `un champ court se dessine encore`() {
        val image = image(largeur = 200, hauteur = 48)
        val peints = (0 until image.width).count { x ->
            (0 until image.height).any { y -> image.getPixel(x, y) != 0 }
        }
        assertTrue("seulement $peints colonnes peintes", peints > image.width / 2)
    }

    /** Rien à montrer : un message, et pas une bande vide qu'on prendrait pour une panne. */
    @Test
    fun `sans profil le champ porte son message`() {
        val image = ProfileRenderer.render(
            320,
            140,
            ProfileFieldModel(
                window = Guidance.profileToFinish(route.copy(profile = null), 0.0),
                emptyMessage = "Pas d'itinéraire",
            ),
            palette,
        )
        val encre = (0 until image.width).sumOf { x ->
            (0 until image.height).count { y -> image.getPixel(x, y) != 0 }
        }
        assertTrue("le champ vide ne porte aucun texte", encre > 0)
    }
}
