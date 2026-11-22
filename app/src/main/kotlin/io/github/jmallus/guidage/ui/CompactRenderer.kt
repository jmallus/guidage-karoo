package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.max

/** Ce qu'affiche Ride compact : vitesse, puissance 3 s et fréquence cardiaque. */
data class CompactModel(
    /** Les cases mêmes de Ride + Climb. */
    val cases: List<EdgeCase>,
)

/**
 * Ride compact : une rangée de trois cases de Ride + Climb, sur toute la hauteur du champ.
 *
 * Il portait aussi le profil à venir, sur les trois cinquièmes du bas. Mais le Karoo impose la
 * taille des emplacements, et celui où le coureur le posait ne faisait que 478 × 192 : chiffres
 * et profil y étaient tous deux trop serrés. Le profil est donc rendu au champ « Profil à
 * venir », à poser dans l'emplacement voisin, et celui-ci garde les chiffres.
 *
 * Rien n'est redessiné ici : les cases viennent de [EdgeRenderer], qui écrit les trois chiffres
 * à un seul corps.
 */
object CompactRenderer {

    fun render(context: Context, width: Int, height: Int, model: CompactModel, sombre: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), width.toFloat(), height.toFloat(), model, sombre)
        return bitmap
    }

    fun draw(context: Context, canvas: Canvas, w: Float, h: Float, model: CompactModel, sombre: Boolean) {
        EdgeRenderer.fond(sombre)?.let { canvas.drawColor(it) }
        // L'échelle se règle sur la largeur, que le champ partage avec Ride + Climb.
        val k = w / LARGEUR_REF
        val pad = PAD * k
        EdgeRenderer.rangee(context, canvas, RectF(pad, pad, w - pad, h - pad), model.cases, sombre, k, filetDessous = false)
    }

    private const val LARGEUR_REF = 478f
    private const val PAD = 4f
}
