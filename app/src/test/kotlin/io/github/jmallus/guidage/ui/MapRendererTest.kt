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
     * pixels, pas en dizaines. Et qu'elle soit **seule** : le tracé en était jalonné sur des
     * centaines de mètres, ce qui déposait du blanc jusqu'en haut du cadre. Le contrôle borne
     * donc l'encre blanche au voisinage du coureur, qui se tient à l'ordonnée 320.
     */
    @Test
    fun `une seule marque blanche, sur le coureur`() {
        val image = rendre()

        var blancs = 0
        var plusHaut = HAUTEUR
        var plusBas = 0
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        for (index in pixels.indices) {
            if (pixels[index] != BLANC) continue
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
