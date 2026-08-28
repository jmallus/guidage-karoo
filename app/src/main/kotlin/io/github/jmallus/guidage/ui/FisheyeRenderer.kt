package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.ProfileWindow
import kotlin.math.max
import kotlin.math.min

/** Une graduation de l'axe : sa distance devant le coureur, et ce qui s'écrit dessous. */
data class FisheyeTick(val aheadMeters: Double, val label: String)

/** Données prêtes à dessiner pour le champ « profil jusqu'à l'arrivée ». */
data class FisheyeFieldModel(
    /** Le profil du reste du parcours, son bord gauche à la position du coureur. */
    val window: ProfileWindow,
    /** L'échelle non linéaire, ou null pour dessiner droit. */
    val scale: FisheyeScale? = null,
    val ticks: List<FisheyeTick> = emptyList(),
    /** Distance où l'échelle bascule du pas fin au comprimé (m devant le coureur). */
    val hingeAheadMeters: Double? = null,
    /** Ce qu'il reste, écrit en haut à gauche. */
    val remainingLabel: String? = null,
    /** Le dénivelé qui reste, écrit en haut à droite. */
    val ascentLabel: String? = null,
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
)

/**
 * Dessine le profil du reste du parcours sur une échelle qui dilate le proche.
 *
 * Le tracé est peint colonne de pixels par colonne de pixels, et non segment par segment
 * comme le fait [ProfileRenderer] : là où l'échelle se comprime, plusieurs centaines de
 * mètres tombent dans le même pixel, et découper par segments y laisserait des trous. On
 * part donc de l'écran et on demande à l'échelle ce que porte chaque colonne.
 */
object FisheyeRenderer {

    fun render(width: Int, height: Int, model: FisheyeFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val labelSize = (height * 0.16f).coerceIn(9f, 20f)
        val padding = (min(width, height) * 0.04f).coerceIn(2f, 8f)
        val left = padding
        val right = width - padding
        val top = padding + labelSize * 1.2f
        // L'axe mange le bas : les graduations s'y écrivent, et sans elles la compression
        // ne se lit pas — le profil paraîtrait simplement bosselé à droite.
        val axisHeight = if (model.ticks.isEmpty()) 0f else labelSize * 1.3f
        val bottom = height - padding - axisHeight

        if (model.window.isEmpty || bottom <= top || right <= left) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }

        drawProfile(canvas, model, left, top, right, bottom, palette)
        drawHinge(canvas, model, left, top, right, bottom, palette)
        drawPosition(canvas, left, top, bottom, palette)
        drawAxis(canvas, model, left, right, bottom, height - padding, labelSize, palette)
        drawLabels(canvas, model, left, right, padding + labelSize, labelSize, palette)
        return bitmap
    }

    /** Position en pixels d'une distance devant le coureur. */
    private fun xOf(model: FisheyeFieldModel, ahead: Double, left: Float, right: Float): Float {
        val span = model.window.distanceSpan.takeIf { it > 0 } ?: return left
        val ratio = model.scale?.position(ahead) ?: (ahead / span)
        return left + (ratio * (right - left)).toFloat()
    }

    /** L'inverse : ce que porte une colonne de pixels. */
    private fun aheadOf(model: FisheyeFieldModel, x: Float, left: Float, right: Float): Double {
        val width = (right - left).toDouble().takeIf { it > 0 } ?: return 0.0
        val ratio = ((x - left) / width).coerceIn(0.0, 1.0)
        return model.scale?.distanceAt(ratio) ?: (ratio * model.window.distanceSpan)
    }

    private fun drawProfile(
        canvas: Canvas,
        model: FisheyeFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val window = model.window
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return
        val profileHeight = bottom - top

        fun y(elevation: Double): Float =
            bottom - ((elevation - window.minElevation) / elevationSpan * profileHeight).toFloat()

        fun elevationAt(ahead: Double): Double = interpolate(window, window.start + ahead)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val ridge = Path()
        var started = false

        var x = left
        while (x < right) {
            val next = min(x + COLUMN_WIDTH, right)
            val from = aheadOf(model, x, left, right)
            val to = aheadOf(model, next, left, right)
            val run = to - from
            val startElevation = elevationAt(from)
            val endElevation = elevationAt(to)
            val grade = if (run > 0.0) (endElevation - startElevation) / run * 100.0 else 0.0

            fill.color = if (model.colorByGrade) FieldPalette.gradeColor(grade) else FieldPalette.NEUTRAL
            val columnTop = y(endElevation)
            // Un demi-pixel de recouvrement : sans lui, l'anticrénelage laisse une raie
            // claire entre deux colonnes voisines, et le profil paraît hachuré.
            canvas.drawRect(x, columnTop, next + 0.5f, bottom, fill)

            val ridgeY = y(startElevation)
            if (!started) {
                ridge.moveTo(x, ridgeY)
                started = true
            } else {
                ridge.lineTo(x, ridgeY)
            }
            x = next
        }
        ridge.lineTo(right, y(elevationAt(window.distanceSpan)))

        canvas.drawPath(
            ridge,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = palette.outline
            },
        )
    }

    /** Altitude interpolée dans la fenêtre, à une distance comptée depuis le départ. */
    private fun interpolate(window: ProfileWindow, distance: Double): Double {
        val points = window.points
        if (points.isEmpty()) return 0.0
        if (distance <= points.first().distance) return points.first().elevation
        if (distance >= points.last().distance) return points.last().elevation

        var low = 0
        var high = points.size - 1
        while (low < high) {
            val middle = (low + high) / 2
            if (points[middle].distance < distance) low = middle + 1 else high = middle
        }
        val index = if (low == 0) 1 else low
        val before = points[index - 1]
        val after = points[index]
        val span = after.distance - before.distance
        if (span <= 0.0) return after.elevation
        return before.elevation + (distance - before.distance) / span * (after.elevation - before.elevation)
    }

    /**
     * Le filet de la charnière : au-delà, l'échelle se comprime.
     *
     * Sans lui la bascule est invisible, et l'œil croit à une échelle droite jusqu'au bout.
     */
    private fun drawHinge(
        canvas: Canvas,
        model: FisheyeFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val hinge = model.hingeAheadMeters ?: return
        if (model.scale == null) return
        val x = xOf(model, hinge, left, right)
        if (x <= left || x >= right) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            strokeWidth = 1.5f
            alpha = HINGE_ALPHA
        }
        var y = top
        while (y < bottom) {
            canvas.drawLine(x, y, x, min(y + DASH, bottom), paint)
            y += DASH * 2
        }
    }

    private fun drawPosition(canvas: Canvas, left: Float, top: Float, bottom: Float, palette: Palette) {
        val size = ((bottom - top) * 0.14f).coerceIn(4f, 12f)
        val middle = bottom
        val path = Path().apply {
            moveTo(left, middle - size)
            lineTo(left + size, middle - size / 2f)
            lineTo(left, middle)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary })
    }

    /**
     * Les graduations, et le blanc qu'elles réclament.
     *
     * Elles portent toute la démonstration : leur écartement inégal est ce qui dit que
     * l'échelle n'est pas droite. On les écarte donc de proche en proche, en laissant
     * tomber celles qui viendraient sur la précédente — mieux vaut trois graduations
     * lisibles que sept superposées.
     */
    private fun drawAxis(
        canvas: Canvas,
        model: FisheyeFieldModel,
        left: Float,
        right: Float,
        axisTop: Float,
        axisBottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        if (model.ticks.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize * 0.86f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            strokeWidth = 1.5f
            alpha = HINGE_ALPHA
        }

        var lastRight = left - labelSize
        model.ticks.forEach { tick ->
            val x = xOf(model, tick.aheadMeters, left, right)
            val half = paint.measureText(tick.label) / 2f
            if (x - half < lastRight + TICK_GAP || x + half > right) return@forEach
            canvas.drawLine(x, axisTop, x, axisTop + labelSize * 0.3f, rule)
            canvas.drawText(tick.label, x - half, axisBottom - paint.descent(), paint)
            lastRight = x + half
        }
    }

    private fun drawLabels(
        canvas: Canvas,
        model: FisheyeFieldModel,
        left: Float,
        right: Float,
        baseline: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        model.remainingLabel?.let { canvas.drawText(it, left, baseline, paint) }
        model.ascentLabel?.let {
            canvas.drawText(it, right - paint.measureText(it), baseline, paint)
        }
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

    /** Largeur d'une colonne peinte, en pixels. */
    private const val COLUMN_WIDTH = 1.5f

    /** Blanc minimal entre deux graduations, en part du corps. */
    private const val TICK_GAP = 8f

    private const val HINGE_ALPHA = 140

    /** Longueur d'un tiret du filet de charnière. */
    private const val DASH = 4f
}
