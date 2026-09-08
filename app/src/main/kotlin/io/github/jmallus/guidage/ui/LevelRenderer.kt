package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min

/** Une part de la barre des zones : sa couleur et ce qu'elle occupe, de 0 à 1. */
data class LevelSlice(val share: Float, val color: Int)

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
    /** La ligne du bas, quand la case a un mot à dire plutôt qu'un chiffre. */
    val caption: String? = null,
    /** La barre empilée, pour la répartition par zone. */
    val slices: List<LevelSlice> = emptyList(),
    /** Message qui remplace tout quand rien n'est mesurable. */
    val emptyMessage: String? = null,
)

/**
 * Dessine une case de bilan : ce que la sortie vaut depuis le départ.
 *
 * Les six cases partagent une seule grammaire, et c'est délibéré : posées côte à côte sur une
 * page, elles doivent se lire comme une famille et non comme six bricolages. Le libellé en
 * haut, le grand chiffre au milieu, la référence à sa droite, et le bas laissé au mot ou à la
 * barre quand il y en a un.
 *
 * Le Karoo publie déjà chacun de ces nombres, un par champ. Ce que cette case ajoute n'est pas
 * la mesure mais le **voisinage** : deux emplacements de page réunis en un, et la soustraction
 * faite pour le coureur plutôt que de tête.
 */
object LevelRenderer {

    fun render(width: Int, height: Int, model: LevelFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    fun draw(canvas: Canvas, area: RectF, model: LevelFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        if (width <= 0f || height <= 0f) return

        val padding = (min(width, height) * 0.06f).coerceIn(3f, 10f)
        val left = area.left + padding
        val right = area.right - padding

        // L'aplat porte le verdict et décide des encres : sur un fond saturé, le gris des
        // libellés disparaît. Le contraste est mesuré, jamais supposé.
        val encre = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textPrimary
        val encreDouce = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textSecondary
        model.background?.let { canvas.drawRect(area, Paint().apply { color = it }) }

        model.emptyMessage?.let { message ->
            val paint = paint(min(height * 0.20f, Lisibilite.corpsPourCapitale() * 1.6f), encreDouce, Lisibilite.LIBELLE)
            fit(paint, message, right - left)
            canvas.drawText(message, left, area.centerY() - (paint.descent() + paint.ascent()) / 2f, paint)
            return
        }

        // Le libellé ne descend jamais sous le plancher de lisibilité : à ce corps-là il est
        // écrit, ou il ne l'est pas du tout.
        val corpsLibelle = max(height * LABEL_FRACTION, Lisibilite.corpsPourCapitale())
        val libellePaint = paint(corpsLibelle, encreDouce, Lisibilite.LIBELLE)
        val hauteurLibelle = libellePaint.descent() - libellePaint.ascent()
        val libelleVisible = hauteurLibelle < height * 0.34f
        if (libelleVisible) {
            fit(libellePaint, model.label, right - left)
            canvas.drawText(model.label, left, area.top + padding - libellePaint.ascent(), libellePaint)
        }

        // Ce que le bas occupe, retiré avant de dimensionner le chiffre : le calculer après
        // reviendrait à dessiner un chiffre qui déborde, puis à s'en apercevoir à l'écran.
        val basHauteur = when {
            model.slices.isNotEmpty() -> height * BAR_FRACTION + (height * BAR_GAP_FRACTION)
            model.caption != null -> max(height * CAPTION_FRACTION, Lisibilite.corpsPourCapitale()) * 1.5f
            else -> 0f
        }
        val hautChiffre = area.top + padding + if (libelleVisible) hauteurLibelle else 0f
        val basChiffre = area.bottom - padding - basHauteur
        val placeChiffre = basChiffre - hautChiffre
        if (placeChiffre <= 0f) return

        // Le grand chiffre et sa référence forment un bloc unique, calé à gauche : les six
        // cases s'alignent alors sur le même bord quelle que soit la longueur des nombres.
        val corpsValeur = placeChiffre * VALUE_FRACTION
        val valeurPaint = paint(corpsValeur, encre, VALUE_TYPEFACE)
        val corpsUnite = corpsValeur * UNIT_RATIO
        val unitePaint = paint(corpsUnite, encre, VALUE_TYPEFACE)
        val corpsReference = max(corpsValeur * REFERENCE_RATIO, Lisibilite.corpsPourCapitale())
        val referencePaint = paint(corpsReference, encre, VALUE_TYPEFACE)
        val referenceLibellePaint = paint(corpsReference * 0.62f, encreDouce, Lisibilite.LIBELLE)

        val largeurValeur = valeurPaint.measureText(model.value)
        val largeurUnite = model.unit?.let { unitePaint.measureText(it) } ?: 0f
        val largeurReference = model.referenceValue?.let { referencePaint.measureText(it) } ?: 0f
        val largeurReferenceLibelle = model.referenceLabel?.let { referenceLibellePaint.measureText(it) } ?: 0f
        val ecart = corpsValeur * GAP_RATIO
        val bloc = largeurValeur + largeurUnite +
            if (model.referenceValue == null) 0f else ecart + max(largeurReference, largeurReferenceLibelle)

        // Trop large : tout le bloc rétrécit d'un coup, plutôt que le seul grand chiffre —
        // sans quoi la référence finirait par le dépasser, ce qui inverserait leurs rôles.
        val reduction = if (bloc > right - left && bloc > 0f) (right - left) / bloc else 1f
        val ligne = basChiffre - placeChiffre * BASELINE_ABOVE_BOTTOM
        valeurPaint.textSize = corpsValeur * reduction
        unitePaint.textSize = corpsUnite * reduction
        referencePaint.textSize = corpsReference * reduction
        referenceLibellePaint.textSize = corpsReference * 0.62f * reduction

        var x = left
        canvas.drawText(model.value, x, ligne, valeurPaint)
        x += valeurPaint.measureText(model.value)
        model.unit?.let {
            canvas.drawText(it, x, ligne, unitePaint)
            x += unitePaint.measureText(it)
        }

        model.referenceValue?.let { reference ->
            val depart = x + ecart * reduction
            // La référence s'écrit sous son libellé, tous deux calés à gauche du même bord :
            // c'est ce qui la donne à lire comme une glose du grand chiffre, non comme une
            // seconde valeur de même rang.
            model.referenceLabel?.let { titre ->
                canvas.drawText(titre, depart, ligne - referencePaint.textSize * 1.05f, referenceLibellePaint)
            }
            canvas.drawText(reference, depart, ligne, referencePaint)
        }

        model.caption?.let { texte ->
            val paint = paint(max(height * CAPTION_FRACTION, Lisibilite.corpsPourCapitale()), encreDouce, Lisibilite.LIBELLE)
            fit(paint, texte, right - left)
            canvas.drawText(texte, left, area.bottom - padding - paint.descent(), paint)
        }

        if (model.slices.isNotEmpty()) {
            drawBar(canvas, model.slices, left, area.bottom - padding - height * BAR_FRACTION, right, area.bottom - padding)
        }
    }

    /**
     * La barre des zones : une part par zone, dans l'ordre des zones et non de leur poids.
     *
     * L'ordre est ce qui la rend lisible d'un coup d'œil — le vert à gauche, le rouge à
     * droite — et c'est la **place** du gros de la barre qui dit le niveau de la sortie, plus
     * vite qu'aucun chiffre. Une part trop mince pour se voir n'est pas dessinée plutôt que
     * réduite à un pixel qui mentirait sur sa taille.
     */
    private fun drawBar(canvas: Canvas, slices: List<LevelSlice>, left: Float, top: Float, right: Float, bottom: Float) {
        val largeur = right - left
        if (largeur <= 0f || bottom <= top) return
        val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        var x = left
        slices.forEach { part ->
            val bout = x + largeur * part.share.coerceIn(0f, 1f)
            if (bout - x >= MIN_SLICE_PIXELS) {
                fond.color = part.color
                canvas.drawRect(x, top, bout, bottom, fond)
            }
            x = bout
        }
    }

    private fun paint(size: Float, color: Int, typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = typeface
    }

    private fun fit(paint: Paint, text: String, maxWidth: Float) {
        val mesure = paint.measureText(text)
        if (mesure > maxWidth && maxWidth > 0f) {
            paint.textSize = (paint.textSize * maxWidth / mesure).coerceAtLeast(8f)
        }
    }

    private val VALUE_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Part de la hauteur donnée au libellé du haut. */
    private const val LABEL_FRACTION = 0.16f

    /** Part de la hauteur restante donnée au grand chiffre, et où pose sa ligne de base. */
    private const val VALUE_FRACTION = 0.72f
    private const val BASELINE_ABOVE_BOTTOM = 0.14f

    /** Tailles de l'unité et de la référence, en part de celle du grand chiffre. */
    private const val UNIT_RATIO = 0.46f
    private const val REFERENCE_RATIO = 0.44f

    /** Blanc entre le grand chiffre et sa référence, en part du corps du premier. */
    private const val GAP_RATIO = 0.34f

    /** Part de la hauteur donnée à la ligne du bas, et à la barre des zones. */
    private const val CAPTION_FRACTION = 0.13f
    private const val BAR_FRACTION = 0.16f
    private const val BAR_GAP_FRACTION = 0.06f

    /** En deçà, une part de barre n'est pas dessinée : elle mentirait sur sa taille. */
    private const val MIN_SLICE_PIXELS = 2f
}
