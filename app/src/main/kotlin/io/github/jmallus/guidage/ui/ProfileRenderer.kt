package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.Units
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Données prêtes à dessiner pour le champ « profil à venir ». */
data class ProfileFieldModel(
    val window: ProfileWindow,
    /** Côtes de l'itinéraire, pour surligner celles visibles dans la fenêtre. */
    val climbs: List<RouteClimb> = emptyList(),
    /** Texte du dénivelé positif restant sur la fenêtre, ex. « +120 m ». */
    val ascentLabel: String? = null,
    /** Texte de la distance restante, ex. « 41,2 km ». */
    val rangeLabel: String? = null,
    /** Message affiché quand il n'y a rien à montrer. */
    val emptyMessage: String? = null,
    val colorByGrade: Boolean = true,
    /** Unités du coureur, pour graduer l'axe rondement. */
    val units: Units = Units.METRIC,
)

/**
 * Dessine le profil altimétrique à venir dans un bitmap aux dimensions du champ.
 *
 * Glance ne sait pas dessiner de courbe : le champ est donc rendu sur un Canvas puis
 * envoyé au système sous forme d'image.
 *
 * L'échelle horizontale n'est pas proportionnelle mais logarithmique (voir [FisheyeScale]) :
 * la bande couvre tout ce qui reste à parcourir, du premier mètre à l'arrivée, en donnant au
 * premier plan une largeur qu'une échelle régulière lui refuserait. C'est ce qui a permis de
 * retirer le réglage de portée : il n'y a plus de choix à faire entre voir la rampe et voir
 * la journée.
 *
 * Le prix en est que la silhouette lointaine est une **crête** et non une courbe : une
 * colonne de pixels y couvre parfois deux kilomètres, dont on retient le point le plus haut.
 * Un sommet ne peut donc pas disparaître entre deux colonnes, mais un col suivi d'une
 * descente courte se lit comme un plateau. À cette échelle, c'est ce qu'on veut savoir.
 */
object ProfileRenderer {

    fun render(width: Int, height: Int, model: ProfileFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val labelSize = (height * 0.16f).coerceIn(9f, 20f)
        val padding = (min(width, height) * 0.04f).coerceIn(2f, 8f)
        val tickSize = (labelSize * TICK_TEXT_RATIO).coerceAtLeast(MIN_TICK_TEXT)
        val labelled = height >= tickSize * LABELLED_AXIS_HEIGHTS
        val axis = if (labelled) TICK_LENGTH + tickSize * 1.35f else TICK_LENGTH

        val top = padding + labelSize
        val bottom = height - padding - axis
        val left = padding
        val right = width - padding

        val scale = FisheyeScale(model.window.distanceSpan)
        if (model.window.isEmpty || !scale.usable || bottom <= top || right <= left) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }

        drawProfile(canvas, model, scale, left, top, right, bottom, palette)
        drawClimbMarkers(canvas, model, scale, left, top, right, bottom, labelSize, palette)
        drawAxis(canvas, model, scale, left, right, bottom, tickSize, labelled, palette)
        drawPositionMarker(canvas, left, top, bottom, palette)
        drawLabels(canvas, model, left, right, padding + labelSize, labelSize, palette)
        return bitmap
    }

    /**
     * La silhouette, colonne de pixels par colonne de pixels.
     *
     * Et non segment par segment comme autrefois : sous une échelle comprimée, cent segments
     * du relevé tombent dans la même colonne, et les dessiner l'un après l'autre revient à
     * empiler cent rectangles d'un pixel de large dont seul le dernier se voit — le profil
     * lointain se criblait de trous et prenait la couleur du dernier segment tiré. En
     * partant des colonnes, chacune est peinte une fois, de la pente qu'elle couvre vraiment.
     */
    private fun drawProfile(
        canvas: Canvas,
        model: ProfileFieldModel,
        scale: FisheyeScale,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val window = model.window
        val points = window.points
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return
        // Les colonnes sont calées sur les pixels de l'image, et non sur les bords fractionnaires
        // de la zone de dessin : un rectangle posé à cinq virgule six déborde sur deux pixels,
        // dont aucun ne reçoit sa couleur pure. Là où deux colonnes voisines n'ont pas la même
        // teinte — c'est-à-dire à chaque changement de zone de pente — le pixel de la frontière
        // devient un mélange qui n'appartient à aucune des deux.
        val first = ceil(left).toInt()
        val columns = (floor(right).toInt() - first).coerceAtLeast(1)

        fun y(elevation: Double) =
            bottom - ((elevation - window.minElevation) / elevationSpan * (bottom - top)).toFloat()

        // Sans antialiasing : ces rectangles sont alignés sur la grille des pixels, et l'adoucir
        // ne ferait que rendre floues des frontières franches. La crête, elle, est une courbe et
        // reste adoucie.
        val fill = Paint().apply { style = Paint.Style.FILL }
        val ridge = Path()

        for (column in 0 until columns) {
            val from = window.start + scale.distanceAt(column.toDouble() / columns)
            val to = window.start + scale.distanceAt((column + 1).toDouble() / columns)
            val crest = crest(points, from, to)
            val span = to - from
            val grade = if (span > 0.0) {
                (interpolate(points, to) - interpolate(points, from)) / span * 100.0
            } else {
                0.0
            }

            fill.color = if (model.colorByGrade) FieldPalette.gradeColor(grade) else FieldPalette.NEUTRAL
            val x = (first + column).toFloat()
            // Au moins un pixel de haut, toujours. Sur un plat au bas de l'échelle, le relief
            // d'une colonne vaut une fraction de pixel : sans plancher, le rectangle n'est pas
            // tracé du tout et la silhouette se troue — précisément là où le terrain est le
            // plus régulier, c'est-à-dire là où un trou ressemble le moins à un accident.
            val crestY = min(y(crest), bottom - 1f)
            canvas.drawRect(x, crestY, x + 1f, bottom, fill)
            if (column == 0) ridge.moveTo(x, crestY) else ridge.lineTo(x + 0.5f, crestY)
        }

        canvas.drawPath(
            ridge,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = palette.outline
            },
        )
    }

    /**
     * Les graduations, dont l'espacement inégal est la seule chose qui trahisse la
     * compression.
     *
     * Sans elles l'œil suppose une échelle régulière et lit un faux relief : une bosse à
     * trente kilomètres lui paraît deux fois plus courte qu'une bosse à trois. Elles ne sont
     * donc pas un ornement, et c'est pourquoi leurs traits subsistent même quand le champ est
     * trop court pour porter les chiffres — un espacement irrégulier se voit sans se lire.
     */
    private fun drawAxis(
        canvas: Canvas,
        model: ProfileFieldModel,
        scale: FisheyeScale,
        left: Float,
        right: Float,
        bottom: Float,
        tickSize: Float,
        labelled: Boolean,
        palette: Palette,
    ) {
        val usable = right - left
        val gap = if (labelled) {
            (tickSize * TICK_LABEL_WIDTHS / usable).coerceIn(MIN_GAP, MAX_GAP)
        } else {
            MIN_GAP
        }
        val ticks = scale.ticks(
            minimumGap = gap.toDouble(),
            unit = Format.longDistanceUnitMeters(model.units),
        )
        if (ticks.isEmpty()) return

        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            strokeWidth = 2f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = tickSize
            textAlign = Paint.Align.CENTER
        }
        val unit = Format.longDistanceUnit(model.units)

        ticks.forEachIndexed { index, tick ->
            val x = left + (tick.fraction * usable).toFloat()
            canvas.drawLine(x, bottom, x, bottom + TICK_LENGTH, rule)
            if (!labelled) return@forEachIndexed
            // L'unité une seule fois, sur le dernier repère : la répéter à chaque graduation
            // remplirait l'axe du mot le moins informatif qu'il porte.
            val caption = Format.axisValue(tick.value) + if (index == ticks.lastIndex) " $unit" else ""
            canvas.drawText(caption, x, bottom + TICK_LENGTH + tickSize, text)
        }
    }

    private fun drawClimbMarkers(
        canvas: Canvas,
        model: ProfileFieldModel,
        scale: FisheyeScale,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val window = model.window
        fun x(distance: Double) =
            left + (scale.fractionAt(distance - window.start) * (right - left)).toFloat()

        val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = labelSize * 0.9f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        model.climbs
            .filter { it.endDistance > window.start && it.startDistance < window.end }
            .forEach { climb ->
                val startX = x(max(climb.startDistance, window.start))
                val endX = x(min(climb.endDistance, window.end))
                if (endX - startX < 6f) return@forEach

                overlay.color = FieldPalette.translucent(FieldPalette.gradeColor(climb.grade), 40)
                canvas.drawRect(startX, top, endX, bottom, overlay)

                if (endX - startX > labelSize * 2.4f) {
                    canvas.drawText(
                        "${climb.grade.toInt()}%",
                        (startX + endX) / 2,
                        top + labelSize,
                        text,
                    )
                }
            }
    }

    private fun drawPositionMarker(canvas: Canvas, left: Float, top: Float, bottom: Float, palette: Palette) {
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.position
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(left, top, left, bottom, marker)

        val triangle = Path().apply {
            moveTo(left, bottom)
            lineTo(left - 5f, bottom + 5f)
            lineTo(left + 5f, bottom + 5f)
            close()
        }
        canvas.drawPath(
            triangle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.position
                style = Paint.Style.FILL
            },
        )
    }

    private fun drawLabels(
        canvas: Canvas,
        model: ProfileFieldModel,
        left: Float,
        right: Float,
        baseline: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }
        model.ascentLabel?.let {
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, left, baseline - labelSize * 0.2f, paint)
        }
        model.rangeLabel?.let {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, right, baseline - labelSize * 0.2f, paint)
        }
    }

    private fun drawEmpty(canvas: Canvas, width: Int, height: Int, message: String?, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (height * 0.22f).coerceIn(10f, 26f)
        }
        val centerY = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(message ?: "—", width / 2f, centerY, paint)
    }

    /** Le point le plus haut entre deux distances, bornes comprises. */
    private fun crest(points: List<ProfilePoint>, from: Double, to: Double): Double {
        if (points.isEmpty()) return 0.0
        var top = max(interpolate(points, from), interpolate(points, to))
        var index = upperBound(points, from)
        while (index < points.size && points[index].distance < to) {
            top = max(top, points[index].elevation)
            index++
        }
        return top
    }

    /** Altitude interpolée, les distances hors bornes étant ramenées aux extrémités. */
    private fun interpolate(points: List<ProfilePoint>, distance: Double): Double {
        if (points.isEmpty()) return 0.0
        if (distance <= points.first().distance) return points.first().elevation
        if (distance >= points.last().distance) return points.last().elevation
        val index = upperBound(points, distance).coerceIn(1, points.size - 1)
        val before = points[index - 1]
        val after = points[index]
        val span = after.distance - before.distance
        if (span <= 0.0) return after.elevation
        return before.elevation + (distance - before.distance) / span * (after.elevation - before.elevation)
    }

    private fun upperBound(points: List<ProfilePoint>, distance: Double): Int {
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].distance < distance) low = mid + 1 else high = mid
        }
        return low
    }

    /** Longueur du trait d'une graduation sous l'axe. */
    private const val TICK_LENGTH = 4f

    /** Corps des graduations, en part de celui des libellés du haut. */
    private const val TICK_TEXT_RATIO = 0.72f
    private const val MIN_TICK_TEXT = 7f

    /** Le champ ne porte les chiffres de l'axe qu'à partir de cette hauteur, en corps. */
    private const val LABELLED_AXIS_HEIGHTS = 7f

    /** Largeur réservée à une étiquette de graduation, en corps. */
    private const val TICK_LABEL_WIDTHS = 3.4f

    private const val MIN_GAP = 0.08f
    private const val MAX_GAP = 0.34f
}
