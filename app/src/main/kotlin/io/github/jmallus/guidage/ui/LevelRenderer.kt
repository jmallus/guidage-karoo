package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min

/**
 * Ce qu'affiche une case de bilan.
 *
 * Un grand chiffre, un petit à côté, et de quoi les nommer. Le petit n'est pas une décoration :
 * c'est lui qui donne son sens au grand — une fréquence moyenne ne dit rien tant qu'on n'a pas
 * le maximum de la sortie à côté d'elle, et une puissance moyenne ne dit pas si l'heure fut
 * lisse ou hachée tant que la normalisée ne la borde pas.
 */
data class LevelFieldModel(
    /** Le libellé de la case, écrit en capitales. */
    val label: String,
    /** Le grand chiffre, ou « -- » quand la mesure manque. */
    val value: String,
    /** Son unité, écrite plus petit sur la même ligne de base. */
    val unit: String? = null,
    /** Le petit chiffre, et ce qui le nomme. */
    val referenceLabel: String? = null,
    val referenceValue: String? = null,
    /** Aplat de fond, quand il y a un verdict à porter. */
    val background: Int? = null,
    /** Message qui remplace tout quand rien n'est mesurable. */
    val emptyMessage: String? = null,
)

/**
 * Dessine une case de bilan : ce que la sortie vaut depuis le départ.
 *
 * La case suit la grammaire de Ride + Climb, dont elle prend la police : le libellé en haut à
 * gauche, le grand chiffre en Saira Condensed ExtraBold, et le second chiffre — celui qui
 * donne son sens au premier — dans une colonne étroite à sa droite. L'aplat de couleur, quand
 * la case en porte un, dit le verdict.
 *
 * Elle a longtemps porté davantage : une icône, un mot en bas, une barre. Composée pour une
 * case presque carrée que le Karoo ne donne jamais, elle se retrouvait sur l'appareil en
 * 238 × 126, où tout cela écrasait le chiffre au point de le rendre illisible en roulant. Il
 * ne reste que ce qui se lit d'un coup d'œil.
 */
object LevelRenderer {

    fun render(context: Context, width: Int, height: Int, model: LevelFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    fun draw(context: Context, canvas: Canvas, area: RectF, model: LevelFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        if (width <= 0f || height <= 0f) return

        // Les cotes de Ride + Climb, rapportées à la case : elles y sont calées pour 478 px de
        // large, et une case de bilan en fait la moitié.
        val k = min(width / LARGEUR_REF, height / HAUTEUR_REF).coerceAtLeast(0.5f)
        val insetX = INSET_X * k
        val left = area.left + insetX
        val right = area.right - insetX

        // L'aplat porte le verdict et décide des encres : le contraste est mesuré, jamais supposé.
        val encre = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textPrimary
        val encreLibelle = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textSecondary
        model.background?.let { canvas.drawRect(area, Paint().apply { color = it }) }

        val corpsLibelle = max(TAILLE_LIBELLE * k, Lisibilite.corpsPourCapitale())
        val libelle = paint(corpsLibelle, encreLibelle, LIBELLE)
        fit(libelle, model.label, right - left)
        val ligneLibelle = area.top + LIGNE_LIBELLE * k
        canvas.drawText(model.label, left, ligneLibelle, libelle)

        val haut = ligneLibelle + ESPACE_SOUS_LIBELLE * k
        val bas = area.bottom - INSET_Y * k
        if (bas <= haut) return

        model.emptyMessage?.let { message ->
            val p = paint(corpsLibelle * 1.2f, encreLibelle, LIBELLE)
            fit(p, message, right - left)
            canvas.drawText(message, left, (haut + bas) / 2f - (p.descent() + p.ascent()) / 2f, p)
            return
        }

        val saira = EdgeRenderer.chiffres(context)
        // L'encre d'un chiffre va du haut de la capitale au bas de la virgule : c'est elle qui
        // doit tenir entre le libellé et le bord, sans quoi la virgule mord sur la case d'en dessous.
        val mesure = paint(100f, 0, saira)
        mesure.getTextBounds(GABARIT, 0, GABARIT.length, bornes)
        val hautParPoint = -bornes.top / 100f
        val encreParPoint = (bornes.bottom - bornes.top) / 100f
        val place = bas - haut

        // La colonne de droite : le nom du second chiffre au-dessus, le chiffre en dessous.
        val reference = model.referenceValue
        val corpsReference = place * PART_REFERENCE / encreParPoint
        val refChiffre = paint(corpsReference, encre, saira)
        val refNom = paint(corpsLibelle, encreLibelle, LIBELLE)
        val largeurReference = if (reference == null) 0f else max(
            refChiffre.measureText(reference),
            model.referenceLabel?.let { refNom.measureText(it) } ?: 0f,
        )
        val ecart = if (reference == null) 0f else ECART * k

        // Le grand chiffre : le plus gros qui tienne dans la hauteur et dans ce que la colonne
        // de droite laisse de largeur, l'unité comprise.
        val unite = paint(corpsLibelle, encre, LIBELLE)
        val largeurUnite = model.unit?.let { unite.measureText(it) + UNIT_GAP * k } ?: 0f
        val largeurDispo = right - left - largeurReference - ecart
        val valeur = paint(place / encreParPoint, if (model.value == VIDE) encreLibelle else encre, saira)
        while (valeur.textSize > 8f && valeur.measureText(model.value) + largeurUnite > largeurDispo) valeur.textSize -= 1f
        val ligne = haut + (place - valeur.textSize * encreParPoint) / 2f + valeur.textSize * hautParPoint
        canvas.drawText(model.value, left, ligne, valeur)
        model.unit?.let { canvas.drawText(it, left + valeur.measureText(model.value) + UNIT_GAP * k, ligne, unite) }

        if (reference != null) {
            // Sur la ligne de base du grand chiffre, pour que les deux se lisent comme une paire ;
            // son nom juste au-dessus, comme le « MOY » de Ride + Climb.
            val x = right - largeurReference
            canvas.drawText(reference, x, ligne, refChiffre)
            model.referenceLabel?.let {
                fit(refNom, it, largeurReference)
                val ligneNom = ligne - refChiffre.textSize * hautParPoint - ESPACE_NOM * k
                canvas.drawText(it, x, max(ligneNom, haut - refNom.ascent()), refNom)
            }
        }
    }

    private val bornes = Rect()

    private fun paint(size: Float, color: Int, typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        this.typeface = typeface
    }

    /** Réduit le corps jusqu'à ce que le texte tienne, sans passer sous le plancher de lisibilité. */
    private fun fit(paint: Paint, text: String, maxWidth: Float) {
        while (paint.textSize > Lisibilite.corpsPourCapitale() && paint.measureText(text) > maxWidth) paint.textSize -= 0.5f
    }

    private val LIBELLE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** La case de Ride + Climb dont la case de bilan reprend les cotes : sa référence, en px. */
    private const val LARGEUR_REF = 238f
    private const val HAUTEUR_REF = 126f

    private const val INSET_X = 10f
    private const val INSET_Y = 6f
    private const val TAILLE_LIBELLE = 18f
    private const val LIGNE_LIBELLE = 22f
    private const val ESPACE_SOUS_LIBELLE = 6f
    private const val UNIT_GAP = 3f
    private const val ECART = 14f
    private const val ESPACE_NOM = 5f

    /** Hauteur d'encre du second chiffre, en part de la place sous le libellé. */
    private const val PART_REFERENCE = 0.42f

    /** De quoi mesurer l'encre d'un chiffre : la hauteur d'un chiffre, la descente d'une virgule. */
    private const val GABARIT = "0,"
    private const val VIDE = "--"
}
