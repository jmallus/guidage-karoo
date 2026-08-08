package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import kotlin.math.max
import kotlin.math.min

/** Données prêtes à dessiner pour le champ « profil à venir ». */
data class ProfileFieldModel(
    val window: ProfileWindow,
    /** Côtes de l'itinéraire, pour surligner celles visibles dans la fenêtre. */
    val climbs: List<RouteClimb> = emptyList(),
    /** Texte du dénivelé positif restant sur la fenêtre, ex. « +120 m ». */
    val ascentLabel: String? = null,
    /** Texte de la portée affichée, ex. « 5,0 km ». */
    val rangeLabel: String? = null,
    /** Message affiché quand il n'y a rien à montrer. */
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
)

/**
 * Dessine le profil altimétrique à venir dans un bitmap aux dimensions du champ.
 *
 * Glance ne sait pas dessiner de courbe : le champ est donc rendu sur un Canvas puis
 * envoyé au système sous forme d'image.
 */
object ProfileRenderer {

    fun render(width: Int, height: Int, model: ProfileFieldModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val labelSize = (height * 0.16f).coerceIn(9f, 20f)
        val padding = (min(width, height) * 0.04f).coerceIn(2f, 8f)
        val top = padding + labelSize
        val bottom = height - padding
        val left = padding
        val right = width - padding

        if (model.window.isEmpty || bottom <= top || right <= left) {
            drawEmpty(canvas, width, height, model.emptyMessage)
            return bitmap
        }

        drawProfile(canvas, model, left, top, right, bottom)
        drawClimbMarkers(canvas, model, left, top, right, bottom, labelSize)
        drawPositionMarker(canvas, left, top, bottom)
        drawLabels(canvas, model, left, right, padding + labelSize, labelSize)
        return bitmap
    }

    private fun drawProfile(
        canvas: Canvas,
        model: ProfileFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val window = model.window
        val points = window.points
        val distanceSpan = window.distanceSpan.takeIf { it > 0 } ?: return
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return

        fun x(distance: Double) = left + ((distance - window.start) / distanceSpan * (right - left)).toFloat()
        fun y(elevation: Double) = bottom - ((elevation - window.minElevation) / elevationSpan * (bottom - top)).toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (index in 1 until points.size) {
            val previous: ProfilePoint = points[index - 1]
            val current: ProfilePoint = points[index]
            val segmentLength = current.distance - previous.distance
            if (segmentLength <= 0) continue
            val grade = (current.elevation - previous.elevation) / segmentLength * 100.0

            fill.color = if (model.colorByGrade) FieldPalette.gradeColor(grade) else FieldPalette.NEUTRAL
            val path = Path().apply {
                moveTo(x(previous.distance), y(previous.elevation))
                lineTo(x(current.distance), y(current.elevation))
                lineTo(x(current.distance), bottom)
                lineTo(x(previous.distance), bottom)
                close()
            }
            canvas.drawPath(path, fill)
        }

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = FieldPalette.OUTLINE
        }
        val line = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) line.moveTo(x(point.distance), y(point.elevation))
            else line.lineTo(x(point.distance), y(point.elevation))
        }
        canvas.drawPath(line, outline)
    }

    private fun drawClimbMarkers(
        canvas: Canvas,
        model: ProfileFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
    ) {
        val window = model.window
        val distanceSpan = window.distanceSpan.takeIf { it > 0 } ?: return
        fun x(distance: Double) = left + ((distance - window.start) / distanceSpan * (right - left)).toFloat()

        val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_PRIMARY
            textSize = labelSize * 0.9f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        model.climbs
            .filter { it.endDistance > window.start && it.startDistance < window.end }
            .forEach { climb ->
                val startX = x(max(climb.startDistance, window.start))
                val endX = x(min(climb.endDistance, window.end))
                if (endX - startX < 6f) return@forEach

                overlay.color = FieldPalette.translucent(FieldPalette.gradeColor(climb.grade), 40)
                canvas.drawRect(startX, top, endX, bottom, overlay)

                if (endX - startX > labelSize * 2.4f) {
                    canvas.drawText(
                        "${climb.grade.toInt()}%",
                        (startX + endX) / 2,
                        top + labelSize,
                        text,
                    )
                }
            }
    }

    private fun drawPositionMarker(canvas: Canvas, left: Float, top: Float, bottom: Float) {
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.POSITION
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(left, top, left, bottom, marker)

        val triangle = Path().apply {
            moveTo(left, bottom)
            lineTo(left - 5f, bottom + 5f)
            lineTo(left + 5f, bottom + 5f)
            close()
        }
        canvas.drawPath(
            triangle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FieldPalette.POSITION
                style = Paint.Style.FILL
            },
        )
    }

    private fun drawLabels(
        canvas: Canvas,
        model: ProfileFieldModel,
        left: Float,
        right: Float,
        baseline: Float,
        labelSize: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }
        model.ascentLabel?.let {
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, left, baseline - labelSize * 0.2f, paint)
        }
        model.rangeLabel?.let {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, right, baseline - labelSize * 0.2f, paint)
        }
    }

    private fun drawEmpty(canvas: Canvas, width: Int, height: Int, message: String?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (height * 0.22f).coerceIn(10f, 26f)
        }
        val centerY = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(message ?: "—", width / 2f, centerY, paint)
    }
}
