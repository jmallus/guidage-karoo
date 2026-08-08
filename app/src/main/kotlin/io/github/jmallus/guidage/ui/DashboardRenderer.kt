package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import kotlin.math.max
import kotlin.math.min

/** Un point d'intérêt à marquer sur le graphe de parcours. */
data class GraphPoi(val distance: Double, val label: String)

/** Graphe de parcours en portrait : la distance monte, l'altitude se lit horizontalement. */
data class RouteGraphModel(
    val window: ProfileWindow,
    /** Position courante sur l'itinéraire (m), pour placer le repère. */
    val position: Double,
    val climbs: List<RouteClimb> = emptyList(),
    val pois: List<GraphPoi> = emptyList(),
    val zoomLabel: String? = null,
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
)

/** Graphe de la montée en cours (ou à venir), en bandeau horizontal. */
data class ClimbGraphModel(
    val points: List<ProfilePoint> = emptyList(),
    /** Position courante (m depuis le départ de l'itinéraire). */
    val position: Double = 0.0,
    /** Titre du bandeau, ex. « Côte 2/5 — 1,8 km à 6,5 % ». */
    val title: String? = null,
    /** Message affiché quand il n'y a pas de montée à montrer. */
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
)

/** Une case de chiffres. */
data class Tile(val label: String, val value: String, val unit: String? = null)

/** Ce qu'on affiche dans la colonne de guidage, à droite des mesures. */
sealed interface GuidanceZone {
    data class Map(val model: MapModel) : GuidanceZone

    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le champ plein écran.
 *
 * Colonne gauche : les quatre mesures de l'effort empilées. Colonne droite, sur la même
 * hauteur : le guidage. Sous les deux : distance restante et heure d'arrivée. Tout en bas,
 * sur toute la largeur : le graphe de la montée.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    /** Vitesse, puissance, fréquence cardiaque, cadence. */
    val tiles: List<Tile>,
    /** Distance restante et heure d'arrivée. */
    val footerTiles: List<Tile>,
    val climbGraph: ClimbGraphModel,
    val palette: Palette,
)

object DashboardRenderer {

    /** Largeur de la colonne des mesures. */
    private const val TILE_COLUMN_FRACTION = 0.44f

    /** Hauteur occupée par les mesures et le guidage. */
    private const val MAIN_HEIGHT_FRACTION = 0.60f

    /** Hauteur de la ligne « restant / arrivée ». */
    private const val FOOTER_HEIGHT_FRACTION = 0.17f

    fun render(width: Int, height: Int, model: DashboardModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.015f).coerceIn(2f, 6f)
        val columnSplit = width * TILE_COLUMN_FRACTION
        val mainBottom = height * MAIN_HEIGHT_FRACTION
        val footerBottom = mainBottom + height * FOOTER_HEIGHT_FRACTION

        // Colonne gauche : les quatre mesures.
        val rowHeight = (mainBottom - padding) / model.tiles.size.coerceAtLeast(1)
        model.tiles.forEachIndexed { index, tile ->
            val top = padding + index * rowHeight
            drawTile(
                canvas,
                RectF(padding, top, columnSplit, top + rowHeight),
                tile,
                model.palette,
            )
        }

        // Colonne droite : le guidage, sur toute la hauteur des mesures.
        val guidanceArea = RectF(columnSplit, padding, width - padding, mainBottom)
        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model, model.palette)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, guidanceArea, guidance.model, model.palette)
        }

        // Ligne du bas : restant et arrivée.
        val footerWidth = (width - 2 * padding) / model.footerTiles.size.coerceAtLeast(1)
        model.footerTiles.forEachIndexed { index, tile ->
            val left = padding + index * footerWidth
            drawTile(
                canvas,
                RectF(left, mainBottom, left + footerWidth, footerBottom),
                tile,
                model.palette,
            )
        }

        drawClimbGraph(
            canvas,
            RectF(padding, footerBottom, width - padding, height - padding),
            model.climbGraph,
            model.palette,
        )
        return bitmap
    }

    // --- Cases de chiffres ---------------------------------------------------------------

    /**
     * Une case sans bordure : libellé et valeur sont centrés dans l'espace qui leur revient,
     * seul repère visuel une fois les traits de séparation retirés.
     */
    private fun drawTile(canvas: Canvas, bounds: RectF, tile: Tile, palette: Palette) {
        val labelSize = (bounds.height() * 0.2f).coerceIn(10f, 20f)
        canvas.drawText(
            tile.label,
            bounds.centerX(),
            bounds.top + labelSize,
            paint(labelSize, palette.textSecondary).apply { textAlign = Paint.Align.CENTER },
        )

        val unitSize = labelSize * 1.05f
        val unitPaint = paint(unitSize, palette.textSecondary)
        val unitWidth = tile.unit?.let { unitPaint.measureText(it) + 6f } ?: 0f

        val valuePaint = paint(
            size = fitTextSize(
                text = tile.value,
                maxWidth = bounds.width() - 12f - unitWidth,
                preferredSize = (bounds.height() - labelSize) * 0.8f,
            ),
            color = palette.textPrimary,
        )

        // Valeur et unité forment un bloc, centré d'un seul tenant.
        val valueWidth = valuePaint.measureText(tile.value)
        val blockLeft = bounds.centerX() - (valueWidth + unitWidth) / 2f
        val available = bounds.bottom - (bounds.top + labelSize)
        val baseline = bounds.top + labelSize + available / 2f -
            (valuePaint.descent() + valuePaint.ascent()) / 2f

        canvas.drawText(tile.value, blockLeft, baseline, valuePaint)
        tile.unit?.let {
            canvas.drawText(it, blockLeft + valueWidth + 6f, baseline, unitPaint)
        }
    }

    // --- Bandeau du bas : la montée ------------------------------------------------------

    private fun drawClimbGraph(canvas: Canvas, area: RectF, model: ClimbGraphModel, palette: Palette) {
        val titleSize = (area.height() * 0.24f).coerceIn(10f, 20f)
        if (model.points.size < 2 || area.width() <= 0 || area.height() <= 0) {
            drawCentered(canvas, area, model.emptyMessage ?: "—", titleSize, palette)
            return
        }

        val top = area.top + titleSize * 1.2f
        val bottom = area.bottom
        val start = model.points.first().distance
        val end = model.points.last().distance
        val distanceSpan = (end - start).takeIf { it > 0 } ?: return
        val minElevation = model.points.minOf { it.elevation }
        val elevationSpan = (model.points.maxOf { it.elevation } - minElevation).coerceAtLeast(10.0)

        fun x(distance: Double) = area.left + ((distance - start) / distanceSpan * area.width()).toFloat()
        fun y(elevation: Double) = bottom - ((elevation - minElevation) / elevationSpan * (bottom - top)).toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (index in 1 until model.points.size) {
            val previous = model.points[index - 1]
            val current = model.points[index]
            val length = current.distance - previous.distance
            if (length <= 0) continue
            val grade = (current.elevation - previous.elevation) / length * 100.0
            fill.color = if (model.colorByGrade) FieldPalette.gradeColor(grade) else FieldPalette.NEUTRAL
            canvas.drawPath(
                Path().apply {
                    moveTo(x(previous.distance), y(previous.elevation))
                    lineTo(x(current.distance), y(current.elevation))
                    lineTo(x(current.distance), bottom)
                    lineTo(x(previous.distance), bottom)
                    close()
                },
                fill,
            )
        }

        // Repère de progression dans la montée.
        if (model.position in start..end) {
            canvas.drawLine(
                x(model.position),
                top,
                x(model.position),
                bottom,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.position
                    strokeWidth = 3f
                },
            )
        }

        model.title?.let {
            canvas.drawText(it, area.left, area.top + titleSize, paint(titleSize, palette.textSecondary))
        }
    }

    // --- Colonne de guidage en mode profil ------------------------------------------------

    private fun drawRouteGraph(canvas: Canvas, area: RectF, model: RouteGraphModel, palette: Palette) {
        val labelSize = (area.height() * 0.05f).coerceIn(9f, 16f)
        val window = model.window
        if (window.isEmpty || area.width() <= 0 || area.height() <= 0) {
            drawCentered(canvas, area, model.emptyMessage ?: "—", labelSize * 1.3f, palette)
            return
        }

        val top = area.top + labelSize * 1.4f
        val bottom = area.bottom - labelSize * 0.4f
        val distanceSpan = window.distanceSpan.takeIf { it > 0 } ?: return
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return
        val axis = VerticalAxis(top, bottom, window.start, distanceSpan)

        fun x(elevation: Double) =
            area.left + ((elevation - window.minElevation) / elevationSpan * area.width()).toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val points = window.points
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val length = current.distance - previous.distance
            if (length <= 0) continue
            val grade = (current.elevation - previous.elevation) / length * 100.0
            fill.color = if (model.colorByGrade) FieldPalette.gradeColor(grade) else FieldPalette.NEUTRAL
            canvas.drawPath(
                Path().apply {
                    moveTo(area.left, axis.y(previous.distance))
                    lineTo(x(previous.elevation), axis.y(previous.distance))
                    lineTo(x(current.elevation), axis.y(current.distance))
                    lineTo(area.left, axis.y(current.distance))
                    close()
                },
                fill,
            )
        }

        model.pois
            .filter { it.distance in window.start..window.end }
            .forEach { poi ->
                val poiY = axis.y(poi.distance)
                canvas.drawCircle(area.right - 5f, poiY, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.position
                })
            }

        if (model.position in window.start..window.end) {
            canvas.drawLine(
                area.left,
                axis.y(model.position),
                area.right,
                axis.y(model.position),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.position
                    strokeWidth = 3f
                },
            )
        }

        model.zoomLabel?.let {
            canvas.drawText(it, area.left + 4f, area.top + labelSize, paint(labelSize, palette.textSecondary))
        }
    }

    /** Conversion distance → ordonnée, le début de la fenêtre étant en bas. */
    private class VerticalAxis(
        private val top: Float,
        private val bottom: Float,
        private val start: Double,
        private val span: Double,
    ) {
        fun y(distance: Double): Float = bottom - ((distance - start) / span * (bottom - top)).toFloat()
    }

    // --- Utilitaires ----------------------------------------------------------------------

    private fun paint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun drawCentered(canvas: Canvas, area: RectF, message: String, size: Float, palette: Palette) {
        val paint = paint(size, palette.textSecondary).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(
            message,
            area.centerX(),
            area.centerY() - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
    }

    private fun fitTextSize(text: String, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 140f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }
}
