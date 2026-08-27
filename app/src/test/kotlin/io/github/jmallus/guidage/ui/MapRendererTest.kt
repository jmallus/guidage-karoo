package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ce que la minicarte doit montrer, vérifié sur les pixels qu'elle produit.
 *
 * Les trois contrôles portent sur des choses qui se sont déjà perdues en chemin et qu'aucune
 * compilation ne rattraperait : un ruban qui s'éteint là où il devrait être plein, des
 * chevrons de la couleur du fond, une carte redevenue monochrome.
 *
 * Le rendu est appelé directement, sur une image à lui : c'est le seul moyen de savoir où
 * tombe le coureur — aux quatre cinquièmes de la hauteur — et donc de distinguer l'avant de
 * l'arrière à la lecture des pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapRendererTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /**
     * Le ruban est de la même encre devant et derrière.
     *
     * La part parcourue s'éteignait autrefois au fil des mètres. Un dégradé ne se voit pas
     * dans un test qui compte des couleurs : on lit donc quatre points échelonnés derrière le
     * coureur, et l'on demande qu'ils soient identiques et pleinement opaques.
     */
    @Test
    fun `le ruban ne s'eteint pas derriere le coureur`() {
        val image = rendre()

        val derriere = ARRIERE.map { y -> image.getPixel(MILIEU, y) }

        derriere.forEachIndexed { index, pixel ->
            assertEquals(
                "le point ${ARRIERE[index]} n'est pas opaque",
                255,
                Color.alpha(pixel),
            )
            assertEquals(
                "le ruban change de teinte à ${ARRIERE[index]} : " +
                    "${Integer.toHexString(pixel)} au lieu de ${Integer.toHexString(derriere.first())}",
                derriere.first(),
                pixel,
            )
        }
    }

    /**
     * Une marque blanche, et une seule, posée sur le coureur.
     *
     * Deux choses à tenir. Qu'elle existe — un double chevron plein se compte en centaines de
     * pixels, pas en dizaines. Et qu'elle soit **seule de sa couleur** : les jalons du sens
     * sont noirs, et si le blanc se retrouvait semé le long du tracé, on aurait à chercher sa
     * position parmi eux. Le contrôle borne donc l'encre blanche au voisinage du coureur, qui
     * se tient à l'ordonnée 320.
     */
    @Test
    fun `une seule marque blanche, sur le coureur`() {
        val image = rendre()

        var blancs = 0
        var plusHaut = HAUTEUR
        var plusBas = 0
        for ((index, pixel) in pixelsDe(image).withIndex()) {
            if (pixel != BLANC) continue
            blancs++
            val y = index / image.width
            if (y < plusHaut) plusHaut = y
            if (y > plusBas) plusBas = y
        }

        assertTrue("seulement $blancs pixels blancs sur la carte", blancs > 50)
        assertTrue(
            "du blanc traîne hors du coureur, entre les ordonnées $plusHaut et $plusBas",
            plusHaut >= 280 && plusBas <= 345,
        )
    }

    /**
     * Des chevrons noirs jalonnent ce qui reste à faire.
     *
     * Ils sont cherchés **au-dessus** de la marque de position : le coureur est à l'ordonnée
     * 320, le cap est au nord, donc ce qui est devant lui monte. Rien d'autre sur cette carte
     * n'est d'un noir pur — le fond est crème, ses encres sont ardoise, ses voies colorées —
     * de sorte que ces pixels-là ne peuvent venir que des jalons.
     */
    @Test
    fun `des chevrons noirs jalonnent la route devant`() {
        val image = rendre()

        var devant = 0
        for ((index, pixel) in pixelsDe(image).withIndex()) {
            if (pixel == NOIR && index / image.width < 280) devant++
        }

        assertTrue("seulement $devant pixels noirs devant le coureur", devant > 50)
    }

    /** Les pixels ARGB de l'image, rangés par lignes. */
    private fun pixelsDe(image: Bitmap): IntArray {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels
    }

    /**
     * Le fond est clair et ses voies portent chacune sa teinte.
     *
     * Le noir a été essayé, toutes voies blanches et le rang lu à la seule épaisseur. Deux
     * classes rendaient alors exactement la même couleur, et c'est ce que ce contrôle
     * interdit désormais.
     */
    @Test
    fun `le fond de carte est clair et ses classes se distinguent`() {
        val fond = RoadStyle.BACKGROUND

        assertTrue(
            "le fond n'est pas clair : ${Integer.toHexString(fond)}",
            Color.red(fond) > 200 && Color.green(fond) > 200 && Color.blue(fond) > 200,
        )

        val teintes = CLASSES.map { RoadStyle.color(it, RoadSurface.PAVED) }
        assertEquals("deux classes de voie partagent une teinte", CLASSES.size, teintes.toSet().size)

        assertNotEquals(
            "un chemin non revêtu se dessine comme une route",
            RoadStyle.color(RoadKind.TRACK, RoadSurface.PAVED),
            RoadStyle.color(RoadKind.TRACK, RoadSurface.UNPAVED),
        )
    }

    /** La carte, seule sur son image, coureur au milieu et cap au nord. */
    private fun rendre(): Bitmap {
        val image = Bitmap.createBitmap(LARGEUR, HAUTEUR, Bitmap.Config.ARGB_8888)
        val depart = GeoPoint(49.0, 0.0)
        val modele = MapModel(
            // Cent mètres derrière le coureur, trois cents devant, plein nord.
            path = listOf(-1, 0, 1, 2, 3).map { GeoPoint(depart.lat + it * PAS_DEGRES, 0.0) },
            position = depart,
            heading = 0.0,
            rangeMeters = 200.0,
            chevronRangeMeters = 300.0,
        )
        MapRenderer.draw(
            canvas = Canvas(image),
            area = RectF(0f, 0f, LARGEUR.toFloat(), HAUTEUR.toFloat()),
            model = modele,
            palette = FieldPalette.of(context),
        )
        return image
    }

    private companion object {
        const val LARGEUR = 240
        const val HAUTEUR = 400

        /** Le coureur est au milieu en largeur, aux quatre cinquièmes en hauteur : 320. */
        const val MILIEU = LARGEUR / 2

        /**
         * Quatre points sur le ruban derrière le coureur, entre deux voisins encombrants.
         *
         * Ils commencent sous la marque de position, dont les branches redescendent un peu en
         * arrière du coureur, et s'arrêtent avant l'échelle, qui s'écrit tout en bas.
         */
        val ARRIERE = listOf(340, 352, 364, 376)

        const val BLANC = 0xFFFFFFFF.toInt()
        const val NOIR = 0xFF000000.toInt()

        /** Cent mètres de latitude, à peu de chose près. */
        const val PAS_DEGRES = 0.000898

        /** Une classe par teinte attendue : deux qui se confondraient trahiraient un retour au blanc. */
        val CLASSES = listOf(
            RoadKind.MOTORWAY,
            RoadKind.PRIMARY,
            RoadKind.SECONDARY,
            RoadKind.RESIDENTIAL,
            RoadKind.SERVICE,
            RoadKind.CYCLEWAY,
            RoadKind.FOOTWAY,
        )
    }
}
