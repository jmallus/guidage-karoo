package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Un poste de dépense, prêt à dessiner. */
data class EffortSlice(
    /** Part du total, entre 0 et 1. */
    val share: Float,
    /** Couleur de la tranche : celle de la pente pour une côte, neutre pour le roulant. */
    val color: Int,
    /** Ce qui s'écrit dans la liste, ou null pour un poste qui n'y figure pas. */
    val label: String? = null,
    val value: String? = null,
)

/** Données prêtes à dessiner pour le champ « budget d'effort ». */
data class EffortFieldModel(
    /** Le total, sans unité : « 620 ». */
    val total: String? = null,
    /** L'unité, écrite plus petite à côté : « kJ ». */
    val unit: String = "kJ",
    val label: String? = null,
    val slices: List<EffortSlice> = emptyList(),
    val emptyMessage: String? = null,
)

/**
 * Dessine ce qu'il reste à payer : un total, une barre découpée par poste, puis le détail.
 *
 * La barre porte l'essentiel. Six cents kilojoules ne veulent rien dire seuls ; six cents
 * dont deux tiers dans deux côtes se lisent d'un coup d'œil, et c'est cette répartition qui
 * décide de ce qu'on fait maintenant.
 *
 * Le champ se plie à sa hauteur : très plat, il ne montre que le total ; un peu plus haut,
 * le total et la barre ; entier, le détail poste par poste. Rien n'est jamais tronqué à
 * mi-hauteur — une ligne coupée en deux est pire qu'une ligne absente.
 */
object EffortRenderer {

    fun render(width: Int, height: Int, model: EffortFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    /**
     * Dessine le champ dans une zone du canevas plutôt que dans un bitmap à lui.
     *
     * C'est ce qui permet à la page « Autonomie » de porter ce champ sous la réserve sans le
     * réécrire : une seconde écriture dériverait de celle-ci au premier réglage.
     */
    fun draw(canvas: Canvas, area: RectF, model: EffortFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        val padding = (min(width, height) * 0.05f).coerceIn(3f, 10f)
        val left = area.left + padding
        val right = area.right - padding
        val bottom = area.bottom - padding
        if (right <= left || height <= 0f) return

        val total = model.total
        if (total == null || model.slices.isEmpty()) {
            drawEmpty(canvas, area, model.emptyMessage, palette)
            return
        }

        val labelSize = (height * 0.13f).coerceIn(9f, 18f)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = area.top + padding + labelSize
        model.label?.let {
            canvas.drawText(it, left, y, labelPaint)
            y += labelSize * 0.4f
        }

        // Le total, aussi grand que la place le permet.
        val rows = detailRows(model)
        val barHeight = (height * 0.16f).coerceIn(6f, 26f)
        val rowHeight = labelSize * 1.5f
        val reserved = barHeight + padding + rows.size * rowHeight
        // Les bornes se croiseraient sur un champ très plat, et coerceIn lèverait :
        // la borne haute est donc calculée avant, jamais sous la borne basse.
        val largest = max(12f, height * 0.42f)
        val valueSize = ((bottom - y - reserved) * 0.9f).coerceIn(10f, largest)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = valueSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = valueSize * UNIT_RATIO
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val totalWidth = valuePaint.measureText(total) + unitPaint.measureText(model.unit) + valueSize * 0.1f
        val valueBaseline = y + valueSize
        val valueLeft = right - totalWidth
        canvas.drawText(total, valueLeft, valueBaseline, valuePaint)
        canvas.drawText(model.unit, valueLeft + valuePaint.measureText(total) + valueSize * 0.1f, valueBaseline, unitPaint)

        var cursor = valueBaseline + padding
        if (cursor + barHeight <= bottom) {
            drawBar(canvas, model, left, cursor, right, cursor + barHeight)
            cursor += barHeight + padding
        }

        rows.forEach { slice ->
            if (cursor + rowHeight > bottom) return@forEach
            drawRow(canvas, slice, left, right, cursor + labelSize, labelSize, palette)
            cursor += rowHeight
        }
    }

    /** Les postes qui méritent une ligne : ceux qui portent un libellé. */
    private fun detailRows(model: EffortFieldModel): List<EffortSlice> =
        model.slices.filter { it.label != null && it.value != null }

    private fun drawBar(
        canvas: Canvas,
        model: EffortFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val width = right - left
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        var x = left
        model.slices.forEach { slice ->
            val sliceWidth = width * slice.share
            if (sliceWidth <= 0f) return@forEach
            fill.color = slice.color
            canvas.drawRect(x, top, min(x + sliceWidth, right), bottom, fill)
            x += sliceWidth
        }
    }

    private fun drawRow(
        canvas: Canvas,
        slice: EffortSlice,
        left: Float,
        right: Float,
        baseline: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val swatch = labelSize * 0.7f
        canvas.drawRect(
            left,
            baseline - swatch,
            left + swatch * 0.5f,
            baseline,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slice.color },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(slice.label.orEmpty(), left + swatch, baseline, text)

        val value = slice.value.orEmpty()
        text.color = palette.textSecondary
        canvas.drawText(value, right - text.measureText(value), baseline, text)
    }

    private fun drawEmpty(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        val text = message ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = (area.height() * 0.22f).coerceIn(10f, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val x = area.left + (area.width() - paint.measureText(text)) / 2f
        val y = area.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, max(x, area.left), y, paint)
    }

    /** Corps de l'unité, en part de celui du nombre. */
    private const val UNIT_RATIO = 0.42f
}
