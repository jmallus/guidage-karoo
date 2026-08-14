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
    /** Unité écrite en plus petit, sur la même ligne de base : le signe pour cent. */
    val suffix: String? = null,
    /**
     * Décimale, écrite en exposant et sans séparateur.
     *
     * Une virgule prend la place d'un chiffre sans rien apprendre : la décimale se
     * reconnaît à sa hauteur. « 38,5 » s'écrit donc « 38 » suivi d'un « 5 » surélevé.
     */
    val decimal: String? = null,
    /** Aplat de fond, ou null pour laisser le fond de l'écran. */
    val background: Int? = null,
    /** Icône posée devant le libellé. */
    @DrawableRes val icon: Int? = null,
)

/**
 * Position de la transmission, dessinée en schéma plutôt qu'écrite.
 *
 * Un rapport ne se lit pas, il se situe : savoir qu'il reste deux pignons avant la fin de
 * la cassette se voit d'un coup d'œil sur un peigne de barres, là où « 34×17 » demande de
 * connaître son matériel par cœur.
 */
data class DrivetrainModel(
    val label: String,
    val front: Int? = null,
    val frontCount: Int? = null,
    val frontTeeth: Int? = null,
    val rear: Int? = null,
    val rearCount: Int? = null,
    val rearTeeth: Int? = null,
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
    /** Rang de la côte sur l'itinéraire, façon « 1/5 ». */
    val label: String? = null,
)

/** Ce qu'on affiche dans la colonne de guidage, à droite des mesures. */
sealed interface GuidanceZone {
    data class Map(val model: MapModel) : GuidanceZone

    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le champ plein écran.
 *
 * Cinq rangs : le bandeau de l'effort instantané en haut, puis la carte à droite sur deux
 * hauteurs avec la transmission et le cœur à sa gauche, la distance et la pente en dessous,
 * et le rappel de fin de parcours tout en bas.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    /** Bandeau du haut : vitesse, cadence, puissance. */
    val topTiles: List<Tile>,
    /** Case de gauche sous le bandeau : la transmission. */
    val drivetrain: DrivetrainModel? = null,
    /** Case de gauche suivante : la fréquence cardiaque. */
    val heartRateTile: Tile? = null,
    /** Rang sous la carte : distance parcourue et pente. */
    val midTiles: List<Tile> = emptyList(),
    /** Dernier rang : distance restante et heure d'arrivée. */
    val footerTiles: List<Tile> = emptyList(),
    /** Profil de la côte, sous la dernière ligne, quand il y en a une. */
    val climbBand: ClimbBandModel? = null,
    val palette: Palette,
)

object DashboardRenderer {

    /** Largeur de la colonne de gauche. */
    private const val TILE_COLUMN_FRACTION = 0.5f

    /** Hauteur d'un rang de la grille, en part de la hauteur utile ; le reste va au pied. */
    private const val ROW_HEIGHT_FRACTION = 0.2116f

    /** Nombre de rangs avant la dernière ligne. */
    private const val GRID_ROWS = 4

    /** Nombre de hauteurs de rang occupées par le guidage. */
    private const val GUIDANCE_ROWS = 2

    /** Nombre de cases du bandeau du haut. */
    private const val TOP_TILES = 3

    /** Corps des chiffres du bandeau du haut, en part de celui des autres cases. */
    private const val TOP_VALUE_RATIO = 0.78f

    /** Proportions relevées sur la maquette, exprimées en part de la hauteur de case. */
    private const val VALUE_HEIGHT_FRACTION = 0.614f
    private const val LABEL_HEIGHT_FRACTION = 0.147f
    private const val FOOTER_VALUE_HEIGHT_FRACTION = 0.569f
    private const val FOOTER_LABEL_HEIGHT_FRACTION = 0.165f

    /** Taille du suffixe, en part de celle de la valeur. */
    private const val SUFFIX_RATIO = 0.52f

    /** Élévation de la décimale, en part de la hauteur des chiffres. */
    private const val DECIMAL_RISE = 0.42f

    /** Hauteur du bandeau de montée, quand il y en a un. */
    private const val CLIMB_BAND_FRACTION = 0.10f

    fun render(context: Context, width: Int, height: Int, model: DashboardModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.015f).coerceIn(2f, 6f)
        val columnSplit = width * TILE_COLUMN_FRACTION
        val right = width - padding

        // Le bandeau de montée mange le bas de l'écran ; tout le reste se serre au-dessus.
        val bandHeight = if (model.climbBand == null) 0f else height * CLIMB_BAND_FRACTION
        val contentHeight = height - bandHeight
        val rowHeight = (contentHeight - 2 * padding) * ROW_HEIGHT_FRACTION
        fun row(index: Int) = padding + index * rowHeight
        val footerTop = row(GRID_ROWS)
        val footerBottom = contentHeight - padding

        // Rang du haut : l'effort instantané, trois cases côte à côte. Il a sa propre taille
        // de chiffres, plus petite : ces cases sont deux fois plus étroites que les autres,
        // et des chiffres à pleine hauteur y touchaient les bords.
        val topWidth = (width - 2 * padding) / TOP_TILES
        val topTiles = model.topTiles.take(TOP_TILES)
        drawTiles(
            context = context,
            canvas = canvas,
            bounds = topTiles.indices.map { index ->
                RectF(
                    padding + index * topWidth,
                    row(0),
                    padding + (index + 1) * topWidth,
                    row(1),
                )
            },
            tiles = topTiles,
            palette = model.palette,
            valueFraction = VALUE_HEIGHT_FRACTION * TOP_VALUE_RATIO,
            labelFraction = LABEL_HEIGHT_FRACTION,
        )

        val gridTiles = mutableListOf<Tile>()
        val gridBounds = mutableListOf<RectF>()
        // Colonne gauche : le cœur sous la transmission.
        model.heartRateTile?.let {
            gridTiles += it
            gridBounds += RectF(padding, row(2), columnSplit, row(3))
        }
        // Rang sous la carte : distance parcourue à gauche, pente à droite.
        model.midTiles.take(2).forEachIndexed { index, tile ->
            gridTiles += tile
            val left = if (index == 0) padding else columnSplit
            gridBounds += RectF(left, row(3), if (index == 0) columnSplit else right, footerTop)
        }

        // Une seule taille de chiffres pour ces cases-là : d'un rang à l'autre, les valeurs
        // se comparent alors sans que leur corps ne trahisse la largeur de la case.
        val valueSize = drawTiles(
            context = context,
            canvas = canvas,
            bounds = gridBounds,
            tiles = gridTiles,
            palette = model.palette,
            valueFraction = VALUE_HEIGHT_FRACTION,
            labelFraction = LABEL_HEIGHT_FRACTION,
        )

        // Colonne droite : le guidage sur deux hauteurs de rang.
        val guidanceArea = RectF(columnSplit, row(1), right, row(1 + GUIDANCE_ROWS))
        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model, model.palette)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, guidanceArea, guidance.model, model.palette)
        }

        // Colonne gauche, à hauteur du haut de la carte : la transmission.
        model.drivetrain?.let { drivetrain ->
            drawDrivetrain(
                context = context,
                canvas = canvas,
                bounds = RectF(padding, row(1), columnSplit, row(2)),
                model = drivetrain,
                palette = model.palette,
                valueSize = valueSize,
                labelSize = (rowHeight * LABEL_HEIGHT_FRACTION).coerceIn(9f, 26f),
            )
        }

        // Ligne du bas : restant et arrivée.
        val footerWidth = (width - 2 * padding) / model.footerTiles.size.coerceAtLeast(1)
        drawTiles(
            context = context,
            canvas = canvas,
            bounds = model.footerTiles.indices.map { index ->
                val left = padding + index * footerWidth
                RectF(left, footerTop, left + footerWidth, footerBottom)
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
     * un repère à la position du coureur et le rang de la côte sur l'itinéraire.
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
        if (model.position in window.start..window.end) {
            val elevation = model.positionElevation ?: elevationAt(points, model.position)
            drawMarker(canvas, x(model.position), y(elevation), markerRadius, RIDER_MARKER)
        }

        // Le rang de la côte se pose en haut à gauche : le profil part du bas de la bande à
        // gauche pour monter vers la droite, ce coin est donc toujours vide.
        model.label?.let { label ->
            val size = (area.height() * 0.42f).coerceIn(11f, 22f)
            val x = area.left + 4f
            val baseline = area.top + size
            canvas.drawText(
                label,
                x,
                baseline,
                paint(size, MARKER_BORDER, Typeface.DEFAULT_BOLD).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = size * 0.22f
                    strokeJoin = Paint.Join.ROUND
                },
            )
            canvas.drawText(label, x, baseline, paint(size, palette.textPrimary, Typeface.DEFAULT_BOLD))
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
    private const val MARKER_BORDER = 0xFF1A1A1A.toInt()

    // --- Cases de chiffres ---------------------------------------------------------------

    /**
     * Dessine un groupe de cases avec une seule et même taille de chiffres, qu'elle renvoie.
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
    ): Float {
        if (tiles.isEmpty() || bounds.isEmpty()) return 0f
        val cellHeight = bounds.first().height()
        val labelSize = (cellHeight * labelFraction).coerceIn(9f, 26f)
        val preferred = cellHeight * valueFraction
        val valueSize = tiles.zip(bounds).minOf { (tile, box) ->
            fitValueSize(tile, box.width() - EDGE_INSET * 2, preferred)
        }

        tiles.zip(bounds).forEach { (tile, box) ->
            drawTile(context, canvas, box, tile, palette, valueSize, labelSize)
        }
        return valueSize
    }

    /**
     * Une case : l'icône calée à gauche, le libellé et la valeur alignés à droite.
     *
     * L'alignement à droite fait tomber les unités et les chiffres des poids faibles sur
     * une même verticale d'une case à l'autre : l'œil compare alors les valeurs sans avoir
     * à les recentrer lui-même, ce que le centrage lui imposait.
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
        // Le bloc est descendu d'une fraction de sa hauteur de libellé : centré au pixel près,
        // il paraissait haut dans la case, les chiffres n'ayant pas de jambages pour occuper
        // le bas de leur ligne.
        val drop = (labelHeight * BLOCK_DROP_RATIO)
            .coerceAtMost((bounds.height() - labelHeight - valueHeight) / 2f)
            .coerceAtLeast(0f)
        val top = bounds.centerY() - (labelHeight + valueHeight) / 2f + drop
        val right = bounds.right - EDGE_INSET

        drawLabelRow(context, canvas, bounds, right, tile, labelPaint, iconInk, top, labelSize)

        // La valeur, sa décimale et son unité forment un bloc unique aligné à droite.
        val valueWidth = valuePaint.measureText(tile.value)
        val tail = tile.decimal ?: tile.suffix
        val tailWidth = tail?.let { suffixPaint.measureText(it) } ?: 0f
        val left = right - (valueWidth + tailWidth)
        val baseline = top + labelHeight - valuePaint.ascent()

        canvas.drawText(tile.value, left, baseline, valuePaint)
        if (tail != null) {
            // La décimale monte en exposant, l'unité reste sur la ligne de base.
            val rise = if (tile.decimal != null) -valuePaint.ascent() * DECIMAL_RISE else 0f
            canvas.drawText(tail, left + valueWidth, baseline - rise, suffixPaint)
        }
    }

    /** L'icône contre le bord gauche de la case, le libellé aligné à droite comme la valeur. */
    private fun drawLabelRow(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        right: Float,
        tile: Tile,
        labelPaint: Paint,
        iconInk: Int,
        top: Float,
        labelSize: Float,
    ) {
        val iconSize = labelSize * ICON_RATIO
        val labelWidth = labelPaint.measureText(tile.label)

        tile.icon?.let { resource ->
            val drawable = ContextCompat.getDrawable(context, resource)
            if (drawable != null) {
                // L'icône est plus grande que le libellé et se pose sur la même ligne médiane :
                // à gauche de la case, elle sert de repère avant même qu'on lise le mot.
                val left = bounds.left + EDGE_INSET
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
        }

        canvas.drawText(tile.label, right - labelWidth, top - labelPaint.ascent(), labelPaint)
    }

    // --- Transmission ----------------------------------------------------------------------

    /**
     * La transmission en schéma : un peigne de barres par groupe, plateaux à gauche et
     * pignons à droite, la barre en service allumée.
     *
     * Les barres montent avec le numéro de rapport, comme montent les dents : la position
     * dans la cassette se lit alors sans compter, et l'on voit d'un coup d'œil qu'il reste
     * ou non de la marge avant la butée.
     */
    private fun drawDrivetrain(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        model: DrivetrainModel,
        palette: Palette,
        valueSize: Float,
        labelSize: Float,
    ) {
        val labelPaint = paint(labelSize, palette.textSecondary, Typeface.DEFAULT_BOLD)
        val valuePaint = paint(valueSize, 0, LIGHT_TYPEFACE)
        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val valueHeight = valuePaint.descent() - valuePaint.ascent()
        val drop = (labelHeight * BLOCK_DROP_RATIO)
            .coerceAtMost((bounds.height() - labelHeight - valueHeight) / 2f)
            .coerceAtLeast(0f)
        val top = bounds.centerY() - (labelHeight + valueHeight) / 2f + drop
        val right = bounds.right - EDGE_INSET

        drawLabelRow(
            context = context,
            canvas = canvas,
            bounds = bounds,
            right = right,
            tile = Tile(label = model.label, value = "", icon = model.icon),
            labelPaint = labelPaint,
            iconInk = palette.iconTint,
            top = top,
            labelSize = labelSize,
        )

        val area = RectF(bounds.left + EDGE_INSET, top + labelHeight, right, top + labelHeight + valueHeight)
        if (area.width() <= 0 || area.height() <= 0) return

        val teeth = teethLabel(model)
        val teethSize = valueSize * TEETH_RATIO
        val teethPaint = paint(teethSize, palette.textPrimary, LIGHT_TYPEFACE)
        val combHeight = if (teeth == null) area.height() else area.height() - teethSize * 1.25f

        val front = comb(model.front, model.frontCount)
        val rear = comb(model.rear, model.rearCount)
        if (front == null && rear == null) {
            canvas.drawText(
                PLACEHOLDER,
                right - teethPaint.measureText(PLACEHOLDER),
                area.top + area.height() / 2f - (teethPaint.descent() + teethPaint.ascent()) / 2f,
                teethPaint,
            )
            return
        }

        // Les plateaux tiennent dans le quart gauche, les pignons occupent le reste : c'est
        // la proportion des dentures, et cela laisse la place de compter les pignons.
        val gap = area.width() * COMB_GAP_FRACTION
        val frontWidth = if (front == null) 0f else area.width() * FRONT_COMB_FRACTION
        val rearLeft = area.left + frontWidth + if (front == null) 0f else gap
        val rearWidth = area.right - rearLeft

        // Une seule largeur de barre pour les deux peignes : deux plateaux dans le quart
        // gauche donneraient des barres trois fois plus larges que les onze pignons, et le
        // schéma se lirait comme deux dessins différents plutôt que comme une transmission.
        val pitches = listOfNotNull(
            front?.let { frontWidth / it.second },
            rear?.let { rearWidth / it.second },
        )
        val barWidth = (pitches.minOrNull() ?: 0f).times(BAR_WIDTH_FRACTION).coerceAtLeast(2f)

        front?.let {
            drawComb(canvas, RectF(area.left, area.top, area.left + frontWidth, area.top + combHeight), it, barWidth, ascending = true, palette = palette)
        }
        rear?.let {
            // Les pignons décroissent de gauche à droite, comme sur la cassette : le premier
            // rapport est le grand pignon, celui qu'on prend pour monter.
            drawComb(canvas, RectF(rearLeft, area.top, area.right, area.top + combHeight), it, barWidth, ascending = false, palette = palette)
        }

        teeth?.let {
            canvas.drawText(it, right - teethPaint.measureText(it), area.bottom - teethPaint.descent(), teethPaint)
        }
    }

    /** Rapport courant et nombre de rapports, quand les deux sont connus et cohérents. */
    private fun comb(current: Int?, count: Int?): Pair<Int, Int>? {
        if (count == null || count <= 0) return null
        val gear = current?.coerceIn(1, count) ?: return null
        return gear to count
    }

    private fun drawComb(
        canvas: Canvas,
        area: RectF,
        comb: Pair<Int, Int>,
        barWidth: Float,
        ascending: Boolean,
        palette: Palette,
    ) {
        val (current, count) = comb
        if (area.width() <= 0f) return
        val pitch = area.width() / count
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val radius = barWidth / 2f

        for (index in 1..count) {
            val step = if (count == 1) 1f else (index - 1).toFloat() / (count - 1)
            val ratio = if (ascending) step else 1f - step
            val height = area.height() * (BAR_MIN_HEIGHT + (1f - BAR_MIN_HEIGHT) * ratio)
            val left = area.left + (index - 1) * pitch + (pitch - barWidth) / 2f
            paint.color = if (index == current) palette.iconTint else translucent(palette.textSecondary, BAR_ALPHA)
            // Bouts arrondis : à cette taille, des angles vifs font des barres sales.
            canvas.drawRoundRect(
                RectF(left, area.bottom - height, left + barWidth, area.bottom),
                radius,
                radius,
                paint,
            )
        }
    }

    /** « 34×17 » quand les dentures sont connues, sinon rien. */
    private fun teethLabel(model: DrivetrainModel): String? {
        val front = model.frontTeeth?.takeIf { it > 0 }
        val rear = model.rearTeeth?.takeIf { it > 0 }
        return when {
            front != null && rear != null -> "$front×$rear"
            rear != null -> "$rear"
            front != null -> "$front"
            else -> null
        }
    }

    private const val PLACEHOLDER = "--"

    /** Part de la case revenant aux plateaux, l'écart et les pignons se partageant le reste. */
    private const val FRONT_COMB_FRACTION = 0.22f
    private const val COMB_GAP_FRACTION = 0.08f
    private const val BAR_WIDTH_FRACTION = 0.62f

    /** Hauteur de la plus petite barre, en part de la plus grande. */
    private const val BAR_MIN_HEIGHT = 0.35f
    private const val BAR_ALPHA = 0x66

    /** Taille des dentures, en part de celle des chiffres des autres cases. */
    private const val TEETH_RATIO = 0.42f

    /** Plus grande taille de valeur tenant dans la largeur, décimale et unité comprises. */
    private fun fitValueSize(tile: Tile, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 140f)
        val tail = tile.decimal ?: tile.suffix
        val measured = paint(size, 0, LIGHT_TYPEFACE).measureText(tile.value) +
            (tail?.let { paint(size * SUFFIX_RATIO, 0, LIGHT_TYPEFACE).measureText(it) } ?: 0f)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }

    private fun translucent(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private const val LABEL_ALPHA = 0xCC

    /** Cerne des cases colorées. */
    private const val TILE_BORDER = 0xFF000000.toInt()
    private const val TILE_BORDER_WIDTH = 3f
    private const val ICON_RATIO = 1.15f

    /** Marge entre le bord de la case et ce qu'elle contient. */
    private const val EDGE_INSET = 8f

    /** Descente du bloc libellé + valeur, en part de la hauteur du libellé. */
    private const val BLOCK_DROP_RATIO = 0.45f
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
