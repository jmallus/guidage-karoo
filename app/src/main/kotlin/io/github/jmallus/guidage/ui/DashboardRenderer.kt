package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import kotlin.math.max
import kotlin.math.min

/** Un point d'intérêt à marquer sur le graphe. */
data class GraphPoi(val distance: Double, val label: String)

/** Graphe de parcours en portrait : la distance monte, l'altitude se lit horizontalement. */
data class RouteGraphModel(
    val window: ProfileWindow,
    /** Position courante sur l'itinéraire (m), pour placer le repère. */
    val position: Double,
    val climbs: List<RouteClimb> = emptyList(),
    val pois: List<GraphPoi> = emptyList(),
    /** Étiquette du zoom courant, ex. « Parcours » ou « 20 km ». */
    val zoomLabel: String? = null,
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
)

/** Une case de chiffres du tableau de bord. */
data class Tile(val label: String, val value: String, val unit: String? = null)

/** Ce qu'on affiche dans la zone de guidage, en haut de l'écran. */
sealed interface GuidanceZone {
    /** Minicarte orientée cap en haut. */
    data class Map(val model: MapModel) : GuidanceZone

    /** Profil altimétrique en portrait, la distance montant vers le haut. */
    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le tableau de bord complet : la zone de guidage occupe le haut sur la hauteur de deux
 * champs, les valeurs chiffrées se répartissent dessous, deux par ligne.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    val tiles: List<Tile>,
)

/**
 * Dessine le champ plein écran : le graphe de parcours en portrait occupe la colonne de
 * gauche sur toute la hauteur, les valeurs chiffrées s'empilent à droite.
 */
object DashboardRenderer {

    /**
     * Part de la hauteur réservée au guidage : deux champs sur les quatre lignes
     * d'une page Karoo, les six valeurs se partageant le reste.
     */
    private const val GUIDANCE_HEIGHT_FRACTION = 0.46f

    /** Nombre de cases par ligne dans la grille de chiffres. */
    private const val TILE_COLUMNS = 2

    fun render(width: Int, height: Int, model: DashboardModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.02f).coerceIn(2f, 8f)
        val guidanceBottom = height * GUIDANCE_HEIGHT_FRACTION
        val guidanceArea = RectF(padding, padding, width - padding, guidanceBottom - padding / 2)

        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model)
            is GuidanceZone.Profile -> drawGraph(canvas, guidanceArea, guidance.model)
        }

        val separator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TRACK
            strokeWidth = 2f
        }
        canvas.drawLine(padding, guidanceBottom, width - padding, guidanceBottom, separator)

        drawTiles(
            canvas,
            RectF(padding, guidanceBottom, width - padding, height - padding),
            model.tiles,
            separator,
        )
        return bitmap
    }

    // --- Colonne de gauche : le graphe -------------------------------------------------

    private fun drawGraph(canvas: Canvas, area: RectF, model: RouteGraphModel) {
        val labelSize = (area.height() * 0.025f).coerceIn(9f, 16f)
        val window = model.window
        if (window.isEmpty || area.width() <= 0 || area.height() <= 0) {
            drawCentered(canvas, area, model.emptyMessage ?: "—", labelSize * 1.3f)
            return
        }

        val top = area.top + labelSize * 1.4f
        val bottom = area.bottom - labelSize * 1.4f
        val distanceSpan = window.distanceSpan.takeIf { it > 0 } ?: return
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return

        // La distance monte : le bas de la colonne est le début de la fenêtre.
        val axis = VerticalAxis(top, bottom, window.start, distanceSpan)
        fun y(distance: Double) = axis.y(distance)
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
                    moveTo(area.left, y(previous.distance))
                    lineTo(x(previous.elevation), y(previous.distance))
                    lineTo(x(current.elevation), y(current.distance))
                    lineTo(area.left, y(current.distance))
                    close()
                },
                fill,
            )
        }

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = FieldPalette.OUTLINE
        }
        val line = Path()
        points.forEachIndexed { index, point ->
            val px = x(point.elevation)
            val py = y(point.distance)
            if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        canvas.drawPath(line, outline)

        drawClimbBands(canvas, area, model, window, axis)
        drawPois(canvas, area, model, window, labelSize, axis)
        drawPosition(canvas, area, model, window, axis)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }
        model.zoomLabel?.let { canvas.drawText(it, area.left, area.top + labelSize, label) }
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

    private fun drawClimbBands(
        canvas: Canvas,
        area: RectF,
        model: RouteGraphModel,
        window: ProfileWindow,
        axis: VerticalAxis,
    ) {
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        model.climbs
            .filter { it.endDistance > window.start && it.startDistance < window.end }
            .forEach { climb ->
                val topY = axis.y(min(climb.endDistance, window.end))
                val bottomY = axis.y(max(climb.startDistance, window.start))
                if (bottomY - topY < 2f) return@forEach
                band.color = FieldPalette.translucent(FieldPalette.gradeColor(climb.grade), 45)
                canvas.drawRect(area.left, topY, area.right, bottomY, band)
            }
    }

    private fun drawPois(
        canvas: Canvas,
        area: RectF,
        model: RouteGraphModel,
        window: ProfileWindow,
        labelSize: Float,
        axis: VerticalAxis,
    ) {
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.POSITION
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_PRIMARY
            textSize = labelSize
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        var lastLabelY = Float.MAX_VALUE
        model.pois
            .filter { it.distance in window.start..window.end }
            .sortedBy { it.distance }
            .forEach { poi ->
                val poiY = axis.y(poi.distance)
                canvas.drawCircle(area.right - 4f, poiY, 4f, dot)
                // On saute les libellés qui se chevaucheraient.
                if (lastLabelY - poiY > labelSize * 1.2f || lastLabelY == Float.MAX_VALUE) {
                    canvas.drawText(poi.label, area.right - 10f, poiY - 3f, text)
                    lastLabelY = poiY
                }
            }
    }

    private fun drawPosition(
        canvas: Canvas,
        area: RectF,
        model: RouteGraphModel,
        window: ProfileWindow,
        axis: VerticalAxis,
    ) {
        if (model.position < window.start || model.position > window.end) return
        val positionY = axis.y(model.position)
        canvas.drawLine(
            area.left,
            positionY,
            area.right,
            positionY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FieldPalette.POSITION
                strokeWidth = 3f
            },
        )
        canvas.drawPath(
            Path().apply {
                moveTo(area.left, positionY)
                lineTo(area.left + 7f, positionY - 5f)
                lineTo(area.left + 7f, positionY + 5f)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FieldPalette.POSITION
                style = Paint.Style.FILL
            },
        )
    }

    // --- Colonne de droite : les chiffres ----------------------------------------------

    private fun drawTiles(canvas: Canvas, area: RectF, tiles: List<Tile>, separator: Paint) {
        if (tiles.isEmpty()) return
        val rows = (tiles.size + TILE_COLUMNS - 1) / TILE_COLUMNS
        val rowHeight = area.height() / rows
        val columnWidth = area.width() / TILE_COLUMNS

        tiles.forEachIndexed { index, tile ->
            val row = index / TILE_COLUMNS
            val column = index % TILE_COLUMNS
            val left = area.left + column * columnWidth
            val top = area.top + row * rowHeight
            drawTile(canvas, RectF(left, top, left + columnWidth, top + rowHeight), tile)

            if (column > 0) canvas.drawLine(left, top, left, top + rowHeight, separator)
            if (row > 0) canvas.drawLine(area.left, top, area.right, top, separator)
        }
    }

    private fun drawTile(canvas: Canvas, bounds: RectF, tile: Tile) {
        val labelSize = (bounds.height() * 0.2f).coerceIn(9f, 18f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(tile.label, bounds.left + 6f, bounds.top + labelSize * 1.1f, label)

        val unitSize = labelSize * 1.1f
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = unitSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val unitWidth = tile.unit?.let { unitPaint.measureText(it) + 6f } ?: 0f

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_PRIMARY
            typeface = Typeface.DEFAULT_BOLD
            textSize = fitTextSize(
                text = tile.value,
                maxWidth = bounds.width() - 12f - unitWidth,
                preferredSize = bounds.height() * 0.55f,
            )
        }
        val baseline = bounds.bottom - (bounds.height() - labelSize) * 0.16f
        canvas.drawText(tile.value, bounds.left + 6f, baseline, valuePaint)

        tile.unit?.let {
            canvas.drawText(
                it,
                bounds.left + 6f + valuePaint.measureText(tile.value) + 6f,
                baseline,
                unitPaint,
            )
        }
    }

    // --- Utilitaires --------------------------------------------------------------------

    private fun drawCentered(canvas: Canvas, area: RectF, message: String, size: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val centerY = area.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(message, area.centerX(), centerY, paint)
    }

    private fun fitTextSize(text: String, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 120f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }
}
