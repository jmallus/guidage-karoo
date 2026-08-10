package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.github.jmallus.guidage.core.Contrast
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/**
 * Une case du tableau de bord : un libellé surmontant une valeur.
 *
 * [suffix] est la fin de la valeur écrite en plus petit — la décimale, le signe pour cent.
 * Les chiffres qui comptent gardent ainsi leur pleine hauteur sans que la case déborde.
 */
data class Tile(
    val label: String,
    val value: String,
    val suffix: String? = null,
    /** Aplat de fond, ou null pour laisser le fond de l'écran. */
    val background: Int? = null,
    /** Icône posée devant le libellé. */
    @DrawableRes val icon: Int? = null,
)

/**
 * Bandeau de montée : le profil de la côte en cours ou toute proche.
 *
 * Il n'apparaît que lorsque le Karoo a identifié une côte ; le reste du temps la place
 * revient aux autres champs.
 */
data class ClimbBandModel(
    val window: ProfileWindow,
    /** Position courante sur l'itinéraire (m), pour placer le repère du coureur. */
    val position: Double,
    /** Altitude à cette position (m) ; sans elle le repère se pose sur le profil. */
    val positionElevation: Double?,
)

/** Ce qu'on affiche dans la colonne de guidage, à droite des mesures. */
sealed interface GuidanceZone {
    data class Map(val model: MapModel) : GuidanceZone

    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le champ plein écran.
 *
 * Colonne gauche : quatre mesures de l'effort empilées. Colonne droite : le guidage sur
 * trois de ces quatre hauteurs, la quatrième revenant à la pente. Sous les deux colonnes :
 * distance restante et heure d'arrivée.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    /** Vitesse, puissance, fréquence cardiaque, cadence. */
    val tiles: List<Tile>,
    /** Case sous le guidage : la pente. */
    val sideTile: Tile?,
    /** Distance restante et heure d'arrivée. */
    val footerTiles: List<Tile>,
    /** Profil de la côte, sous la dernière ligne, quand il y en a une. */
    val climbBand: ClimbBandModel? = null,
    val palette: Palette,
)

object DashboardRenderer {

    /** Largeur de la colonne des mesures. */
    private const val TILE_COLUMN_FRACTION = 0.5f

    /** Hauteur occupée par les mesures et le guidage ; le reste va à la dernière ligne. */
    private const val MAIN_HEIGHT_FRACTION = 0.84f

    /** Nombre de hauteurs de case occupées par le guidage. */
    private const val GUIDANCE_ROWS = 3

    /** Proportions relevées sur la maquette, exprimées en part de la hauteur de case. */
    private const val VALUE_HEIGHT_FRACTION = 0.614f
    private const val LABEL_HEIGHT_FRACTION = 0.147f
    private const val FOOTER_VALUE_HEIGHT_FRACTION = 0.569f
    private const val FOOTER_LABEL_HEIGHT_FRACTION = 0.165f

    /** Taille du suffixe, en part de celle de la valeur. */
    private const val SUFFIX_RATIO = 0.52f

    /** Hauteur du bandeau de montée, quand il y en a un. */
    private const val CLIMB_BAND_FRACTION = 0.10f

    fun render(context: Context, width: Int, height: Int, model: DashboardModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.015f).coerceIn(2f, 6f)
        val columnSplit = width * TILE_COLUMN_FRACTION

        // Le bandeau de montée mange le bas de l'écran ; tout le reste se serre au-dessus.
        val bandHeight = if (model.climbBand == null) 0f else height * CLIMB_BAND_FRACTION
        val contentHeight = height - bandHeight
        val mainBottom = contentHeight * MAIN_HEIGHT_FRACTION
        val rowHeight = (mainBottom - padding) / model.tiles.size.coerceAtLeast(1)

        // Colonne gauche : les quatre mesures.
        drawTiles(
            context = context,
            canvas = canvas,
            bounds = model.tiles.indices.map { index ->
                val top = padding + index * rowHeight
                RectF(padding, top, columnSplit, top + rowHeight)
            },
            tiles = model.tiles,
            palette = model.palette,
            valueFraction = VALUE_HEIGHT_FRACTION,
            labelFraction = LABEL_HEIGHT_FRACTION,
        )

        // Colonne droite : le guidage sur trois hauteurs de case…
        val guidanceBottom = padding + GUIDANCE_ROWS * rowHeight
        val guidanceArea = RectF(columnSplit, padding, width - padding, guidanceBottom)
        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model, model.palette)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, guidanceArea, guidance.model, model.palette)
        }

        // … et la pente sur la quatrième.
        model.sideTile?.let { tile ->
            drawTiles(
                context = context,
                canvas = canvas,
                bounds = listOf(RectF(columnSplit, guidanceBottom, width - padding, mainBottom)),
                tiles = listOf(tile),
                palette = model.palette,
                valueFraction = VALUE_HEIGHT_FRACTION,
                labelFraction = LABEL_HEIGHT_FRACTION,
            )
        }

        // Ligne du bas : restant et arrivée.
        val footerWidth = (width - 2 * padding) / model.footerTiles.size.coerceAtLeast(1)
        drawTiles(
            context = context,
            canvas = canvas,
            bounds = model.footerTiles.indices.map { index ->
                val left = padding + index * footerWidth
                RectF(left, mainBottom, left + footerWidth, contentHeight - padding)
            },
            tiles = model.footerTiles,
            palette = model.palette,
            valueFraction = FOOTER_VALUE_HEIGHT_FRACTION,
            labelFraction = FOOTER_LABEL_HEIGHT_FRACTION,
        )

        // Tout en bas : le profil de la côte, sur toute la largeur.
        model.climbBand?.let { band ->
            drawClimbBand(
                canvas = canvas,
                area = RectF(padding, contentHeight, width - padding, height.toFloat()),
                model = band,
                palette = model.palette,
            )
        }
        return bitmap
    }

    // --- Bandeau de montée -----------------------------------------------------------------

    /**
     * Profil de la côte : une silhouette colorée par la pente, surmontée d'un filet, avec
     * un repère à la position du coureur et un autre au sommet.
     *
     * La couleur est portée par la silhouette plutôt que par une courbe : c'est ce qui se
     * lit d'un coup d'œil sur une bande de quelques dizaines de pixels de haut.
     */
    private fun drawClimbBand(canvas: Canvas, area: RectF, model: ClimbBandModel, palette: Palette) {
        val window = model.window
        if (window.isEmpty || area.width() <= 0 || area.height() <= 0) return
        val distanceSpan = window.distanceSpan.takeIf { it > 0 } ?: return
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return

        fun x(distance: Double) =
            area.left + ((distance - window.start) / distanceSpan * area.width()).toFloat()

        fun y(elevation: Double) =
            area.bottom - ((elevation - window.minElevation) / elevationSpan * area.height()).toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val points = window.points
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val length = current.distance - previous.distance
            if (length <= 0) continue
            val grade = (current.elevation - previous.elevation) / length * 100.0
            fill.color = FieldPalette.gradeColor(grade)
            canvas.drawPath(
                Path().apply {
                    moveTo(x(previous.distance), area.bottom)
                    lineTo(x(previous.distance), y(previous.elevation))
                    lineTo(x(current.distance), y(current.elevation))
                    lineTo(x(current.distance), area.bottom)
                    close()
                },
                fill,
            )
        }

        val ridge = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) {
                ridge.moveTo(x(point.distance), y(point.elevation))
            } else {
                ridge.lineTo(x(point.distance), y(point.elevation))
            }
        }
        canvas.drawPath(
            ridge,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                strokeJoin = Paint.Join.ROUND
                color = palette.position
            },
        )

        val markerRadius = (area.height() * 0.13f).coerceIn(4f, 9f)
        points.lastOrNull()?.let { summit ->
            drawMarker(canvas, x(summit.distance), y(summit.elevation), markerRadius, SUMMIT_MARKER)
        }
        if (model.position in window.start..window.end) {
            val elevation = model.positionElevation ?: elevationAt(points, model.position)
            drawMarker(canvas, x(model.position), y(elevation), markerRadius, RIDER_MARKER)
        }
    }

    /** Repère circulaire cerné de sombre, pour rester visible sur toutes les pentes. */
    private fun drawMarker(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        canvas.drawCircle(
            x,
            y,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            },
        )
        canvas.drawCircle(
            x,
            y,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = MARKER_BORDER
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            },
        )
    }

    /** Altitude interpolée sur les points de la fenêtre, faute de mesure directe. */
    private fun elevationAt(points: List<ProfilePoint>, distance: Double): Double {
        val next = points.indexOfFirst { it.distance >= distance }
        if (next <= 0) return points.first().elevation
        val before = points[next - 1]
        val after = points[next]
        val span = after.distance - before.distance
        if (span <= 0) return after.elevation
        val ratio = (distance - before.distance) / span
        return before.elevation + (after.elevation - before.elevation) * ratio
    }

    private const val RIDER_MARKER = 0xFFF2C037.toInt()
    private const val SUMMIT_MARKER = 0xFFFFFFFF.toInt()
    private const val MARKER_BORDER = 0xFF1A1A1A.toInt()

    // --- Cases de chiffres ---------------------------------------------------------------

    /**
     * Dessine un groupe de cases avec une seule et même taille de chiffres.
     *
     * La taille retenue est la plus grande qui convienne à *toutes* les valeurs du groupe :
     * si on ajustait chaque case indépendamment, « 38,5 » serait écrit nettement plus petit
     * que « 245 » et l'œil ne saurait plus quelle valeur est laquelle.
     */
    private fun drawTiles(
        context: Context,
        canvas: Canvas,
        bounds: List<RectF>,
        tiles: List<Tile>,
        palette: Palette,
        valueFraction: Float,
        labelFraction: Float,
    ) {
        if (tiles.isEmpty() || bounds.isEmpty()) return
        val cellHeight = bounds.first().height()
        val labelSize = (cellHeight * labelFraction).coerceIn(9f, 26f)
        val preferred = cellHeight * valueFraction
        val valueSize = tiles.zip(bounds).minOf { (tile, box) ->
            fitValueSize(tile, box.width() - 12f, preferred)
        }

        tiles.zip(bounds).forEach { (tile, box) ->
            drawTile(context, canvas, box, tile, palette, valueSize, labelSize)
        }
    }

    /**
     * Une case : son libellé précédé d'une icône, la valeur en dessous, le tout centré.
     *
     * Sur un aplat de couleur, l'encre passe au noir ou reste au blanc selon ce qui se lit
     * le mieux — le jaune de la zone 3 réclame du noir là où le rouge de la zone 6 non.
     */
    private fun drawTile(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        tile: Tile,
        palette: Palette,
        valueSize: Float,
        labelSize: Float,
    ) {
        val ink = tile.background?.let { Contrast.bestTextColor(it) } ?: palette.textPrimary
        val labelInk = if (tile.background == null) {
            palette.textSecondary
        } else {
            translucent(ink, LABEL_ALPHA)
        }

        // L'aplat est cerné de noir : sans cela deux cases colorées voisines se touchent et
        // leurs couleurs se contaminent à l'œil.
        tile.background?.let { background ->
            canvas.drawRect(bounds, Paint().apply { color = background })
            canvas.drawRect(
                bounds,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TILE_BORDER
                    style = Paint.Style.STROKE
                    strokeWidth = TILE_BORDER_WIDTH
                },
            )
        }

        // Sur fond neutre l'icône est verte ; sur un aplat de zone elle suit l'encre, le
        // vert n'ayant aucune raison d'être lisible sur les sept couleurs de la palette.
        val iconInk = if (tile.background == null) palette.iconTint else labelInk

        val labelPaint = paint(labelSize, labelInk, Typeface.DEFAULT_BOLD)
        val valuePaint = paint(valueSize, ink, LIGHT_TYPEFACE)
        val suffixPaint = paint(valueSize * SUFFIX_RATIO, ink, LIGHT_TYPEFACE)

        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val valueHeight = valuePaint.descent() - valuePaint.ascent()
        val top = bounds.centerY() - (labelHeight + valueHeight) / 2f

        drawLabelRow(context, canvas, bounds, tile, labelPaint, iconInk, top, labelSize)

        // La valeur et son suffixe forment un bloc unique, centré et posé sur une base commune.
        val valueWidth = valuePaint.measureText(tile.value)
        val suffixWidth = tile.suffix?.let { suffixPaint.measureText(it) } ?: 0f
        val left = bounds.centerX() - (valueWidth + suffixWidth) / 2f
        val baseline = top + labelHeight - valuePaint.ascent()

        canvas.drawText(tile.value, left, baseline, valuePaint)
        tile.suffix?.let { canvas.drawText(it, left + valueWidth, baseline, suffixPaint) }
    }

    /** Le libellé, précédé de son icône, centrés ensemble en haut de la case. */
    private fun drawLabelRow(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        tile: Tile,
        labelPaint: Paint,
        iconInk: Int,
        top: Float,
        labelSize: Float,
    ) {
        val iconSize = labelSize * ICON_RATIO
        val labelWidth = labelPaint.measureText(tile.label)
        val iconWidth = if (tile.icon == null) 0f else iconSize + ICON_GAP
        var left = bounds.centerX() - (labelWidth + iconWidth) / 2f

        tile.icon?.let { resource ->
            val drawable = ContextCompat.getDrawable(context, resource)
            if (drawable != null) {
                val iconTop = top + (labelPaint.descent() - labelPaint.ascent() - iconSize) / 2f
                drawable.setTint(iconInk)
                drawable.setBounds(
                    left.roundToInt(),
                    iconTop.roundToInt(),
                    (left + iconSize).roundToInt(),
                    (iconTop + iconSize).roundToInt(),
                )
                drawable.draw(canvas)
            }
            left += iconWidth
        }

        canvas.drawText(tile.label, left, top - labelPaint.ascent(), labelPaint)
    }

    /** Plus grande taille de valeur tenant dans la largeur, suffixe compris. */
    private fun fitValueSize(tile: Tile, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 140f)
        val measured = paint(size, 0, LIGHT_TYPEFACE).measureText(tile.value) +
            (tile.suffix?.let { paint(size * SUFFIX_RATIO, 0, LIGHT_TYPEFACE).measureText(it) } ?: 0f)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }

    private fun translucent(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private const val LABEL_ALPHA = 0xCC

    /** Cerne des cases colorées. */
    private const val TILE_BORDER = 0xFF000000.toInt()
    private const val TILE_BORDER_WIDTH = 3f
    private const val ICON_RATIO = 1.15f
    private const val ICON_GAP = 6f
    private val LIGHT_TYPEFACE: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

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
            canvas.drawText(
                it,
                area.left + 4f,
                area.top + labelSize,
                paint(labelSize, palette.textSecondary, Typeface.DEFAULT_BOLD),
            )
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

    private fun paint(size: Float, color: Int, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = face
    }

    private fun drawCentered(canvas: Canvas, area: RectF, message: String, size: Float, palette: Palette) {
        val paint = paint(size, palette.textSecondary, Typeface.DEFAULT_BOLD)
            .apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(
            message,
            area.centerX(),
            area.centerY() - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
    }
}
