package io.github.jmallus.guidage.ui

import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/**
 * Ce qui se lit en roulant, exprimé en millimètres et non en pixels.
 *
 * Les rendus dimensionnaient leurs textes contre la place disponible, avec des planchers en
 * pixels — neuf, onze — choisis contre ce budget-là. L'œil, lui, ne lit pas des pixels : il
 * lit une hauteur physique, à bout de bras, sur un vélo qui bouge. Sur le Karoo 3, quinze
 * points valent un millimètre, si bien qu'un plancher à onze points laisse passer des chiffres
 * de sept dixièmes de millimètre — invisibles en sortie, parfaitement nets sur une capture
 * regardée de près, ce qui est exactement le piège.
 *
 * Ce n'est pas non plus le corps qui se lit, mais l'**encre** : une capitale occupe 0,71 fois
 * le corps, un bas-de-casse 0,52. « coucher » et « RESTANT » écrits au même corps ne
 * présentent pas la même hauteur à l'œil, à un tiers près.
 *
 * D'où cet objet : un plancher unique, dit en hauteur d'encre, et la règle qui l'accompagne —
 * **sous le plancher, l'élément disparaît, il ne rétrécit pas**. Un texte trop petit pour être
 * lu n'est pas une information dégradée, c'est du bruit qui occupe la place d'autre chose.
 */
object Lisibilite {

    /** Points par millimètre, relevés sur le Karoo 3 : 480 points pour 31,3 mm de large. */
    const val POINTS_PAR_MM = 15.34f

    /** Part du corps qu'occupe une capitale, puis un bas-de-casse, dans la fonte du système. */
    const val CAPITALE = 0.71f
    const val BAS_DE_CASSE = 0.52f

    /**
     * Hauteur d'encre minimale d'un texte secondaire (mm).
     *
     * Le repère est le libellé des cases du tableau de bord — « RESTANT KM », que le Karoo
     * écrit en capitales et en graisse moyenne : sa capitale fait 0,83 mm, et c'est le plus
     * petit texte de l'écran que l'on lise sans s'arrêter.
     */
    const val ENCRE_MINIMALE_MM = 0.85f

    /** Le corps qu'il faut pour obtenir [encreMm] de capitale. */
    fun corpsPourCapitale(encreMm: Float = ENCRE_MINIMALE_MM): Float =
        encreMm * POINTS_PAR_MM / CAPITALE

    /** Le corps qu'il faut pour obtenir [encreMm] de hauteur d'x. */
    fun corpsPourBasDeCasse(encreMm: Float = ENCRE_MINIMALE_MM): Float =
        encreMm * POINTS_PAR_MM / BAS_DE_CASSE

    /**
     * La fonte des libellés secondaires : capitales et graisse moyenne, comme le Karoo.
     *
     * Ce n'est pas une préférence. À corps égal, passer du bas-de-casse en graisse normale aux
     * capitales en graisse moyenne rend trente-huit pour cent de hauteur d'encre, sans coûter
     * un pixel de place — c'est le seul gain gratuit de tout le réglage.
     */
    val LIBELLE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Le libellé tel qu'il se dessine : en capitales. */
    fun libelle(texte: String): String = texte.uppercase(Locale.getDefault())

    /** Un pinceau de libellé secondaire, au corps demandé. */
    fun pinceau(corps: Float, couleur: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur
        textSize = corps
        typeface = LIBELLE
    }
}
