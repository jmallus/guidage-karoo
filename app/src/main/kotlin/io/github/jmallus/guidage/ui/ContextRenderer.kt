package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min

/** Une paire libellé / valeur, telle que le Karoo les écrit. */
data class ContextStat(val label: String, val value: String)

/** Le bandeau coloré qui annonce ce qui presse. */
data class ContextBanner(val title: String, val value: String, val color: Int)

/** Données prêtes à dessiner pour le champ contextuel. */
data class ContextFieldModel(
    /** La moitié haute, qui ne bouge jamais. */
    val topLeft: ContextStat? = null,
    val topRight: ContextStat? = null,
    val banner: ContextBanner? = null,
    /** Les paires du bas, quand l'état s'écrit en chiffres. */
    val stats: List<ContextStat> = emptyList(),
    /** Pentes en pourcents, une par tranche, pour la silhouette de la côte. */
    val gradeBars: List<Double> = emptyList(),
    /** Avancement dans la côte, de 0 à 1. */
    val progress: Float? = null,
    /** Les virages, quand on descend. */
    val bends: List<BendMark> = emptyList(),
    /** La ligne du bas : « CÔTE 4 SUR 6 ». */
    val caption: String? = null,
    /** Rang de l'état courant et nombre d'états, pour le rail de pastilles. */
    val stateIndex: Int = 0,
    val stateCount: Int = 0,
    val emptyMessage: String? = null,
)

/**
 * Dessine le champ qui décide lui-même de ce qu'il montre.
 *
 * Deux garde-fous font toute la mise en page. La moitié haute ne bouge jamais : l'œil y
 * retrouve toujours la même chose au même endroit, quoi qu'il arrive dessous. Et le rail de
 * pastilles, tout en bas, dit dans quel état on est — une bascule qu'on n'a pas vue venir
 * est une bascule qu'on subit.
 *
 * Le liseré qui sépare les deux moitiés n'est pas décoratif : c'est la frontière entre ce
 * qui est stable et ce qui ne l'est pas, et c'est ce qui rend la bascule supportable.
 */
object ContextRenderer {

    fun render(width: Int, height: Int, model: ContextFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.04f).coerceIn(3f, 10f)
        val left = padding
        val right = width - padding
        if (right <= left) return bitmap

        if (model.emptyMessage != null && model.topLeft == null) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }

        val labelSize = (height * 0.055f).coerceIn(9f, 18f)
        val railHeight = if (model.stateCount > 0) labelSize else 0f

        // La moitié haute occupe un peu moins de la moitié : ce qui bouge a besoin de plus
        // de place que ce qui ne bouge pas.
        val split = height * 0.42f
        drawTopRow(canvas, model, left, right, padding, split - padding, labelSize, palette)

        canvas.drawLine(
            0f, split, width.toFloat(), split,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.position
                strokeWidth = (height * 0.008f).coerceIn(2f, 5f)
            },
        )

        var top = split + padding
        val bottom = height - padding - railHeight
        model.banner?.let { banner ->
            val bannerHeight = ((bottom - top) * 0.36f).coerceIn(14f, 64f)
            drawBanner(canvas, banner, 0f, top, width.toFloat(), top + bannerHeight)
            top += bannerHeight + padding
        }

        // Les chiffres d'abord, le dessin ensuite : en montée on veut les deux — ce qui
        // reste au sommet en chiffres, et la silhouette de ce qui reste à monter.
        val graphic = model.gradeBars.isNotEmpty() || model.bends.isNotEmpty()
        if (model.stats.isNotEmpty() && bottom > top) {
            val statsHeight = if (graphic) (bottom - top) * 0.52f else bottom - top
            drawStats(canvas, model.stats, left, top, right, top + statsHeight, labelSize, palette)
            top += statsHeight + padding
        }
        if (graphic && bottom > top) {
            if (model.gradeBars.isNotEmpty()) {
                drawClimb(canvas, model, left, top, right, bottom, labelSize, palette)
            } else {
                drawBends(canvas, model, left, top, right, bottom, palette)
            }
        }

        if (railHeight > 0f) {
            drawRail(canvas, model, left, height - padding - railHeight / 2f, labelSize, palette)
        }
        return bitmap
    }

    private fun drawTopRow(
        canvas: Canvas,
        model: ContextFieldModel,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val pairs = listOfNotNull(model.topLeft, model.topRight)
        if (pairs.isEmpty()) return
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valueSize = ((bottom - top) * 0.62f).coerceIn(14f, max(16f, (right - left) * 0.22f))
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = valueSize
        }
        val baselineLabel = top + labelSize
        val baselineValue = bottom

        model.topLeft?.let {
            canvas.drawText(it.label, left, baselineLabel, label)
            canvas.drawText(it.value, left, baselineValue, value)
        }
        model.topRight?.let {
            canvas.drawText(it.label, right - label.measureText(it.label), baselineLabel, label)
            canvas.drawText(it.value, right - value.measureText(it.value), baselineValue, value)
        }
    }

    private fun drawBanner(canvas: Canvas, banner: ContextBanner, left: Float, top: Float, right: Float, bottom: Float) {
        canvas.drawRect(RectF(left, top, right, bottom), Paint().apply { color = banner.color })
        val ink = Contrast.bestTextColor(banner.color)
        val size = ((bottom - top) * 0.5f).coerceIn(10f, 30f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(banner.title, left + EDGE, baseline, paint)
        canvas.drawText(banner.value, right - EDGE - paint.measureText(banner.value), baseline, paint)
    }

    private fun drawStats(
        canvas: Canvas,
        stats: List<ContextStat>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val rows = stats.take(2)
        val rowHeight = (bottom - top) / rows.size
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        rows.forEachIndexed { index, stat ->
            val rowTop = top + index * rowHeight
            val valueSize = (rowHeight * 0.66f).coerceIn(12f, max(14f, (right - left) * 0.2f))
            val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textPrimary
                textSize = valueSize
            }
            canvas.drawText(stat.label, left, rowTop + labelSize, label)
            canvas.drawText(
                stat.value,
                right - value.measureText(stat.value),
                rowTop + rowHeight * 0.92f,
                value,
            )
        }
    }

    /** La silhouette de la côte, colorée par tranche, et l'avancement dessous. */
    private fun drawClimb(
        canvas: Canvas,
        model: ContextFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val captionRoom = if (model.caption == null) 0f else labelSize * 1.4f
        val progressRoom = if (model.progress == null) 0f else labelSize * 0.9f
        val profileBottom = bottom - captionRoom - progressRoom
        if (profileBottom <= top) return

        val bars = model.gradeBars
        val barWidth = (right - left) / bars.size
        val steepest = max(bars.maxOrNull() ?: 1.0, 1.0)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        bars.forEachIndexed { index, grade ->
            val ratio = (grade / steepest).coerceIn(0.12, 1.0)
            val barTop = profileBottom - ((profileBottom - top) * ratio).toFloat()
            fill.color = FieldPalette.gradeColor(grade)
            canvas.drawRect(
                left + index * barWidth,
                barTop,
                left + (index + 1) * barWidth + 0.5f,
                profileBottom,
                fill,
            )
        }

        model.progress?.let { progress ->
            val barTop = profileBottom + progressRoom * 0.25f
            val barBottom = profileBottom + progressRoom * 0.75f
            canvas.drawRect(left, barTop, right, barBottom, Paint().apply { color = palette.track })
            canvas.drawRect(
                left, barTop, left + (right - left) * progress.coerceIn(0f, 1f), barBottom,
                Paint().apply { color = palette.position },
            )
        }

        model.caption?.let {
            canvas.drawText(
                it, left, bottom - labelSize * 0.3f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textSecondary
                    textSize = labelSize
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
            )
        }
    }

    /** Les virages, dans la même langue que le champ qui leur est consacré. */
    private fun drawBends(
        canvas: Canvas,
        model: ContextFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val middle = (top + bottom) / 2f
        val half = (bottom - top) / 2f
        canvas.drawLine(
            left, middle, right, middle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.track
                strokeWidth = (half * 0.14f).coerceIn(2f, 6f)
                strokeCap = Paint.Cap.ROUND
            },
        )
        val barWidth = ((right - left) * 0.035f).coerceIn(3f, 12f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        model.bends.forEach { mark ->
            val centerX = left + mark.position.coerceIn(0f, 1f) * (right - left)
            val length = half * mark.extent.coerceIn(0.15f, 1f)
            fill.color = mark.color
            if (mark.direction < 0) {
                canvas.drawRect(centerX - barWidth / 2f, middle - length, centerX + barWidth / 2f, middle, fill)
            } else {
                canvas.drawRect(centerX - barWidth / 2f, middle, centerX + barWidth / 2f, middle + length, fill)
            }
        }
        canvas.drawCircle(
            left, middle, (barWidth * 0.6f).coerceAtLeast(3f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary },
        )
    }

    /** Le rail : une pastille par état, la courante allumée. */
    private fun drawRail(
        canvas: Canvas,
        model: ContextFieldModel,
        left: Float,
        centerY: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val radius = (labelSize * 0.22f).coerceIn(2f, 5f)
        val gap = radius * 4f
        val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary }
        val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.track }
        repeat(model.stateCount) { index ->
            canvas.drawCircle(
                left + index * gap,
                centerY,
                radius,
                if (index == model.stateIndex) on else off,
            )
        }
    }

    private fun drawEmpty(canvas: Canvas, width: Int, height: Int, message: String?, palette: Palette) {
        val text = message ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = (height * 0.16f).coerceIn(10f, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val x = (width - paint.measureText(text)) / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, max(x, 0f), y, paint)
    }

    private const val EDGE = 6f
}
