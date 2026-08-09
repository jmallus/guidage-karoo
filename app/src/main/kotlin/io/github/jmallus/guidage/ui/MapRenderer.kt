package io.github.jmallus.guidage.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.PlanePoint
import kotlin.math.hypot

/** Un point d'intérêt à poser sur la carte. */
data class MapPoi(val position: GeoPoint, val label: String)

/** Ce qu'affiche la minicarte. */
data class MapModel(
    /** Tracé de l'itinéraire. */
    val path: List<GeoPoint> = emptyList(),
    /** Position du coureur ; sans elle, rien ne peut être orienté. */
    val position: GeoPoint? = null,
    /** Cap en degrés (0 = nord). Null quand il est inconnu : la carte reste alors nord en haut. */
    val heading: Double? = null,
    val pois: List<MapPoi> = emptyList(),
    /** Distance visible devant le coureur (m). */
    val rangeMeters: Double = 1_000.0,
    val emptyMessage: String? = null,
)

/**
 * Minicarte orientée « cap en haut », à la manière d'un GPS de voiture : le coureur est
 * fixe dans le bas de la vue, la carte tourne autour de lui, et ce qui est devant est en haut.
 *
 * Seul le tracé de l'itinéraire est dessiné, sans fond de carte : tout est calculé sur
 * l'appareil, sans réseau ni tuiles à télécharger.
 */
object MapRenderer {

    /** Part de la hauteur laissée devant le coureur. */
    private const val AHEAD_FRACTION = 0.80f

    fun draw(canvas: Canvas, area: RectF, model: MapModel, palette: Palette) {
        val origin = model.position
        if (origin == null || area.width() <= 0 || area.height() <= 0) {
            drawMessage(canvas, area, model.emptyMessage, palette)
            return
        }

        val riderX = area.centerX()
        val riderY = area.top + area.height() * AHEAD_FRACTION
        val metersToPixels =
            ((area.height() * AHEAD_FRACTION) / model.rangeMeters.coerceAtLeast(1.0)).toFloat()
        val heading = model.heading ?: 0.0
        val projection = Projection(origin, heading, riderX, riderY, metersToPixels)

        canvas.save()
        canvas.clipRect(area)

        drawPath(canvas, model, projection, palette)
        drawPois(canvas, area, model, projection, palette)
        drawRider(canvas, riderX, riderY, area.height(), palette)
        drawScaleBar(canvas, area, model.rangeMeters, metersToPixels, palette)

        canvas.restore()
    }

    /** Position géographique → pixels, cap en haut et coureur fixe. */
    private class Projection(
        private val origin: GeoPoint,
        private val heading: Double,
        private val riderX: Float,
        private val riderY: Float,
        private val metersToPixels: Float,
    ) {
        fun toScreen(point: GeoPoint): PlanePoint {
            val plane = Geo.toTrackUpPlane(origin, heading, point)
            return PlanePoint(
                x = riderX + plane.x * metersToPixels,
                y = riderY - plane.y * metersToPixels,
            )
        }
    }

    private fun drawPath(canvas: Canvas, model: MapModel, projection: Projection, palette: Palette) {
        if (model.path.size < 2) return
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.routeLine
        }
        val outline = Paint(line).apply {
            strokeWidth = 13f
            color = palette.routeOutline
        }

        val screenPoints = model.path.map { projection.toScreen(it) }
        val path = Path()
        screenPoints.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(point.x.toFloat(), point.y.toFloat())
            } else {
                path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
        }
        canvas.drawPath(path, outline)
        canvas.drawPath(path, line)
        drawDirectionChevrons(canvas, screenPoints, palette)
    }

    /**
     * Chevrons semés le long du tracé pour indiquer le sens de la marche, comme sur la
     * carte native du Karoo.
     */
    private fun drawDirectionChevrons(canvas: Canvas, points: List<PlanePoint>, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.routeOutline
        }

        var carry = 0f
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            val dx = (to.x - from.x).toFloat()
            val dy = (to.y - from.y).toFloat()
            val length = hypot(dx, dy)
            if (length < 0.01f) continue

            val ux = dx / length
            val uy = dy / length
            var position = CHEVRON_SPACING - carry
            while (position <= length) {
                drawChevron(
                    canvas = canvas,
                    x = from.x.toFloat() + ux * position,
                    y = from.y.toFloat() + uy * position,
                    ux = ux,
                    uy = uy,
                    paint = paint,
                )
                position += CHEVRON_SPACING
            }
            carry = (carry + length) % CHEVRON_SPACING
        }
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, ux: Float, uy: Float, paint: Paint) {
        val size = CHEVRON_SIZE
        // Perpendiculaire à la direction de marche.
        val px = -uy
        val py = ux
        val tipX = x + ux * size
        val tipY = y + uy * size
        canvas.drawPath(
            Path().apply {
                moveTo(tipX - ux * size * 1.6f + px * size * 0.9f, tipY - uy * size * 1.6f + py * size * 0.9f)
                lineTo(tipX, tipY)
                lineTo(tipX - ux * size * 1.6f - px * size * 0.9f, tipY - uy * size * 1.6f - py * size * 0.9f)
            },
            paint,
        )
    }

    private fun drawPois(
        canvas: Canvas,
        area: RectF,
        model: MapModel,
        projection: Projection,
        palette: Palette,
    ) {
        if (model.pois.isEmpty()) return
        val labelSize = (area.height() * 0.05f).coerceIn(10f, 16f)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.position
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }

        model.pois.forEach { poi ->
            val screen = projection.toScreen(poi.position)
            val x = screen.x.toFloat()
            val y = screen.y.toFloat()
            if (x < area.left - 20 || x > area.right + 20 || y < area.top - 20 || y > area.bottom + 20) return@forEach
            canvas.drawCircle(x, y, 5f, dot)
            canvas.drawText(poi.label, x + 8f, y + labelSize * 0.35f, text)
        }
    }

    /**
     * Flèche de position reprenant celle de la navigation Karoo : un chevron élancé,
     * franchement pointé vers l'avant, cerné de sombre pour rester lisible au-dessus du tracé.
     *
     * Les angles sont adoucis en épaississant le contour au lieu d'arrondir le tracé point
     * par point : une jointure ronde d'épaisseur *r* arrondit d'un rayon *r* les quatre
     * sommets d'un coup. Le contour étant tracé vers l'extérieur, la silhouette est calculée
     * en retrait d'autant pour que la flèche garde sa taille.
     */
    private fun drawRider(canvas: Canvas, x: Float, y: Float, height: Float, palette: Palette) {
        val size = (height * 0.085f).coerceIn(14f, 30f)
        val radius = size * CORNER_RATIO
        val body = size - radius
        val arrow = Path().apply {
            moveTo(x, y - body)
            lineTo(x + body * 0.62f, y + body * 0.72f)
            lineTo(x, y + body * 0.16f)
            lineTo(x - body * 0.62f, y + body * 0.72f)
            close()
        }
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ARROW_BORDER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = radius * 2 + ARROW_BORDER_WIDTH * 2
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            },
        )
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ARROW_COLOR
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = radius * 2
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            },
        )
    }

    private const val ARROW_COLOR = 0xFFE6E24C.toInt()
    private const val ARROW_BORDER_COLOR = 0xFF1E1E1E.toInt()
    private const val ARROW_BORDER_WIDTH = 2.5f

    /** Rayon d'arrondi des sommets de la flèche, en part de sa taille. */
    private const val CORNER_RATIO = 0.22f

    /** Espacement des chevrons de direction, en pixels. */
    private const val CHEVRON_SPACING = 42f
    private const val CHEVRON_SIZE = 4.5f

    private fun drawScaleBar(
        canvas: Canvas,
        area: RectF,
        rangeMeters: Double,
        metersToPixels: Float,
        palette: Palette,
    ) {
        val scaleMeters = Geo.niceScale(rangeMeters / 2)
        val barWidth = scaleMeters.toFloat() * metersToPixels
        if (barWidth < 10f || barWidth > area.width()) return

        val labelSize = (area.height() * 0.045f).coerceIn(9f, 15f)
        val y = area.bottom - labelSize * 0.5f
        val left = area.left + 6f

        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            strokeWidth = 3f
        }
        canvas.drawLine(left, y, left + barWidth, y, bar)
        canvas.drawLine(left, y - 4f, left, y + 4f, bar)
        canvas.drawLine(left + barWidth, y - 4f, left + barWidth, y + 4f, bar)

        val label = if (scaleMeters >= 1_000) "${(scaleMeters / 1_000).toInt()} km" else "${scaleMeters.toInt()} m"
        canvas.drawText(
            label,
            left,
            y - 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                textSize = labelSize
                typeface = Typeface.DEFAULT_BOLD
            },
        )
    }

    private fun drawMessage(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = (area.height() * 0.1f).coerceIn(10f, 22f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            message ?: "—",
            area.centerX(),
            area.centerY() - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
    }
}
