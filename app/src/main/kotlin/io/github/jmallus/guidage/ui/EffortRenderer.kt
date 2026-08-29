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
    /**
     * Part de la barre déjà payée (0 à 1), null quand la dépense n'est pas mesurée.
     *
     * Quand elle est là, la barre ne porte plus le seul reste mais la sortie entière : le
     * dépensé en gris à gauche, les postes qui restent à droite. C'est ce qui répond à la
     * question que le total seul laisse ouverte — six cents kilojoules devant, est-ce le
     * début ou la fin ?
     */
    val spentShare: Float? = null,
    /** « 62 % DE L'EFFORT · 50 % DE LA DISTANCE », sous la barre. */
    val progressCaption: String? = null,
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
        val captionRoom = if (model.progressCaption == null) 0f else labelSize * 1.4f
        val reserved = barHeight + captionRoom + padding + rows.size * rowHeight
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
            drawBar(canvas, model, left, cursor, right, cursor + barHeight, palette)
            cursor += barHeight
            // La légende chiffrée suit la barre immédiatement, sans la marge : elle la
            // commente, et un blanc entre les deux les ferait lire comme deux choses.
            model.progressCaption?.let { caption ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textSecondary
                    textSize = labelSize * 0.92f
                    letterSpacing = 0.04f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                if (cursor + captionRoom <= bottom) {
                    canvas.drawText(caption, left, cursor + labelSize, paint)
                    cursor += captionRoom
                }
            }
            cursor += padding
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

    /**
     * La barre : ce qui est payé à gauche, ce qui reste à droite.
     *
     * Sans dépense mesurée, elle ne porte que le reste et occupe toute sa largeur — c'est le
     * comportement d'origine, et le seul possible sans capteur de puissance. Avec, elle porte
     * la sortie entière, et le trait qui sépare les deux est le coureur : à sa gauche ce qui
     * est fait, à sa droite ce qui coûte encore.
     */
    private fun drawBar(
        canvas: Canvas,
        model: EffortFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val width = right - left
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val spent = model.spentShare?.coerceIn(0f, 1f)
        var x = left
        if (spent != null && spent > 0f) {
            fill.color = palette.outline
            fill.alpha = SPENT_ALPHA
            canvas.drawRect(x, top, left + width * spent, bottom, fill)
            fill.alpha = 0xFF
            x = left + width * spent
        }

        // Les postes se partagent ce qui reste de la barre, non sa largeur entière : leurs
        // parts se rapportent au budget restant, et non à la sortie.
        val remainingWidth = width * (1f - (spent ?: 0f))
        model.slices.forEach { slice ->
            val sliceWidth = remainingWidth * slice.share
            if (sliceWidth <= 0f) return@forEach
            fill.color = slice.color
            canvas.drawRect(x, top, min(x + sliceWidth, right), bottom, fill)
            x += sliceWidth
        }

        if (spent != null && spent > 0f && spent < 1f) {
            val mark = left + width * spent
            canvas.drawLine(
                mark,
                top - (bottom - top) * 0.25f,
                mark,
                bottom + (bottom - top) * 0.25f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textPrimary
                    strokeWidth = ((bottom - top) * 0.14f).coerceIn(1.5f, 4f)
                },
            )
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

    /** Opacité de la part déjà payée : présente, mais elle n'est plus à décider. */
    private const val SPENT_ALPHA = 0x66
}
