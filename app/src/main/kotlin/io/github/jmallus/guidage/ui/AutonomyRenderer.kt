package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

/** Les deux moitiés de la page « Autonomie », chacune dans le modèle de son champ. */
data class AutonomyFieldModel(
    val resupply: ResupplyFieldModel = ResupplyFieldModel(),
    val effort: EffortFieldModel = EffortFieldModel(),
)

/**
 * Les deux réserves qui s'épuisent, sur une seule page : l'eau en haut, le sucre en bas.
 *
 * Ce n'est pas une commodité de mise en page. Les deux questions d'une longue sortie — après
 * quel point n'y a-t-il plus à boire, et que coûte encore ce qui reste — ne se décident pas
 * séparément : on ne s'arrête qu'une fois, et c'est en voyant les deux ensemble qu'on sait
 * s'il faut s'arrêter à ce point-ci ou tenir jusqu'au suivant. Sur deux pages, il faudrait
 * les rapprocher de mémoire, en roulant, ce que personne ne fait.
 *
 * La réserve est en haut parce qu'elle porte le bandeau d'alerte, et qu'une alerte se pose au
 * bord haut de l'écran. Le budget est dessous : ses lignes de détail se lisent au calme, et
 * elles sont ce qu'on abandonne en premier si la place manque.
 *
 * Rien n'est redessiné ici : les deux moitiés appellent le rendu de leur propre champ. Une
 * seconde écriture aurait dérivé de la première dès la retouche suivante — c'est exactement ce
 * qu'on reproche aux planches, et il n'y a pas de raison de le refaire à l'intérieur de l'APK.
 */
object AutonomyRenderer {

    fun render(width: Int, height: Int, model: AutonomyFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (width <= 0 || height <= 0) return bitmap

        val split = height * RESUPPLY_FRACTION
        ResupplyRenderer.draw(
            canvas,
            RectF(0f, 0f, width.toFloat(), split),
            model.resupply,
            palette,
        )

        // Un filet, et non un blanc : les deux moitiés portent chacune une ligne horizontale
        // — le rail des points en haut, la barre des postes en bas — et sans séparation on les
        // lit comme trois lignes d'un même graphique.
        canvas.drawLine(
            0f,
            split,
            width.toFloat(),
            split,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.outline
                alpha = RULE_ALPHA
                strokeWidth = 1f
            },
        )

        EffortRenderer.draw(
            canvas,
            RectF(0f, split, width.toFloat(), height.toFloat()),
            model.effort,
            palette,
        )
        return bitmap
    }

    /**
     * Part de la hauteur laissée à la réserve.
     *
     * Un peu plus de la moitié : sa ligne porte tout l'itinéraire et a besoin d'air au-dessus
     * et en dessous, faute de quoi le vide de droite se lit comme une marge. Le budget, lui,
     * se replie proprement — il abandonne ses lignes de détail, puis sa barre.
     */
    private const val RESUPPLY_FRACTION = 0.56f

    /** Opacité du filet de séparation : présent, jamais bavard. */
    private const val RULE_ALPHA = 0x59
}
