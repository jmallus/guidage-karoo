package io.github.jmallus.guidage.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.PlanePoint

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
    val rangeMeters: Double = 2_000.0,
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
    private const val AHEAD_FRACTION = 0.78f

    fun draw(canvas: Canvas, area: RectF, model: MapModel) {
        val origin = model.position
        if (origin == null || area.width() <= 0 || area.height() <= 0) {
            drawMessage(canvas, area, model.emptyMessage)
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

        drawPath(canvas, model, projection)
        drawPois(canvas, area, model, projection)
        drawRider(canvas, riderX, riderY, area.height())
        drawScaleBar(canvas, area, model.rangeMeters, metersToPixels)

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

    private fun drawPath(canvas: Canvas, model: MapModel, projection: Projection) {
        if (model.path.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ROUTE_COLOR
        }
        val outline = Paint(paint).apply {
            strokeWidth = 9f
            color = ROUTE_OUTLINE_COLOR
        }

        val path = Path()
        model.path.forEachIndexed { index, point ->
            val screen = projection.toScreen(point)
            if (index == 0) {
                path.moveTo(screen.x.toFloat(), screen.y.toFloat())
            } else {
                path.lineTo(screen.x.toFloat(), screen.y.toFloat())
            }
        }
        canvas.drawPath(path, outline)
        canvas.drawPath(path, paint)
    }

    private fun drawPois(
        canvas: Canvas,
        area: RectF,
        model: MapModel,
        projection: Projection,
    ) {
        if (model.pois.isEmpty()) return
        val labelSize = (area.height() * 0.07f).coerceIn(10f, 18f)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.POSITION
            style = Paint.Style.FILL
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_PRIMARY
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }

        model.pois.forEach { poi ->
            val screen = projection.toScreen(poi.position)
            val x = screen.x.toFloat()
            val y = screen.y.toFloat()
            if (x < area.left - 20 || x > area.right + 20 || y < area.top - 20 || y > area.bottom + 20) return@forEach
            canvas.drawCircle(x, y, 7f, halo)
            canvas.drawCircle(x, y, 5f, dot)
            canvas.drawText(poi.label, x + 9f, y + labelSize * 0.35f, text)
        }
    }

    private fun drawRider(canvas: Canvas, x: Float, y: Float, height: Float) {
        val size = (height * 0.06f).coerceIn(8f, 18f)
        val body = Path().apply {
            moveTo(x, y - size)
            lineTo(x + size * 0.75f, y + size * 0.7f)
            lineTo(x, y + size * 0.3f)
            lineTo(x - size * 0.75f, y + size * 0.7f)
            close()
        }
        canvas.drawPath(
            body,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = RIDER_COLOR
                style = Paint.Style.FILL
            },
        )
        canvas.drawPath(
            body,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FieldPalette.OUTLINE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            },
        )
    }

    private fun drawScaleBar(canvas: Canvas, area: RectF, rangeMeters: Double, metersToPixels: Float) {
        val scaleMeters = Geo.niceScale(rangeMeters / 2)
        val barWidth = (scaleMeters * metersToPixels).toFloat()
        if (barWidth < 10f || barWidth > area.width()) return

        val labelSize = (area.height() * 0.07f).coerceIn(9f, 16f)
        val y = area.bottom - labelSize * 0.6f
        val left = area.left + 6f

        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_PRIMARY
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
                color = FieldPalette.TEXT_PRIMARY
                textSize = labelSize
                typeface = Typeface.DEFAULT_BOLD
            },
        )
    }

    private fun drawMessage(canvas: Canvas, area: RectF, message: String?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.TEXT_SECONDARY
            textSize = (area.height() * 0.16f).coerceIn(10f, 26f)
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

    private const val ROUTE_COLOR = 0xFFFFD400.toInt()
    private const val ROUTE_OUTLINE_COLOR = 0xFF4E4300.toInt()
    private const val RIDER_COLOR = 0xFF1565C0.toInt()
}
