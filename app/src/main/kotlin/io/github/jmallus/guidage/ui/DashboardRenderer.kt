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

/** Une case de chiffres : la valeur et son unité, sans libellé. */
data class Tile(val value: String, val unit: String? = null)

/** Ce qu'on affiche dans la colonne de guidage, à droite des mesures. */
sealed interface GuidanceZone {
    data class Map(val model: MapModel) : GuidanceZone

    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le champ plein écran.
 *
 * Colonne gauche : les quatre mesures de l'effort empilées. Colonne droite, sur la même
 * hauteur : le guidage. Sous les deux : distance restante et heure d'arrivée.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    /** Vitesse, puissance, fréquence cardiaque, cadence. */
    val tiles: List<Tile>,
    /** Distance restante et heure d'arrivée. */
    val footerTiles: List<Tile>,
    val palette: Palette,
)

object DashboardRenderer {

    /** Largeur de la colonne des mesures. */
    private const val TILE_COLUMN_FRACTION = 0.5f

    /** Hauteur occupée par les mesures et le guidage ; le reste va à la dernière ligne. */
    private const val MAIN_HEIGHT_FRACTION = 0.84f

    /** Hauteur des chiffres, en part de la hauteur de la case. */
    private const val VALUE_HEIGHT_FRACTION = 0.61f
    private const val FOOTER_VALUE_HEIGHT_FRACTION = 0.57f

    /** Hauteur de l'unité, écrite sous la valeur. */
    private const val UNIT_HEIGHT_FRACTION = 0.15f
    private const val FOOTER_UNIT_HEIGHT_FRACTION = 0.17f

    fun render(width: Int, height: Int, model: DashboardModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.015f).coerceIn(2f, 6f)
        val columnSplit = width * TILE_COLUMN_FRACTION
        val mainBottom = height * MAIN_HEIGHT_FRACTION

        // Colonne gauche : les quatre mesures.
        val rowHeight = (mainBottom - padding) / model.tiles.size.coerceAtLeast(1)
        drawTiles(
            canvas = canvas,
            bounds = model.tiles.indices.map { index ->
                val top = padding + index * rowHeight
                RectF(padding, top, columnSplit, top + rowHeight)
            },
            tiles = model.tiles,
            palette = model.palette,
            valueFraction = VALUE_HEIGHT_FRACTION,
            unitFraction = UNIT_HEIGHT_FRACTION,
        )

        // Colonne droite : le guidage, sur toute la hauteur des mesures.
        val guidanceArea = RectF(columnSplit, padding, width - padding, mainBottom)
        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model, model.palette)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, guidanceArea, guidance.model, model.palette)
        }

        // Ligne du bas : restant et arrivée.
        val footerWidth = (width - 2 * padding) / model.footerTiles.size.coerceAtLeast(1)
        drawTiles(
            canvas = canvas,
            bounds = model.footerTiles.indices.map { index ->
                val left = padding + index * footerWidth
                RectF(left, mainBottom, left + footerWidth, height - padding)
            },
            tiles = model.footerTiles,
            palette = model.palette,
            valueFraction = FOOTER_VALUE_HEIGHT_FRACTION,
            unitFraction = FOOTER_UNIT_HEIGHT_FRACTION,
        )
        return bitmap
    }

    // --- Cases de chiffres ---------------------------------------------------------------

    /**
     * Dessine un groupe de cases avec une seule et même taille de chiffres.
     *
     * La taille retenue est la plus grande qui convienne à *toutes* les valeurs du groupe :
     * si on ajustait chaque case indépendamment, « 38,5 » serait écrit nettement plus petit
     * que « 245 » et l'œil ne saurait plus quelle valeur est laquelle.
     */
    private fun drawTiles(
        canvas: Canvas,
        bounds: List<RectF>,
        tiles: List<Tile>,
        palette: Palette,
        valueFraction: Float,
        unitFraction: Float,
    ) {
        if (tiles.isEmpty() || bounds.isEmpty()) return
        val cellHeight = bounds.first().height()
        val unitSize = (cellHeight * unitFraction).coerceIn(9f, 26f)
        val preferred = cellHeight * valueFraction
        val valueSize = tiles.zip(bounds).minOf { (tile, box) ->
            fitTextSize(tile.value, box.width() - 12f, preferred)
        }

        tiles.zip(bounds).forEach { (tile, box) ->
            drawTile(canvas, box, tile, palette, valueSize, unitSize)
        }
    }

    /**
     * Une case sans bordure ni libellé : la valeur, et son unité en plus petit juste dessous.
     *
     * L'unité sous la valeur plutôt qu'à côté libère toute la largeur de la case pour les
     * chiffres, qui sont ce qu'on lit en roulant.
     */
    private fun drawTile(
        canvas: Canvas,
        bounds: RectF,
        tile: Tile,
        palette: Palette,
        valueSize: Float,
        unitSize: Float,
    ) {
        val valuePaint = paint(valueSize, palette.textPrimary).apply { textAlign = Paint.Align.CENTER }
        val unitPaint = paint(unitSize, palette.textSecondary).apply { textAlign = Paint.Align.CENTER }

        val valueHeight = valuePaint.descent() - valuePaint.ascent()
        val unitHeight = if (tile.unit == null) 0f else unitPaint.descent() - unitPaint.ascent()
        val top = bounds.centerY() - (valueHeight + unitHeight) / 2f

        canvas.drawText(tile.value, bounds.centerX(), top - valuePaint.ascent(), valuePaint)
        tile.unit?.let {
            canvas.drawText(it, bounds.centerX(), top + valueHeight - unitPaint.ascent(), unitPaint)
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
