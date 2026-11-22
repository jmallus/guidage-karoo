package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.max

/** Ce qu'affiche Ride + Climb compact : trois cases, puis le profil à venir. */
data class CompactModel(
    /** Vitesse, puissance 3 s, fréquence cardiaque : les cases mêmes de Ride + Climb. */
    val cases: List<EdgeCase>,
    /** Le profil du champ « Profil à venir », à l'identique. */
    val profil: ProfileFieldModel,
)

/**
 * Ride + Climb compact : une rangée de trois cases de Ride + Climb, et le profil à venir dessous.
 *
 * Fait pour un champ de 478 × 300, qui laisse le reste de la page à d'autres champs. Les
 * chiffres prennent les deux cinquièmes de la hauteur, le profil le reste : à moins, ses
 * côtes se lisaient mal. Rien n'est redessiné ici — les cases viennent de [EdgeRenderer], le
 * profil de [ProfileRenderer] — si bien que le champ ne peut pas dériver des deux autres.
 */
object CompactRenderer {

    fun render(context: Context, width: Int, height: Int, model: CompactModel, sombre: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), width.toFloat(), height.toFloat(), model, sombre)
        return bitmap
    }

    fun draw(context: Context, canvas: Canvas, w: Float, h: Float, model: CompactModel, sombre: Boolean) {
        EdgeRenderer.fond(sombre)?.let { canvas.drawColor(it) }
        // L'échelle se règle sur la largeur, que le champ partage avec Ride + Climb : sur sa
        // hauteur, trois fois moindre, les libellés seraient tombés sous le lisible.
        val k = w / LARGEUR_REF
        val pad = PAD * k
        val hauteurCases = (h - 2 * pad) * PART_CASES
        val cases = RectF(pad, pad, w - pad, pad + hauteurCases)
        EdgeRenderer.rangee(context, canvas, cases, model.cases, sombre, k)
        val hautProfil = cases.bottom + EdgeRenderer.espace(k)
        if (h - pad > hautProfil) {
            ProfileRenderer.draw(canvas, RectF(0f, hautProfil, w, h - pad), model.profil, FieldPalette.of(sombre))
        }
    }

    private const val LARGEUR_REF = 478f
    private const val PAD = 4f

    /** Part de la hauteur laissée aux chiffres ; le profil prend le reste. */
    private const val PART_CASES = 0.4f
}
