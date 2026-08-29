package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min

/** Un virage prêt à dessiner. */
data class BendMark(
    /** Position sur la bande, 0 au coureur et 1 au bout de la portée. */
    val position: Float,
    /** Longueur de la barre, 0 à 1 : c'est la sévérité. */
    val extent: Float,
    /** Sens : -1 à gauche, +1 à droite. */
    val direction: Int,
    val color: Int,
    /** Le plus serré porte un cerne : c'est celui dont la distance est écrite en bas. */
    val highlighted: Boolean = false,
)

/** Données prêtes à dessiner pour le champ « virages ». */
data class BendFieldModel(
    val label: String? = null,
    /** Ce qui résume la portée : « 8 virages · 4,2 km ». */
    val summary: String? = null,
    val marks: List<BendMark> = emptyList(),
    /** Graduations de distance : position sur la bande, et ce qui s'écrit. */
    val ticks: List<Pair<Float, String>> = emptyList(),
    /** Le bandeau du bas : ce qu'on lit vraiment à soixante à l'heure. */
    val callout: String? = null,
    val calloutValue: String? = null,
    val calloutColor: Int? = null,
    val emptyMessage: String? = null,
    /** Sens des barres, écrit sous la colonne en pleine page : « GAUCHE » et « DROITE ». */
    val leftCaption: String? = null,
    val rightCaption: String? = null,
)

/**
 * Dessine les virages à venir : la route redressée, un virage par barre.
 *
 * Ce champ n'est pas une carte, et c'est délibéré — la minicarte fait déjà cela, et mieux.
 * La route est ramenée à une ligne droite, la distance court de gauche à droite, et chaque
 * virage devient une barre qui s'écarte vers le haut ou vers le bas selon son sens, longue
 * et rouge selon sa sévérité. Ce qu'on cherche en descente n'est pas où l'on est, mais ce
 * qui arrive et à quel point c'est serré.
 *
 * La bande court à l'horizontale quelle que soit la forme du champ : posée sur un demi-rang,
 * c'est le seul sens qui laisse de la place ; sur un champ haut, elle prend le milieu et
 * laisse le bandeau du bas dire l'essentiel.
 */
object BendRenderer {

    fun render(width: Int, height: Int, model: BendFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.05f).coerceIn(3f, 10f)
        val left = padding
        val right = width - padding
        if (right <= left) return bitmap

        if (model.marks.isEmpty() && model.emptyMessage != null) {
            drawEmpty(canvas, width, height, model.emptyMessage, palette)
            return bitmap
        }

        // Une page entière n'est pas une bande étirée. Sur un champ haut, la route se
        // redresse à la verticale — la distance monte, le coureur est en bas — et les
        // libellés cessent d'être plafonnés à dix-huit points, taille faite pour un demi-rang.
        val vertical = height > width * VERTICAL_RATIO
        val labelSize = if (vertical) {
            (min(width, height) * 0.058f).coerceIn(10f, 30f)
        } else {
            (height * 0.13f).coerceIn(9f, 18f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerBaseline = padding + labelSize
        model.label?.let { canvas.drawText(it, left, headerBaseline, labelPaint) }
        model.summary?.let {
            canvas.drawText(it, right - labelPaint.measureText(it), headerBaseline, labelPaint)
        }

        // Le bandeau du bas prend sa place avant tout le reste : c'est lui qu'on lit en
        // roulant, et il ne doit jamais être celui qu'on rogne.
        val calloutHeight = when {
            model.callout == null -> 0f
            vertical -> (height * 0.14f).coerceIn(40f, 120f)
            else -> (height * 0.22f).coerceIn(16f, 60f)
        }
        val bandTop = headerBaseline + padding
        val bandBottom = height - padding - calloutHeight
        if (bandBottom > bandTop) {
            if (vertical) {
                drawColumn(canvas, model, left, bandTop, right, bandBottom, labelSize, palette)
            } else {
                drawBand(canvas, model, left, bandTop, right, bandBottom, labelSize, palette)
            }
        }
        if (calloutHeight > 0f) {
            drawCallout(canvas, model, 0f, height - calloutHeight, width.toFloat(), height.toFloat(), palette)
        }
        return bitmap
    }

    /**
     * La route redressée à la verticale, pour un champ en pleine page.
     *
     * La distance monte : le coureur est en bas, ce qui arrive est au-dessus. C'est le sens
     * d'une descente qu'on lit d'en bas, et il laisse à chaque virage toute la largeur du
     * champ pour dire sa sévérité — là où la bande horizontale ne lui donnait qu'une demi-
     * hauteur de rang.
     */
    private fun drawColumn(
        canvas: Canvas,
        model: BendFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val sideRoom = labelSize * 1.6f
        val axisTop = top + labelSize
        val axisBottom = bottom - sideRoom
        if (axisBottom <= axisTop) return

        val middle = (left + right) / 2f
        val half = (right - left) / 2f
        fun y(position: Float) = axisBottom - position.coerceIn(0f, 1f) * (axisBottom - axisTop)

        // Les graduations d'abord : elles passent sous la route et sous les barres.
        if (model.ticks.isNotEmpty()) {
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                alpha = TICK_RULE_ALPHA
                strokeWidth = 2f
            }
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                textSize = labelSize * 0.82f
            }
            model.ticks.forEach { (position, text) ->
                val lineY = y(position)
                canvas.drawLine(left, lineY, right, lineY, rule)
                canvas.drawText(text, left, lineY - tick.descent() - 2f, tick)
            }
        }

        canvas.drawLine(
            middle, axisTop, middle, axisBottom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.track
                strokeWidth = (half * 0.05f).coerceIn(4f, 12f)
                strokeCap = Paint.Cap.ROUND
            },
        )

        val barHeight = ((axisBottom - axisTop) * 0.035f).coerceIn(6f, 26f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = palette.textPrimary
        }
        model.marks.forEach { mark ->
            val centerY = y(mark.position)
            val length = half * mark.extent.coerceIn(0.15f, 1f)
            val bar = if (mark.direction < 0) {
                RectF(middle - length, centerY - barHeight / 2f, middle, centerY + barHeight / 2f)
            } else {
                RectF(middle, centerY - barHeight / 2f, middle + length, centerY + barHeight / 2f)
            }
            fill.color = mark.color
            canvas.drawRect(bar, fill)
            if (mark.highlighted) {
                canvas.drawRect(
                    RectF(bar.left - 4f, bar.top - 4f, bar.right + 4f, bar.bottom + 4f),
                    ring,
                )
            }
        }

        canvas.drawCircle(
            middle, axisBottom, (barHeight * 0.7f).coerceAtLeast(5f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary },
        )

        // Le sens, écrit sous la colonne : une barre qui part à gauche est un virage à gauche,
        // et rien d'autre sur cet écran ne le dit.
        val side = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize * 0.82f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = bottom - side.descent()
        model.leftCaption?.let { canvas.drawText(it, left, baseline, side) }
        model.rightCaption?.let {
            canvas.drawText(it, right - side.measureText(it), baseline, side)
        }
    }

    private fun drawBand(
        canvas: Canvas,
        model: BendFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val tickRoom = if (model.ticks.isEmpty()) 0f else labelSize * 1.2f
        val axisBottom = bottom - tickRoom
        if (axisBottom <= top) return

        val middle = (top + axisBottom) / 2f
        val half = (axisBottom - top) / 2f
        fun x(position: Float) = left + position.coerceIn(0f, 1f) * (right - left)

        // La route redressée : une ligne, et le coureur à son extrémité gauche.
        canvas.drawLine(
            left, middle, right, middle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.track
                strokeWidth = (half * 0.16f).coerceIn(2f, 8f)
                strokeCap = Paint.Cap.ROUND
            },
        )

        val barWidth = ((right - left) * 0.035f).coerceIn(3f, 14f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = palette.textPrimary
        }
        model.marks.forEach { mark ->
            val centerX = x(mark.position)
            val length = (half * mark.extent.coerceIn(0.15f, 1f))
            val bar = if (mark.direction < 0) {
                RectF(centerX - barWidth / 2f, middle - length, centerX + barWidth / 2f, middle)
            } else {
                RectF(centerX - barWidth / 2f, middle, centerX + barWidth / 2f, middle + length)
            }
            fill.color = mark.color
            canvas.drawRect(bar, fill)
            if (mark.highlighted) {
                canvas.drawRect(
                    RectF(bar.left - 3f, bar.top - 3f, bar.right + 3f, bar.bottom + 3f),
                    ring,
                )
            }
        }

        canvas.drawCircle(
            left, middle, (barWidth * 0.6f).coerceAtLeast(3f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary },
        )

        if (model.ticks.isNotEmpty()) {
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                textSize = labelSize * 0.86f
            }
            var lastRight = left - labelSize
            model.ticks.forEach { (position, text) ->
                val centerX = x(position)
                val halfText = tick.measureText(text) / 2f
                if (centerX - halfText < lastRight + TICK_GAP || centerX + halfText > right) return@forEach
                canvas.drawText(text, centerX - halfText, bottom - tick.descent(), tick)
                lastRight = centerX + halfText
            }
        }
    }

    private fun drawCallout(
        canvas: Canvas,
        model: BendFieldModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val background = model.calloutColor ?: return
        canvas.drawRect(RectF(left, top, right, bottom), Paint().apply { color = background })

        val ink = Contrast.bestTextColor(background)
        val size = ((bottom - top) * 0.5f).coerceIn(10f, 34f)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size * 0.78f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = (top + bottom) / 2f - (value.descent() + value.ascent()) / 2f
        model.callout?.let { canvas.drawText(it, left + EDGE, baseline, title) }
        model.calloutValue?.let { canvas.drawText(it, right - EDGE - value.measureText(it), baseline, value) }
    }

    private fun drawEmpty(canvas: Canvas, width: Int, height: Int, message: String?, palette: Palette) {
        val text = message ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = (height * 0.22f).coerceIn(10f, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val x = (width - paint.measureText(text)) / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, max(x, 0f), y, paint)
    }

    /**
     * Rapport hauteur/largeur au-delà duquel la route se redresse à la verticale.
     *
     * Un dixième de plus que le carré : en deçà, la bande horizontale reste la bonne réponse,
     * et c'est celle des demi-rangs où ce champ est le plus souvent posé.
     */
    private const val VERTICAL_RATIO = 1.1f

    /** Opacité des graduations, qui passent sous la route sans la disputer. */
    private const val TICK_RULE_ALPHA = 0x59

    private const val TICK_GAP = 8f
    private const val EDGE = 6f
}
