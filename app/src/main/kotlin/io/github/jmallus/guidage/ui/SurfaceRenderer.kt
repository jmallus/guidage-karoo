package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

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

        val padding = (min(width, height) * 0.05f).coerceIn(3f, 10f)
        val left = padding
        val right = width - padding
        if (right <= left) return bitmap

        if (model.bands.isEmpty()) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }

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

        val bandTop = y + padding
        val labelRoom = if (model.bands.any { it.label != null }) labelSize * 1.3f else 0f
        val bandBottom = height - padding - labelRoom
        if (bandBottom > bandTop) {
            drawBands(canvas, model, left, bandTop, right, bandBottom, labelSize, palette)
        }
        return bitmap
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
        val x = (width - paint.measureText(text)) / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, max(x, 0f), y, paint)
    }

    /** Encre de la trame du chemin : la teinte de la piste, assombrie. */
    private const val HATCH_INK = 0xFF6B4A22.toInt()
}
