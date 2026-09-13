package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Color
import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RoutePoi
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

    /**
     * Les teintes de la silhouette : l'aplat voilé et la crête franche, au jaune du Karoo.
     *
     * Les relever sert à ne lire que la silhouette. Un test qui prendrait tout pixel non
     * transparent lirait aussi les libellés du haut et les graduations du bas — et le premier
     * écrit du texte plus haut que le col ne montera jamais, de sorte qu'un contrôle sur le
     * point culminant passerait en désignant une lettre.
     *
     * Elles se reconnaissent à leur teinte, non à leur valeur exacte : l'aplat est voilé, et un
     * pixel translucide posé sur un fond vide ne rend pas ses composantes au pixel près une
     * fois relu ; la crête est adoucie, et sur une pente son cœur même est un mélange. Rien
     * d'autre sur la bande n'est jaune — les libellés sont bleus ou blancs, les jalons magenta.
     */
    private fun estJaune(pixel: Int): Boolean =
        Color.alpha(pixel) > 0x40 && Color.red(pixel) > 0xB0 && Color.green(pixel) > 0x90 && Color.blue(pixel) < 0x60

    /** Hauteur du sommet de la silhouette dans chaque colonne, ou la hauteur si elle est vide. */
    private fun crete(image: Bitmap): List<Int> =
        (0 until image.width).map { x ->
            (0 until image.height).firstOrNull { y -> estJaune(image.getPixel(x, y)) } ?: image.height
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
     * La silhouette est d'un seul jaune, celui de l'itinéraire du Karoo : aucune teinte de
     * pente ne s'y glisse plus.
     */
    @Test
    fun `la bande ne porte aucune teinte de pente`() {
        val image = image()
        val pentes = (-25..25).map { FieldPalette.gradeColor(it.toDouble()) }.filterNot(::estJaune).toSet()
        val intruses = buildSet {
            for (x in 0 until image.width) {
                for (y in 0 until image.height) {
                    val pixel = image.getPixel(x, y)
                    if (pixel in pentes) add(pixel)
                }
            }
        }
        assertTrue("teintes de pente trouvées : $intruses", intruses.isEmpty())
        val jaune = crete(image).count { it < image.height }
        assertTrue("la silhouette jaune manque : $jaune colonnes", jaune > image.width / 2)
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
        val sommets = crete(image)
        // Les marges gauche et droite ne portent rien : le contrôle porte sur ce qui est
        // entre la première et la dernière colonne peintes, où aucun trou n'est admis.
        val premiere = sommets.indexOfFirst { it < image.height }
        val derniere = sommets.indexOfLast { it < image.height }
        assertTrue("rien n'est dessiné", premiere >= 0 && derniere - premiere > image.width / 2)

        val vides = (premiere..derniere).count { sommets[it] >= image.height }
        assertTrue("$vides colonnes sans relief entre $premiere et $derniere", vides == 0)
    }

    /** Un champ très court perd les chiffres de l'axe, mais jamais le dessin. */
    @Test
    fun `un champ court se dessine encore`() {
        val image = image(largeur = 200, hauteur = 48)
        val peintes = crete(image).count { it < image.height }
        assertTrue("seulement $peintes colonnes peintes", peintes > image.width / 2)
    }

    /**
     * Un point d'intérêt pose un jalon, et à sa distance.
     *
     * Le contrôle porte sur la teinte exacte : la marque est pleine et opaque, donc son
     * cœur porte le magenta sans mélange. Vérifier seulement qu'« il y a des pixels » ne
     * dirait pas si le jalon est au bon endroit — et l'échelle comprimée est précisément ce
     * qui rend ce placement facile à manquer.
     */
    @Test
    fun `un point d'interet pose un jalon a sa distance`() {
        val image = ProfileRenderer.render(
            320,
            140,
            ProfileFieldModel(
                window = Guidance.profileToFinish(route, 0.0),
                pois = listOf(RoutePoi("eau", "Fontaine", "water", 12_000.0)),
            ),
            palette,
        )
        val colonnes = (0 until image.width).filter { x ->
            (0 until image.height).any { y -> image.getPixel(x, y) == FieldPalette.POI }
        }
        assertTrue("aucun jalon dessiné", colonnes.isNotEmpty())

        val attendu = FisheyeScale(40_000.0).fractionAt(12_000.0) * image.width
        assertTrue(
            "jalon en $colonnes, attendu vers $attendu",
            colonnes.any { abs(it - attendu) < image.width * 0.10 },
        )
    }

    /** Et sans point d'intérêt, aucun pixel de cette teinte : le jalon ne s'invente pas. */
    @Test
    fun `sans point d'interet aucun jalon`() {
        val image = image()
        val magenta = (0 until image.width).sumOf { x ->
            (0 until image.height).count { y -> image.getPixel(x, y) == FieldPalette.POI }
        }
        assertTrue("$magenta pixels de jalon alors qu'aucun point n'est fourni", magenta == 0)
    }

    /**
     * Deux points d'intérêt voisins dans le lointain ne donnent qu'un jalon.
     *
     * Là où l'échelle comprime, plusieurs points tombent dans la même colonne : les dessiner
     * tous produirait une tache dont on ne saurait ni combien ils sont, ni où.
     */
    @Test
    fun `deux points confondus au loin ne donnent qu'un jalon`() {
        fun jalons(pois: List<RoutePoi>): Int {
            val image = ProfileRenderer.render(
                320,
                140,
                ProfileFieldModel(window = Guidance.profileToFinish(route, 0.0), pois = pois),
                palette,
            )
            // Un jalon est une plage continue de colonnes portant la teinte ; on compte les
            // plages, et non les colonnes, pour ne pas confondre largeur et nombre.
            val portantes = (0 until image.width).map { x ->
                (0 until image.height).any { y -> image.getPixel(x, y) == FieldPalette.POI }
            }
            return portantes.filterIndexed { i, ici -> ici && (i == 0 || !portantes[i - 1]) }.size
        }

        val loin = listOf(
            RoutePoi("a", null, "water", 39_000.0),
            RoutePoi("b", null, "water", 39_050.0),
        )
        assertTrue("les deux points lointains devraient se fondre", jalons(loin) == 1)

        val separes = listOf(
            RoutePoi("a", null, "water", 1_000.0),
            RoutePoi("b", null, "water", 12_000.0),
        )
        assertTrue("deux points bien séparés doivent rester deux", jalons(separes) == 2)
    }

    /**
     * Deux ravitaillements proches mais distincts restent deux.
     *
     * C'est le défaut qui a mordu en grossissant la marque : l'écart de fusion était indexé
     * sur sa demi-largeur, si bien que la grossir a porté le seuil à vingt-six pixels et
     * effacé le second de deux points séparés de 1,4 km — donc de seize pixels, parfaitement
     * distincts.
     *
     * Le contrôle ne compte pas les plages contiguës : à cette taille les deux marques se
     * touchent et n'en formeraient qu'une seule. Il regarde **jusqu'où va la teinte** : un
     * jalon posé au premier point ne peut pas atteindre l'abscisse du second, si bien qu'une
     * couleur trouvée là prouve qu'il y en a bien deux.
     */
    @Test
    fun `deux ravitaillements proches restent deux`() {
        val image = ProfileRenderer.render(
            320,
            140,
            ProfileFieldModel(
                window = Guidance.profileToFinish(route, 0.0),
                pois = listOf(
                    RoutePoi("a", null, "water", 4_200.0),
                    RoutePoi("b", null, "convenience_store", 5_600.0),
                ),
            ),
            palette,
        )
        val derniere = (0 until image.width).lastOrNull { x ->
            (0 until image.height).any { y -> image.getPixel(x, y) == FieldPalette.POI }
        }
        assertTrue("aucun jalon dessiné", derniere != null)

        val second = FisheyeScale(40_000.0).fractionAt(5_600.0) * image.width
        assertTrue(
            "la teinte s'arrête en $derniere ; le second jalon, vers $second, manque",
            derniere!! >= second - 2,
        )
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
