package io.github.jmallus.guidage.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.PlanePoint
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.fromMicroDegrees
import kotlin.math.hypot
import kotlin.math.ln

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
    /** Voies du fond de carte, quand un fond est installé. */
    val roads: List<RoadSegment> = emptyList(),
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

        // Le fond clair est posé même sans voies : c'est lui qui rend la carte lisible au
        // soleil, l'écran du Karoo réfléchissant la lumière au lieu de lutter contre elle.
        canvas.drawRect(area, Paint().apply { color = RoadStyle.BACKGROUND })
        if (model.roads.isNotEmpty()) {
            drawRoads(canvas, model.roads, projection, metersToPixels)
        }
        drawPath(canvas, model, projection, palette, routeWidth(model.rangeMeters))
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

        /** Abscisse écran, sans allouer de point intermédiaire. */
        fun screenX(latitude: Double, longitude: Double): Float {
            val plane = Geo.toTrackUpPlane(origin, heading, GeoPoint(latitude, longitude))
            return (riderX + plane.x * metersToPixels).toFloat()
        }

        fun screenY(latitude: Double, longitude: Double): Float {
            val plane = Geo.toTrackUpPlane(origin, heading, GeoPoint(latitude, longitude))
            return (riderY - plane.y * metersToPixels).toFloat()
        }
    }

    /**
     * Voies du fond de carte, sous le tracé.
     *
     * Elles sont dessinées de la plus fine à la plus large, de sorte qu'une nationale
     * passe par-dessus le chemin qui la longe et non l'inverse. Chaque famille est tracée
     * d'un seul tenant : changer de pinceau coûte plus cher que de dessiner.
     */
    private fun drawRoads(
        canvas: Canvas,
        roads: List<RoadSegment>,
        projection: Projection,
        metersToPixels: Float,
    ) {
        roads
            .groupBy { Triple(it.kind, it.surface, RoadStyle.isDashed(it.kind, it.surface)) }
            .entries
            .sortedBy { RoadStyle.widthMeters(it.key.first) }
            .forEach { (style, segments) ->
                val (kind, surface, dashed) = style
                val width = (RoadStyle.widthMeters(kind) * metersToPixels)
                    .coerceIn(MIN_ROAD_WIDTH, MAX_ROAD_WIDTH)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = RoadStyle.color(kind, surface)
                    if (dashed) {
                        val dash = (width * 2.5f).coerceAtLeast(4f)
                        pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.8f), 0f)
                    }
                }

                val path = Path()
                segments.forEach { segment ->
                    for (index in 0 until segment.size) {
                        val latitude = segment.latitudes[index].fromMicroDegrees()
                        val longitude = segment.longitudes[index].fromMicroDegrees()
                        val x = projection.screenX(latitude, longitude)
                        val y = projection.screenY(latitude, longitude)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                canvas.drawPath(path, paint)
            }
    }

    private const val MIN_ROAD_WIDTH = 1f
    private const val MAX_ROAD_WIDTH = 26f

    private fun drawPath(
        canvas: Canvas,
        model: MapModel,
        projection: Projection,
        palette: Palette,
        width: Float,
    ) {
        if (model.path.size < 2) return
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.routeLine
        }
        val outline = Paint(line).apply {
            strokeWidth = width + ROUTE_OUTLINE_WIDTH * 2
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
        drawDirectionChevrons(canvas, screenPoints, palette, width)
    }

    /**
     * Épaisseur du tracé, décroissante avec la portée affichée.
     *
     * Une épaisseur fixe ne peut pas convenir aux deux bouts de la plage : à 200 m elle
     * représente un ruban de quelques mètres, à 10 km une bande de plusieurs centaines. La
     * décroissance suit le logarithme de la portée plutôt que la portée elle-même — sinon
     * le trait s'effondrerait dès le premier cran et resterait filiforme sur toute la
     * moitié haute de la plage.
     */
    private fun routeWidth(rangeMeters: Double): Float {
        val range = rangeMeters.coerceIn(MIN_RANGE, MAX_RANGE)
        val ratio = ln(range / MIN_RANGE) / ln(MAX_RANGE / MIN_RANGE)
        return (ROUTE_WIDTH_NEAR - (ROUTE_WIDTH_NEAR - ROUTE_WIDTH_FAR) * ratio).toFloat()
    }

    /**
     * Chevrons semés le long du tracé pour indiquer le sens de la marche, comme sur la
     * carte native du Karoo : jaunes et cernés de noir, dans les couleurs de la flèche de
     * position.
     *
     * Le cerne n'est pas un ornement. Un chevron d'une seule couleur disparaît dès que le
     * tracé passe sur un fond de la même valeur — et le tracé est justement clair. Deux
     * passes, l'une plus large et sombre puis l'autre claire, le tiennent lisible partout.
     */
    private fun drawDirectionChevrons(
        canvas: Canvas,
        points: List<PlanePoint>,
        palette: Palette,
        routeWidth: Float,
    ) {
        // Les chevrons débordent le ruban de part et d'autre, cerne compris : c'est ce
        // débord qui les fait lire comme des pointes posées dessus. Contenus dans la
        // largeur du tracé, ils s'y noieraient — le tracé est jaune comme eux.
        val spacing = routeWidth * CHEVRON_SPACING_RATIO
        val stroke = routeWidth * CHEVRON_STROKE_RATIO
        val borderWidth = stroke + (stroke * CHEVRON_BORDER_RATIO).coerceAtLeast(1.6f) * 2
        val halfSpan =
            routeWidth / 2 + ROUTE_OUTLINE_WIDTH + routeWidth * CHEVRON_OVERHANG_RATIO
        val size = ((halfSpan - borderWidth / 2) / CHEVRON_ARM).coerceAtLeast(routeWidth * 0.4f)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ARROW_BORDER_COLOR
        }
        val fill = Paint(border).apply {
            strokeWidth = stroke
            color = ARROW_COLOR
        }

        // Les chevrons sont accumulés puis dessinés en deux passes : sans cela, le cerne du
        // chevron suivant viendrait mordre le jaune du précédent quand ils se serrent.
        val chevrons = Path()
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
            var position = spacing - carry
            while (position <= length) {
                addChevron(
                    path = chevrons,
                    x = from.x.toFloat() + ux * position,
                    y = from.y.toFloat() + uy * position,
                    ux = ux,
                    uy = uy,
                    size = size,
                )
                position += spacing
            }
            carry = (carry + length) % spacing
        }
        canvas.drawPath(chevrons, border)
        canvas.drawPath(chevrons, fill)
    }

    private fun addChevron(path: Path, x: Float, y: Float, ux: Float, uy: Float, size: Float) {
        // Perpendiculaire à la direction de marche.
        val px = -uy
        val py = ux
        val tipX = x + ux * size
        val tipY = y + uy * size
        val backX = tipX - ux * size * CHEVRON_SWEEP
        val backY = tipY - uy * size * CHEVRON_SWEEP
        val armX = px * size * CHEVRON_ARM
        val armY = py * size * CHEVRON_ARM
        path.moveTo(backX + armX, backY + armY)
        path.lineTo(tipX, tipY)
        path.lineTo(backX - armX, backY - armY)
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
            color = POI_COLOR
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
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
     * Flèche de position reprenant celle de la navigation Karoo : une pointe large, presque
     * aussi étalée que haute, échancrée à la base, cernée de sombre pour rester lisible
     * au-dessus du tracé.
     *
     * L'écartement des branches est relevé sur l'appareil : une flèche étroite se confond
     * avec les chevrons du tracé, alors que celle-ci se lit d'emblée comme « moi ». Sa
     * hauteur, elle, ne change pas.
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
            lineTo(x + body * ARROW_HALF_WIDTH, y + body * ARROW_BASE)
            lineTo(x, y + body * ARROW_NOTCH)
            lineTo(x - body * ARROW_HALF_WIDTH, y + body * ARROW_BASE)
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

    /** Point d'intérêt : un bleu soutenu, seule couleur froide de la carte. */
    private const val POI_COLOR = 0xFF1B5E9A.toInt()

    private const val ARROW_COLOR = 0xFFE6E24C.toInt()
    private const val ARROW_BORDER_COLOR = 0xFF1E1E1E.toInt()
    private const val ARROW_BORDER_WIDTH = 2.5f

    /**
     * Rayon d'arrondi des sommets de la flèche, en part de sa taille.
     *
     * Juste de quoi ôter l'agressivité des pointes : au-delà, la flèche s'émousse et perd
     * la franchise de direction qui fait tout son intérêt. Sa taille hors tout n'en dépend
     * pas — la silhouette est calculée en retrait du rayon.
     */
    private const val CORNER_RATIO = 0.10f

    /**
     * Silhouette de la flèche, en part de sa demi-hauteur : demi-largeur, ordonnée des
     * branches, fond de l'échancrure. La largeur vaut ainsi 0,95 fois la hauteur totale,
     * proportion relevée sur la flèche de la navigation Karoo.
     */
    private const val ARROW_HALF_WIDTH = 0.82f
    private const val ARROW_BASE = 0.72f
    private const val ARROW_NOTCH = 0.19f

    /** Épaisseur du tracé aux deux bouts de la plage de portées, et cerne. */
    private const val ROUTE_WIDTH_NEAR = 14.0
    private const val ROUTE_WIDTH_FAR = 6.0
    private const val ROUTE_OUTLINE_WIDTH = 4f

    /** Bornes de la plage de portées, reprises de ZoomLevels. */
    private const val MIN_RANGE = 200.0
    private const val MAX_RANGE = 10_000.0

    /** Chevrons de direction, exprimés en part de l'épaisseur du tracé. */
    private const val CHEVRON_SPACING_RATIO = 4.3f
    private const val CHEVRON_STROKE_RATIO = 0.43f

    /** Débord du chevron au-delà du bord du tracé, cerne du tracé compris. */
    private const val CHEVRON_OVERHANG_RATIO = 0.35f

    /** Cerne du chevron, en part de son propre trait. */
    private const val CHEVRON_BORDER_RATIO = 0.35f

    /** Recul des branches derrière la pointe, et leur écartement, en part de la taille. */
    private const val CHEVRON_SWEEP = 1.6f
    private const val CHEVRON_ARM = 0.9f

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
            color = RoadStyle.INK
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
                color = RoadStyle.INK
                textSize = labelSize
                typeface = Typeface.DEFAULT_BOLD
            },
        )
    }

    private fun drawMessage(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        canvas.drawRect(area, Paint().apply { color = RoadStyle.BACKGROUND })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
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
