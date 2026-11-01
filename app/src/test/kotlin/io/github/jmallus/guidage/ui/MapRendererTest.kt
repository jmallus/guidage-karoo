package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSurface
import kotlin.math.abs
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
 * Les contrôles portent sur des choses qui se sont déjà perdues en chemin et qu'aucune
 * compilation ne rattraperait : un ruban qui s'éteint là où il devrait être plein, une marque
 * de position semée partout, des chevrons de la couleur du fond, une carte redevenue
 * monochrome.
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
     * Derrière le coureur, le ruban est d'une encre unie.
     *
     * Il y est plus clair que devant, et c'est voulu : on lit du premier coup d'œil ce qui est
     * fait. Ce qu'on refuse, c'est le **dégradé** qu'il a porté un temps, qui s'éteignait vers
     * le transparent au fil des mètres et laissait une branche fantôme le long de celle qui
     * compte. Un dégradé ne se voit pas dans un test qui compte des couleurs : on lit donc
     * quatre points échelonnés derrière le coureur, et l'on demande qu'ils soient identiques
     * entre eux et pleinement opaques.
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
     * Le triangle de position, à la place du coureur.
     *
     * Il est du jaune de l'itinéraire, comme sur la carte native : le contrôle le cherche donc
     * à sa largeur, bien plus grande que celle du ruban, autour de la ligne du coureur —
     * l'ordonnée 320. Plus haut, les chevrons débordent aussi du ruban, de leur jaune : ils
     * sont hors de la bande examinée.
     */
    @Test
    fun `la fleche de position se tient a la place du coureur`() {
        val image = rendre()

        var blancs = 0
        var plusHaut = HAUTEUR
        var plusBas = 0
        val largeurRuban = (0 until image.width).count { image.getPixel(it, 200) == KarooColors.LEMON_YELLOW }
        for ((index, pixel) in pixelsDe(image).withIndex()) {
            if (pixel != KarooColors.LEMON_YELLOW) continue
            // Seuls la colonne du coureur et le bas de la carte sont examinés : la rose des
            // vents est blanche elle aussi, et large, mais elle vit dans le coin haut droit.
            val x = index % image.width
            val y = index / image.width
            if (abs(x - MILIEU) > COLONNE || y < 270 || y > 365) continue
            // Le ruban passe aussi par là : seul compte ce qui le déborde, la largeur du triangle.
            if (abs(x - MILIEU) <= largeurRuban / 2 + 2) continue
            blancs++
            if (y < plusHaut) plusHaut = y
            if (y > plusBas) plusBas = y
        }

        assertTrue("le triangle ne couvre que $blancs pixels, des ordonnées $plusHaut à $plusBas", blancs > 50)
    }

    /**
     * La rose des vents rend le nord, dans le coin haut droit.
     *
     * La carte tourne avec le coureur : elle est lisible en roulant, mais on y perd
     * l'orientation absolue. L'aiguille la rend. Le contrôle la cherche à son rose, que rien
     * d'autre ne porte sur la carte.
     */
    @Test
    fun `la rose des vents se tient dans le coin haut droit`() {
        val image = rendre()

        var rouges = 0
        var ailleurs = 0
        for ((index, pixel) in pixelsDe(image).withIndex()) {
            if (pixel != AIGUILLE_NORD) continue
            val x = index % image.width
            val y = index / image.width
            if (x > image.width / 2 && y < image.height / 3) rouges++ else ailleurs++
        }

        assertTrue("l'aiguille du nord ne couvre que $rouges pixels", rouges > 20)
        assertEquals("du rose se pose ailleurs que dans la rose", 0, ailleurs)
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

        /** Demi-largeur de la colonne où l'on cherche la pastille de position. */
        const val COLONNE = 40

        /** En deçà, on est dans le ciel de la carte : la rose y règne, le coureur non. */
        const val MOITIE_BASSE = 200

        /**
         * Quatre points sur le ruban derrière le coureur, entre deux voisins encombrants.
         *
         * La place est comptée : la pastille de position déborde de quarante points en arrière
         * du coureur, l'échelle s'écrit tout en bas, et il ne reste qu'une vingtaine de points
         * entre les deux. C'est assez pour qu'un dégradé, s'il revenait, s'y voie.
         */
        val ARRIERE = listOf(366, 372, 378, 384)

        const val NOIR = 0xFF000000.toInt()

        /**
         * Le blanc de l'anneau et de la flèche, recopié depuis le rendu.
         *
         * Recopier une constante privée est le prix à payer pour lire le résultat en pixels
         * plutôt qu'en appels de fonction : si elle change là-bas sans changer ici, le
         * contrôle tombe — et c'est bien ce qu'on veut d'un contrôle d'aspect.
         */
        const val BLANC_FLECHE = 0xFFFFFFFF.toInt()

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

    /** Le rose de l'aiguille du nord, celui de la boussole native. */
    private val AIGUILLE_NORD = 0xFFE5566E.toInt()
}
