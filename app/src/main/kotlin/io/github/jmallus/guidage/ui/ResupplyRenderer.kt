package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Ce qu'un point de ravitaillement est devenu au moment où on regarde la ligne. */
enum class StopKind {
    /** Dépassé : il ne sert plus à rien qu'à situer. */
    PASSED,

    /** Le prochain devant. */
    NEXT,

    /** Le dernier avant la traversée : celui où il faut s'arrêter. */
    LAST_USEFUL,

    /** Devant, mais ni le prochain ni le dernier utile. */
    AHEAD,
}

/** Un point sur la ligne du bas, placé en part de la longueur de l'itinéraire. */
data class ResupplyStop(val fraction: Float, val kind: StopKind)

/** Données prêtes à dessiner pour le champ « réserve ». */
data class ResupplyFieldModel(
    /** Bandeau d'alerte : « DERNIER RAVITAILLEMENT AVANT » et la longueur de la traversée. */
    val warningLabel: String? = null,
    val warningValue: String? = null,
    val sinceLabel: String? = null,
    val sinceValue: String? = null,
    val stops: List<ResupplyStop> = emptyList(),
    /** Position du coureur sur la ligne, en part de la longueur. */
    val position: Float = 0f,
    /** Début du segment sans ravitaillement, en part de la longueur ; null s'il n'y en a pas. */
    val dryFrom: Float? = null,
    val lastUsefulCaption: String? = null,
    val dryCaption: String? = null,
    val nextLabel: String? = null,
    val nextName: String? = null,
    val nextValue: String? = null,
    val emptyMessage: String? = null,
)

/**
 * Dessine la réserve : ce qui reste à boire sur l'itinéraire, et surtout ce qui n'y est plus.
 *
 * La question du ravitaillement n'est pas « où est le prochain point d'eau » — le champ
 * « Prochain point d'intérêt » y répond déjà — mais « après lequel n'y en a-t-il plus ». Ce
 * n'est pas une donnée nouvelle, c'est l'annonce retournée, et elle change ce qu'on fait au
 * point qu'on s'apprêtait à dépasser.
 *
 * D'où la ligne du bas, qui porte tout l'itinéraire et non la portion à venir : les points
 * passés en gris, le prochain en blanc, le dernier avant la traversée cerclé de jaune, et à
 * droite un long segment rouge qui ne porte rien. **C'est ce vide qu'on regarde**, et il ne
 * se lit que sur une ligne complète — un chiffre ne le montre pas, une portion tronquée le
 * coupe.
 */
object ResupplyRenderer {

    fun render(width: Int, height: Int, model: ResupplyFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    /**
     * Dessine le champ dans une zone du canevas plutôt que dans un bitmap à lui.
     *
     * C'est ce qui permet à la page « Autonomie » de porter la réserve au-dessus du budget
     * d'effort sans la réécrire : deux écritures d'un même champ dérivent l'une de l'autre
     * dès la première retouche, et c'est précisément ce que le banc d'essai cherche à éviter.
     */
    fun draw(canvas: Canvas, area: RectF, model: ResupplyFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        val padding = (min(width, height) * 0.04f).coerceIn(4f, 14f)
        val left = area.left + padding
        val right = area.right - padding
        if (right <= left) return

        if (model.stops.isEmpty() && model.warningValue == null) {
            drawEmpty(canvas, area, model.emptyMessage, palette)
            return
        }

        // Le bandeau d'alerte occupe le haut sur toute la largeur, à fond perdu : c'est la
        // seule chose de cet écran qu'on lise en roulant, et un aplat qui touche les bords se
        // repère sans être cherché.
        var y = area.top + padding
        if (model.warningValue != null) {
            val bandHeight = (height * WARNING_HEIGHT_FRACTION).coerceIn(34f, 170f)
            drawWarning(canvas, model, RectF(area.left, area.top, area.right, area.top + bandHeight))
            y = area.top + bandHeight + padding * 1.5f
        }

        val labelSize = (height * LABEL_FRACTION).coerceIn(9f, 26f)
        val valueSize = (height * VALUE_FRACTION).coerceIn(16f, 84f)

        if (model.sinceValue != null) {
            y = drawStat(canvas, model.sinceLabel, model.sinceValue, left, right, y, labelSize, valueSize, palette)
            y += padding
        }

        // La ligne, au milieu de ce qui reste : elle doit avoir de l'air au-dessus et en
        // dessous, sinon le vide de droite se lit comme une marge et non comme une absence.
        val nextRoom = if (model.nextValue == null) 0f else labelSize + valueSize * 1.5f + padding * 2
        val lineTop = y
        val lineBottom = area.bottom - padding - nextRoom
        if (lineBottom > lineTop) {
            drawLine(canvas, model, left, right, (lineTop + lineBottom) / 2f, labelSize, palette)
        }

        if (model.nextValue != null) {
            drawNext(canvas, model, left, right, area.bottom - nextRoom, labelSize, valueSize, palette)
        }
    }

    /**
     * Le bandeau d'alerte.
     *
     * Son encre est le blanc du système et non celle du thème : l'aplat est l'UI Red du
     * Karoo, que celui-ci réserve aux états à ne pas manquer, et sur lequel il n'écrit jamais
     * qu'en blanc — quel que soit le thème du reste de l'écran.
     */
    private fun drawWarning(canvas: Canvas, model: ResupplyFieldModel, area: RectF) {
        canvas.drawRect(area, Paint().apply { color = KarooColors.UI_RED })

        val labelSize = (area.height() * 0.20f).coerceIn(9f, 30f)
        val valueSize = (area.height() * 0.52f).coerceIn(18f, 90f)
        val inset = area.height() * 0.16f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = KarooColors.METADATA
            textSize = labelSize
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = KarooColors.METADATA
            textSize = valueSize
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        model.warningLabel?.let {
            fit(label, it, area.width() - inset * 2)
            canvas.drawText(it, area.left + inset, area.top + inset + labelSize, label)
        }
        model.warningValue?.let { canvas.drawText(it, area.right - inset, area.bottom - inset, value) }
    }

    private fun drawStat(
        canvas: Canvas,
        label: String?,
        value: String,
        left: Float,
        right: Float,
        top: Float,
        labelSize: Float,
        valueSize: Float,
        palette: Palette,
    ): Float {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            letterSpacing = 0.06f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = valueSize
            textAlign = Paint.Align.RIGHT
        }
        label?.let { canvas.drawText(it, left, top + labelSize, labelPaint) }
        canvas.drawText(value, right, top + labelSize + valueSize, valuePaint)
        return top + labelSize + valueSize
    }

    /**
     * La ligne de l'itinéraire, du départ à l'arrivée.
     *
     * Tout l'itinéraire, et non la portion à venir : sans ce qui est passé, on ne voit pas
     * que les points étaient nombreux et qu'ils ont cessé. Le vide n'est un vide que comparé
     * à un plein.
     */
    private fun drawLine(
        canvas: Canvas,
        model: ResupplyFieldModel,
        left: Float,
        right: Float,
        y: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val width = right - left
        fun x(fraction: Float) = left + fraction.coerceIn(0f, 1f) * width
        val thickness = (labelSize * 0.42f).coerceIn(4f, 10f)

        canvas.drawLine(
            left,
            y,
            right,
            y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.outline
                alpha = LINE_ALPHA
                strokeWidth = thickness
                strokeCap = Paint.Cap.ROUND
            },
        )

        // Le segment sans ravitaillement, en rouge. Il ne porte aucun point, et c'est tout
        // ce qu'il a à dire.
        model.dryFrom?.let { from ->
            canvas.drawLine(
                x(from),
                y,
                right,
                y,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = KarooColors.UI_RED
                    strokeWidth = thickness
                    strokeCap = Paint.Cap.ROUND
                },
            )
        }

        val radius = (labelSize * 0.62f).coerceIn(5f, 14f)
        model.stops.forEach { stop ->
            val center = x(stop.fraction)
            when (stop.kind) {
                StopKind.PASSED -> canvas.drawCircle(center, y, radius * 0.8f, fill(PASSED_STOP))
                StopKind.AHEAD -> canvas.drawCircle(center, y, radius * 0.8f, fill(palette.textSecondary))
                StopKind.NEXT -> canvas.drawCircle(center, y, radius, fill(palette.textPrimary))
                StopKind.LAST_USEFUL -> {
                    canvas.drawCircle(center, y, radius * 1.25f, fill(KarooColors.LEMON_YELLOW))
                    canvas.drawCircle(
                        center,
                        y,
                        radius * 2.1f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = KarooColors.LEMON_YELLOW
                            style = Paint.Style.STROKE
                            strokeWidth = thickness * 0.5f
                        },
                    )
                }
            }
        }

        // Le repère du coureur, au-dessus de la ligne : un triangle, comme sur le profil.
        val markerSize = radius * 1.4f
        val marker = Path().apply {
            moveTo(x(model.position), y - radius * 1.9f)
            lineTo(x(model.position) - markerSize, y - radius * 1.9f - markerSize * 1.5f)
            lineTo(x(model.position) + markerSize, y - radius * 1.9f - markerSize * 1.5f)
            close()
        }
        canvas.drawPath(marker, fill(palette.position))

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSize * 0.86f
            letterSpacing = 0.06f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = y + radius * 2.6f + caption.textSize
        model.stops.firstOrNull { it.kind == StopKind.LAST_USEFUL }?.let { stop ->
            model.lastUsefulCaption?.let {
                caption.color = KarooColors.LEMON_YELLOW
                caption.textAlign = Paint.Align.CENTER
                canvas.drawText(it, x(stop.fraction), baseline, caption)
            }
        }
        model.dryCaption?.let {
            caption.color = KarooColors.UI_RED
            caption.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, right, baseline, caption)
        }
    }

    private fun drawNext(
        canvas: Canvas,
        model: ResupplyFieldModel,
        left: Float,
        right: Float,
        top: Float,
        labelSize: Float,
        valueSize: Float,
        palette: Palette,
    ) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = labelSize
            letterSpacing = 0.06f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        model.nextLabel?.let {
            fit(labelPaint, it, right - left)
            canvas.drawText(it, left, top + labelSize, labelPaint)
        }

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = valueSize * 0.46f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = valueSize
            textAlign = Paint.Align.RIGHT
        }
        val baseline = top + labelSize + valueSize
        model.nextValue?.let { canvas.drawText(it, right, baseline, valuePaint) }
        model.nextName?.let { canvas.drawText(it, left, baseline, namePaint) }
    }

    /**
     * Rapetisse un libellé jusqu'à ce qu'il tienne dans la largeur donnée.
     *
     * Les libellés de ce champ ne sont pas tous de la même longueur : « PROCHAIN » fait huit
     * signes, « PLUS RIEN JUSQU'À L'ARRIVÉE » en fait vingt-sept, et le second déborderait à
     * la taille du premier. Il vaut mieux l'écrire plus petit que le couper : c'est une
     * phrase, et une phrase tronquée ne veut plus rien dire.
     */
    private fun fit(paint: Paint, text: String, maxWidth: Float) {
        val measured = paint.measureText(text)
        if (measured > maxWidth && maxWidth > 0f) paint.textSize *= maxWidth / measured
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun drawEmpty(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (area.height() * 0.09f).coerceIn(11f, 26f)
        }
        val centerY = area.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(message ?: "—", area.centerX(), centerY, paint)
    }

    /** Hauteur du bandeau d'alerte, en part de celle du champ. */
    private const val WARNING_HEIGHT_FRACTION = 0.22f

    private const val LABEL_FRACTION = 0.042f
    private const val VALUE_FRACTION = 0.13f

    /** Gris des points déjà dépassés : ils situent, ils ne servent plus. */
    private const val PASSED_STOP = 0xFF4A5458.toInt()

    /** Opacité du rail sur lequel les points sont posés. */
    private const val LINE_ALPHA = 0x66
}
