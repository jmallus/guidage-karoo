package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.max
import kotlin.math.min

/** Données prêtes à dessiner pour le champ « prochaine côte ». */
data class ClimbFieldModel(
    /** Ligne d'en-tête, ex. « CÔTE 2/5 » ou « EN COURS ». */
    val header: String? = null,
    /** Valeur principale, ex. « 1,2 km ». */
    val primary: String,
    /** Première valeur secondaire, ex. « 6,5 % ». */
    val secondaryTop: String? = null,
    /** Seconde valeur secondaire, ex. « +120 m ». */
    val secondaryBottom: String? = null,
    /** Avancement dans la côte (0 à 1), null hors côte. */
    val progress: Float? = null,
    /** Pente servant à colorer l'accent. */
    val accentGrade: Double? = null,
    /** Message affiché à la place des valeurs quand il n'y a pas de côte. */
    val caption: String? = null,
)

/**
 * Dessine le champ « prochaine côte » : distance jusqu'à la côte (ou jusqu'au sommet
 * si elle est commencée), pente moyenne, dénivelé restant et barre de progression.
 */
object ClimbRenderer {

    fun render(width: Int, height: Int, model: ClimbFieldModel, alignment: ViewConfig.Alignment): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.05f).coerceIn(2f, 10f)
        val headerSize = (height * 0.16f).coerceIn(9f, 20f)
        val barHeight = if (model.progress != null) (height * 0.1f).coerceIn(4f, 12f) else 0f
        val accent = model.accentGrade?.let { FieldPalette.gradeColor(it) } ?: FieldPalette.NEUTRAL

        val left = padding
        val right = width - padding
        val contentTop = if (model.header != null) padding + headerSize * 1.2f else padding
        val contentBottom = height - padding - if (barHeight > 0) barHeight * 1.6f else 0f

        model.header?.let {
            val paint = textPaint(headerSize, FieldPalette.TEXT_SECONDARY, Paint.Align.LEFT)
            canvas.drawText(it, left, padding + headerSize, paint)
        }

        val secondarySize = (height * 0.19f).coerceIn(10f, 24f)
        var secondaryWidth = 0f
        val secondaryPaint = textPaint(secondarySize, FieldPalette.TEXT_PRIMARY, Paint.Align.RIGHT)
        val secondaries = listOfNotNull(model.secondaryTop, model.secondaryBottom)
        if (secondaries.isNotEmpty()) {
            secondaryWidth = secondaries.maxOf { secondaryPaint.measureText(it) } + padding
            var y = contentTop + secondarySize
            secondaries.forEach { value ->
                canvas.drawText(value, right, y, secondaryPaint)
                y += secondarySize * 1.25f
            }
        }

        val primarySize = fitTextSize(
            text = model.primary,
            maxWidth = (right - left - secondaryWidth).coerceAtLeast(1f),
            preferredSize = (contentBottom - contentTop) * 0.72f,
        )
        val primaryPaint = textPaint(primarySize, FieldPalette.TEXT_PRIMARY, Paint.Align.LEFT, bold = true)
        val primaryY = (contentTop + contentBottom) / 2f - (primaryPaint.descent() + primaryPaint.ascent()) / 2f
        val primaryX = when (alignment) {
            ViewConfig.Alignment.LEFT -> left
            ViewConfig.Alignment.CENTER -> (left + right - secondaryWidth) / 2f - primaryPaint.measureText(model.primary) / 2f
            ViewConfig.Alignment.RIGHT -> right - secondaryWidth - primaryPaint.measureText(model.primary)
        }
        canvas.drawText(model.primary, primaryX.coerceAtLeast(left), primaryY, primaryPaint)

        model.caption?.let {
            val paint = textPaint((height * 0.15f).coerceIn(9f, 18f), FieldPalette.TEXT_SECONDARY, Paint.Align.LEFT)
            canvas.drawText(it, left, contentBottom, paint)
        }

        model.progress?.let { progress ->
            val barTop = height - padding - barHeight
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FieldPalette.TRACK }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            val radius = barHeight / 2f
            canvas.drawRoundRect(RectF(left, barTop, right, barTop + barHeight), radius, radius, track)
            val filledRight = left + (right - left) * progress.coerceIn(0f, 1f)
            if (filledRight > left) {
                canvas.drawRoundRect(RectF(left, barTop, filledRight, barTop + barHeight), radius, radius, fill)
            }
        }

        return bitmap
    }

    private fun textPaint(
        size: Float,
        color: Int,
        align: Paint.Align,
        bold: Boolean = false,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fitTextSize(text: String, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 96f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }
}
