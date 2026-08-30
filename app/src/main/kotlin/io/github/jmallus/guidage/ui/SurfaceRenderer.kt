package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Une entrée de légende : le nom d'une classe de sol et la teinte qui la porte. */
data class SurfaceLegendEntry(val label: String, val color: Int, val hatched: Boolean = false)

/** Une portion de la bande, avec ce qui s'écrit dessous. */
data class SurfaceBand(
    /** Part de la bande, entre 0 et 1. */
    val share: Float,
    val color: Int,
    /** Hachuré pour le chemin : la couleur seule ne suffit pas sur un écran délavé. */
    val hatched: Boolean = false,
    val label: String? = null,
)

/** Données prêtes à dessiner pour le champ « revêtement ». */
data class SurfaceFieldModel(
    val label: String? = null,
    /** La valeur en tête : la distance à la prochaine bascule. */
    val value: String? = null,
    /** Ce qui suit la bascule : « puis 2,1 km de chemin ». */
    val caption: String? = null,
    val bands: List<SurfaceBand> = emptyList(),
    /**
     * Les classes présentes, nommées.
     *
     * Elle n'est portée qu'en pleine page. Sur une bande, les couleurs se lisent à leur place
     * dans l'ordre du parcours et une légende volerait la moitié du champ ; sur une page,
     * rien ne dit qu'un brun est un chemin à qui ne l'a pas encore appris.
     */
    val legend: List<SurfaceLegendEntry> = emptyList(),
    val emptyMessage: String? = null,
)

/**
 * Dessine ce que l'itinéraire réserve comme revêtement.
 *
 * La bande porte les portions dans l'ordre où on les prendra, à l'échelle de leur longueur,
 * et le tracé de l'itinéraire est posé dessus au jaune que le Karoo lui réserve : on lit
 * ainsi d'un coup que ces portions sont celles qu'on va faire, et non un fond de carte
 * alentour.
 *
 * Le chemin est hachuré autant que coloré. L'écran transflectif du Karoo délave les teintes,
 * et deux aplats voisins de valeur proche se confondent au soleil ; la trame, elle, tient.
 */
object SurfaceRenderer {

    fun render(width: Int, height: Int, model: SurfaceFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (model.bands.isEmpty()) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }
        // Une page n'est pas une bande étirée, et les deux mises en page n'ont pas la même
        // vedette : sur une bande, la valeur est tout ce qu'on a la place de lire ; sur une
        // page, c'est le ruban qu'on vient regarder, et la valeur n'est que son titre.
        if (height > width * PAGE_RATIO) {
            drawPage(canvas, width, height, model, palette)
        } else {
            drawStrip(canvas, width, height, model, palette)
        }
        return bitmap
    }

    /**
     * La mise en page pleine page : titre, valeur, ruban, légende au pied.
     *
     * Les proportions sont écrites plutôt que déduites de la place restante. La règle
     * précédente donnait à la valeur tout ce que la bande ne prenait pas : sur 478 × 642
     * cela faisait un « 7 km » de deux cent vingt points, qui occupait le tiers de l'écran
     * pour dire un chiffre que le ruban montre déjà.
     */
    private fun drawPage(
        canvas: Canvas,
        width: Int,
        height: Int,
        model: SurfaceFieldModel,
        palette: Palette,
    ) {
        val padding = (width * PAGE_PADDING_FRACTION).coerceIn(8f, 24f)
        val left = padding
        val right = width - padding
        if (right <= left) return

        val labelSize = (width * PAGE_LABEL_FRACTION).coerceIn(11f, 30f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = padding + labelSize
        model.label?.let { canvas.drawText(it, left, y, label) }

        val value = model.value
        if (value != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textPrimary
                textSize = height * PAGE_VALUE_FRACTION
            }
            // La suite de la bascule se met sur la même ligne de pied, à droite : elle
            // complète la valeur plutôt qu'elle ne la commente, et lui donner une ligne à
            // elle repousserait le ruban d'autant.
            val captionWidth = model.caption?.let { label.measureText(it) + labelSize } ?: 0f
            val room = right - left - captionWidth
            if (room > 0f && paint.measureText(value) > room) {
                paint.textSize *= room / paint.measureText(value)
            }
            y += padding * 0.6f + paint.textSize
            canvas.drawText(value, left, y, paint)
            model.caption?.let { canvas.drawText(it, right - label.measureText(it), y, label) }
        } else {
            model.caption?.let {
                y += labelSize * 1.3f
                canvas.drawText(it, right - label.measureText(it), y, label)
            }
        }

        // La légende est posée au bas de la page, et non collée sous le ruban : accrochée au
        // ruban, elle laissait un tiers d'écran vide sous elle, et l'œil y cherchait ce qui
        // aurait dû s'y trouver.
        val rowHeight = labelSize * LEGEND_SWATCH_RATIO
        val step = labelSize * LEGEND_STEP_RATIO
        val legendHeight =
            if (model.legend.isEmpty()) 0f else (model.legend.size - 1) * step + rowHeight
        val legendTop = height - padding - legendHeight

        val bandLabelRoom = if (model.bands.any { it.label != null }) labelSize * 1.3f else 0f
        val libre = legendTop - padding - bandLabelRoom - (y + padding * 1.4f)
        val bandHeight = min(libre, height * PAGE_BAND_FRACTION)
        if (bandHeight > 0f) {
            // Le bloc du ruban est centré dans ce qui lui reste : plaqué sous la valeur, il
            // laissait tout le vide d'un seul côté, ce qui se lit comme un oubli.
            val bandTop = y + padding * 1.4f + (libre - bandHeight) / 2f
            drawBands(canvas, model, left, bandTop, right, bandTop + bandHeight, labelSize, palette)
        }
        // Sur un champ trop court pour tout porter, la légende cède : elle explique le ruban,
        // et un ruban recouvert n'a plus rien à faire expliquer.
        if (model.legend.isNotEmpty() && legendTop > y + padding) {
            drawLegend(canvas, model, left, legendTop, labelSize, palette)
        }
    }

    /** La mise en bande : le titre, la valeur à droite, et le ruban sur tout ce qui reste. */
    private fun drawStrip(
        canvas: Canvas,
        width: Int,
        height: Int,
        model: SurfaceFieldModel,
        palette: Palette,
    ) {
        val padding = (min(width, height) * 0.05f).coerceIn(3f, 10f)
        val left = padding
        val right = width - padding
        if (right <= left) return

        val labelSize = (height * 0.13f).coerceIn(9f, 18f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        var y = padding + labelSize
        model.label?.let { canvas.drawText(it, left, y, label) }

        model.value?.let { text ->
            val captionRoom = if (model.caption == null) 0f else labelSize * 1.3f
            val bandRoom = (height * 0.3f).coerceIn(14f, 90f)
            val largest = max(14f, height * 0.34f)
            val size = ((height - y - bandRoom - captionRoom - padding * 3) * 0.95f).coerceIn(12f, largest)
            val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textPrimary
                textSize = size
            }
            y += size
            canvas.drawText(text, right - value.measureText(text), y, value)
        }

        model.caption?.let {
            y += labelSize * 1.25f
            canvas.drawText(it, right - label.measureText(it), y, label)
        }

        val bandTop = y + padding * 2
        val labelRoom = if (model.bands.any { it.label != null }) labelSize * 1.3f else 0f
        val bandBottom = height - padding - labelRoom
        if (bandBottom > bandTop) {
            drawBands(canvas, model, left, bandTop, right, bandBottom, labelSize, palette)
        }
    }

    /**
     * La légende, au pied de la page : une pastille par classe de sol présente.
     *
     * Seules les classes que le parcours traverse y figurent. Une légende complète
     * apprendrait à reconnaître un revêtement qu'on ne rencontrera pas, ce qui est du bruit
     * sur un écran qu'on regarde en roulant.
     */
    private fun drawLegend(
        canvas: Canvas,
        model: SurfaceFieldModel,
        left: Float,
        top: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val swatchHeight = labelSize * LEGEND_SWATCH_RATIO
        val swatchWidth = labelSize * LEGEND_SWATCH_WIDTH_RATIO
        val step = labelSize * LEGEND_STEP_RATIO
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = labelSize * LEGEND_TEXT_RATIO
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (swatchHeight * 0.16f).coerceAtLeast(1.5f)
        }
        model.legend.forEachIndexed { index, entry ->
            val box = RectF(
                left,
                top + index * step,
                left + swatchWidth,
                top + index * step + swatchHeight,
            )
            if (box.bottom > canvas.height) return@forEachIndexed
            fill.color = entry.color
            canvas.drawRect(box, fill)
            if (entry.hatched) {
                // La trame est coupée au bord de la pastille — sans quoi elle en sortait et
                // venait toucher le mot qu'elle annonce — et tirée de la même encre que
                // celle du ruban : une légende d'une autre teinte n'apprend rien.
                canvas.save()
                canvas.clipRect(box)
                hatch.color = HATCH_INK
                var x = box.left - swatchHeight
                while (x < box.right) {
                    canvas.drawLine(x, box.bottom, x + swatchHeight, box.top, hatch)
                    x += swatchHeight * 0.5f
                }
                canvas.restore()
            }
            // La ligne de base est déduite des métriques du texte, non d'une fraction de la
            // hauteur de la pastille : celle-ci laissait le mot bas, d'autant plus bas que
            // le corps grandissait.
            val baseline = box.centerY() - (text.descent() + text.ascent()) / 2f
            canvas.drawText(entry.label, box.right + labelSize * 0.7f, baseline, text)
        }
    }

    private fun drawBands(
        canvas: Canvas,
        model: SurfaceFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val width = right - left
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (labelSize * 0.18f).coerceAtLeast(1.5f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize * 0.86f
        }

        var x = left
        model.bands.forEach { band ->
            val bandWidth = width * band.share
            if (bandWidth <= 0f) return@forEach
            val bandRight = min(x + bandWidth, right)
            fill.color = band.color
            canvas.drawRect(RectF(x, top, bandRight, bottom), fill)

            if (band.hatched) {
                canvas.save()
                canvas.clipRect(RectF(x, top, bandRight, bottom))
                hatch.color = HATCH_INK
                val spacing = (bottom - top) * 0.34f
                var start = x - (bottom - top)
                while (start < bandRight) {
                    canvas.drawLine(start, bottom, start + (bottom - top), top, hatch)
                    start += spacing
                }
                canvas.restore()
            }

            band.label?.let { caption ->
                val measured = text.measureText(caption)
                if (measured < bandWidth) {
                    canvas.drawText(
                        caption,
                        x + (bandWidth - measured) / 2f,
                        bottom + labelSize,
                        text,
                    )
                }
            }
            x = bandRight
        }

        // Le tracé, posé sur la bande : ce sont bien ces portions-là qu'on va faire.
        canvas.drawLine(
            left, (top + bottom) / 2f, right, (top + bottom) / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.routeLine
                strokeWidth = ((bottom - top) * 0.1f).coerceIn(2f, 5f)
            },
        )
        canvas.drawCircle(
            left, (top + bottom) / 2f, ((bottom - top) * 0.16f).coerceIn(3f, 8f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary },
        )
    }

    private fun drawEmpty(canvas: Canvas, width: Int, height: Int, message: String?, palette: Palette) {
        val text = message ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = (height * 0.22f).coerceIn(10f, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Le message est ramené à la largeur du champ. Ces phrases-là sont longues — elles
        // expliquent une absence — et débordaient d'un champ étroit, où elles se lisaient
        // tronquées sans que rien ne le signale. Mieux vaut l'écrire plus petit que le couper.
        val place = width * 0.92f
        val mesure = paint.measureText(text)
        if (mesure > place && place > 0f) paint.textSize *= place / mesure
        val x = (width - paint.measureText(text)) / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, max(x, 0f), y, paint)
    }

    /** Rapport hauteur/largeur au-delà duquel le champ se compose en page. */
    private const val PAGE_RATIO = 1.1f

    /** Marge de la page, en part de sa largeur. */
    private const val PAGE_PADDING_FRACTION = 0.04f

    /** Corps des libellés de la page, en part de sa largeur. */
    private const val PAGE_LABEL_FRACTION = 0.052f

    /**
     * Corps de la valeur en pleine page, en part de sa hauteur.
     *
     * Assez pour se lire d'un coup d'œil à bout de bras, pas plus : ce chiffre annonce la
     * bascule, il ne la montre pas — c'est le ruban qui la montre.
     */
    private const val PAGE_VALUE_FRACTION = 0.135f

    /** Hauteur maximale du ruban en pleine page, en part de celle du champ. */
    private const val PAGE_BAND_FRACTION = 0.28f

    /** Hauteur d'une pastille de légende, et pas d'une ligne à l'autre, en corps de libellé. */
    private const val LEGEND_SWATCH_RATIO = 1.15f
    private const val LEGEND_SWATCH_WIDTH_RATIO = 2.0f
    private const val LEGEND_STEP_RATIO = 1.9f

    /** Corps du mot d'une légende : un rien plus petit que le titre, qui est en capitales. */
    private const val LEGEND_TEXT_RATIO = 0.92f

    /** Encre de la trame du chemin : la teinte de la piste, assombrie. */
    private const val HATCH_INK = 0xFF6B4A22.toInt()
}
