package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/** Le verdict, tel que le rendu le colore. */
enum class NightVerdict { YES, TIGHT, NO }

/**
 * La frise du soir : maintenant à gauche, la nuit à droite, l'arrivée entre les deux.
 *
 * Tout est en fractions de la frise, de zéro à un : le modèle décide de l'échelle, le rendu
 * ne fait que la poser sur des pixels.
 */
data class NightTimeline(
    val nowLabel: String,
    /** Position de l'arrivée moyenne, ou null tant qu'aucune heure n'est connue. */
    val arrivalFraction: Float? = null,
    /** Demi-largeur de la fourchette d'arrivée, dans la même unité. */
    val arrivalSpread: Float = 0f,
    val arrivalLabel: String? = null,
    val sunsetFraction: Float,
    val sunsetLabel: String,
    /** La fin du crépuscule civil, ou null les nuits blanches. */
    val duskFraction: Float? = null,
    val duskLabel: String? = null,
)

/** Données prêtes à dessiner pour le champ « Avant la nuit ». */
data class NightFieldModel(
    /** « ARRIVÉE AVANT LA NUIT ? » */
    val title: String,
    val verdict: NightVerdict? = null,
    /** « OUI », « JUSTE », « NON ». */
    val verdictLabel: String? = null,
    /** « 8 min d'avance ». */
    val marginLabel: String? = null,
    /** « · estimation ± 14 ». */
    val uncertaintyLabel: String? = null,
    val timeline: NightTimeline? = null,
    /** « AU PIRE, LA NUIT VOUS PREND » et « à 1,8 km de l'arrivée ». */
    val worstCaseLabel: String? = null,
    val worstCaseValue: String? = null,
    /** « Coucher calculé hors ligne pour 45,18° N · 5,72° E ». */
    val footnote: String? = null,
    /**
     * Ce qui s'écrit à la place du verdict quand il n'y en a pas.
     *
     * Avec une frise, c'est un état d'attente — l'allure s'apprend — et la frise reste, car
     * l'heure du coucher, elle, est déjà sûre. Sans frise, c'est tout le champ qui attend.
     */
    val emptyMessage: String? = null,
)

/**
 * Dessine le verdict, puis ce qui le justifie.
 *
 * Le mot d'abord, énorme : c'est lui qu'on lit à trente kilomètres à l'heure, et c'est lui
 * seul qui répond à la question. La marge et l'incertitude dessous, pour qui veut savoir de
 * combien. La frise ensuite, qui montre la même chose qu'elle ne la dit — l'arrivée avec sa
 * fourchette, le coucher, la nuit. Et, seulement quand le pire cas ne passe pas, où la nuit
 * prendrait le coureur : c'est le chiffre qui décide de sortir la lampe maintenant ou au
 * prochain arrêt.
 */
object NightRenderer {

    fun render(width: Int, height: Int, model: NightFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    fun draw(canvas: Canvas, area: RectF, model: NightFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        if (width <= 0f || height <= 0f) return
        val padding = (height * 0.03f).coerceIn(4f, 20f)
        val left = area.left + padding
        val right = area.right - padding
        val bottom = area.bottom - padding

        val titleSize = (height * 0.032f).coerceIn(9f, 20f)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = titleSize
            letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        var cursor = area.top + padding + titleSize
        canvas.drawText(model.title, left, cursor, titlePaint)
        cursor += titleSize * 0.35f

        // Le pied de page est réservé avant tout le reste : il est petit, mais s'il manque,
        // rien ne dit d'où sort l'heure du coucher.
        val footnoteSize = (height * 0.021f).coerceIn(8f, 14f)
        val footnoteRoom = if (model.footnote != null && height >= MIN_HEIGHT_FOR_FOOTNOTE) {
            footnoteSize * 1.6f
        } else {
            0f
        }
        val contentBottom = bottom - footnoteRoom

        val timeline = model.timeline
        val verdictLabel = model.verdictLabel
        if (verdictLabel == null || model.verdict == null) {
            val message = model.emptyMessage
            if (timeline == null) {
                if (message != null) drawCentered(canvas, message, left, cursor, right, contentBottom, palette)
                return
            }
            // L'attente prend la place du verdict, sans en prendre la voix : corps moyen,
            // teinte secondaire. La frise suit, comme si de rien n'était.
            if (message != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textSecondary
                    textSize = (height * 0.05f).coerceIn(11f, 30f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                fit(paint, message, right - left)
                cursor += paint.textSize * 1.3f
                canvas.drawText(message, left, cursor, paint)
            }
            cursor += padding
        } else {
            val accent = verdictColor(model.verdict)
            val verdictSize = (height * 0.19f).coerceIn(22f, 132f)
            val verdictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = verdictSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            fit(verdictPaint, verdictLabel, right - left)
            cursor += verdictPaint.textSize * 0.92f
            canvas.drawText(verdictLabel, left, cursor, verdictPaint)

            // La marge dans la couleur du verdict, l'incertitude en retrait : la première
            // est la réponse, la seconde dit à quel point on peut la croire.
            val marginSize = (height * 0.042f).coerceIn(10f, 28f)
            val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = marginSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val uncertaintyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                textSize = marginSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            model.marginLabel?.let { margin ->
                cursor += marginSize * 1.5f
                canvas.drawText(margin, left, cursor, marginPaint)
                model.uncertaintyLabel?.let { uncertainty ->
                    val x = left + marginPaint.measureText(margin) + marginSize * 0.4f
                    if (x + uncertaintyPaint.measureText(uncertainty) <= right) {
                        canvas.drawText(uncertainty, x, cursor, uncertaintyPaint)
                    } else if (cursor + marginSize * 1.3f < contentBottom) {
                        cursor += marginSize * 1.3f
                        canvas.drawText(uncertainty, left, cursor, uncertaintyPaint)
                    }
                }
            }
            cursor += padding
        }

        if (timeline != null) {
            val timelineHeight = (height * 0.27f).coerceIn(40f, 180f)
            if (cursor + timelineHeight <= contentBottom) {
                drawTimeline(canvas, timeline, left, cursor, right, cursor + timelineHeight, model.verdict, palette)
                cursor += timelineHeight
            }
        }

        val worstLabel = model.worstCaseLabel
        val worstValue = model.worstCaseValue
        if (worstLabel != null && worstValue != null) {
            val valueSize = (height * 0.05f).coerceIn(12f, 32f)
            val block = padding + titleSize * 1.4f + valueSize * 1.3f
            if (cursor + block <= contentBottom) {
                cursor += padding
                canvas.drawLine(left, cursor, right, cursor, rulePaint(palette))
                cursor += titleSize * 1.4f
                canvas.drawText(worstLabel, left, cursor, titlePaint)
                val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textPrimary
                    textSize = valueSize
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                fit(valuePaint, worstValue, right - left)
                cursor += valueSize * 1.3f
                canvas.drawText(worstValue, left, cursor, valuePaint)
            }
        }

        if (footnoteRoom > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textSecondary
                alpha = FOOTNOTE_ALPHA
                textSize = footnoteSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val footnote = model.footnote.orEmpty()
            fit(paint, footnote, right - left)
            canvas.drawText(footnote, left, bottom - footnoteSize * 0.3f, paint)
        }
    }

    /**
     * La frise, du présent à la nuit.
     *
     * Le rail est gris ; il se teinte de crépuscule après le coucher et de nuit après la fin
     * du crépuscule civil. L'arrivée est une bande translucide — la fourchette — barrée d'un
     * trait à sa moyenne, dans la couleur du verdict. Le coucher est un trait jaune, le jaune
     * du Karoo, qui est ici la référence et non un itinéraire. Les libellés se répartissent
     * sur deux rangs sous le rail, pour que « coucher » et « nuit », souvent proches, ne se
     * chevauchent jamais.
     */
    private fun drawTimeline(
        canvas: Canvas,
        timeline: NightTimeline,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        verdict: NightVerdict?,
        palette: Palette,
    ) {
        val height = bottom - top
        val labelSize = (height * 0.12f).coerceIn(8f, 18f)
        val railHeight = (height * 0.07f).coerceIn(3f, 10f)
        // Un rang de libellé au-dessus (l'arrivée), deux dessous (maintenant et coucher, puis
        // la nuit) : le rail se place pour que tout tienne.
        val railTop = top + labelSize * 1.6f + railHeight * 1.2f
        val railBottom = railTop + railHeight
        if (railBottom + labelSize * 2.6f > bottom) return

        val inset = railHeight
        val railLeft = left + inset
        val railRight = right - inset
        fun x(fraction: Float) = railLeft + (railRight - railLeft) * fraction.coerceIn(0f, 1f)

        val radius = railHeight / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fill.color = palette.track
        canvas.drawRoundRect(RectF(railLeft, railTop, railRight, railBottom), radius, radius, fill)
        fill.color = TWILIGHT
        canvas.drawRoundRect(RectF(x(timeline.sunsetFraction), railTop, railRight, railBottom), radius, radius, fill)
        timeline.duskFraction?.let { dusk ->
            fill.color = NIGHT
            canvas.drawRoundRect(RectF(x(dusk), railTop, railRight, railBottom), radius, radius, fill)
        }

        // L'arrivée : la bande d'abord, sous les traits, pour ne rien cacher.
        val accent = verdictColor(verdict)
        timeline.arrivalFraction?.let { arrival ->
            val spread = timeline.arrivalSpread
            if (spread > 0f) {
                fill.color = FieldPalette.translucent(accent, BAND_ALPHA)
                canvas.drawRoundRect(
                    RectF(x(arrival - spread), railTop - railHeight * 0.8f, x(arrival + spread), railBottom + railHeight * 0.8f),
                    radius,
                    radius,
                    fill,
                )
            }
            val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                strokeWidth = (railHeight * 0.4f).coerceIn(2f, 4f)
            }
            canvas.drawLine(x(arrival), railTop - railHeight * 1.6f, x(arrival), railBottom + railHeight * 1.6f, mark)
            timeline.arrivalLabel?.let { label ->
                val paint = labelPaint(labelSize, accent, bold = true)
                canvas.drawText(label, anchored(paint, label, x(arrival), left, right), railTop - railHeight * 2.2f, paint)
            }
        }

        // Le coucher.
        val sunsetX = x(timeline.sunsetFraction)
        canvas.drawLine(
            sunsetX,
            railTop - railHeight * 1.2f,
            sunsetX,
            railBottom + railHeight * 1.2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = KarooColors.LEMON_YELLOW
                strokeWidth = (railHeight * 0.3f).coerceIn(2f, 3f)
            },
        )
        val firstRow = railBottom + railHeight * 1.2f + labelSize * 1.1f
        val secondRow = firstRow + labelSize * 1.25f
        val sunsetPaint = labelPaint(labelSize, KarooColors.LEMON_YELLOW)
        val nowPaint = labelPaint(labelSize, palette.textSecondary)
        val nowWidth = nowPaint.measureText(timeline.nowLabel)
        // « Maintenant » au bord gauche ; le coucher au rang du haut s'il ne le touche pas,
        // au rang du bas sinon — et la nuit prend le rang qui reste libre.
        val sunsetLeft = anchored(sunsetPaint, timeline.sunsetLabel, sunsetX, left, right)
        val sunsetOnFirstRow = sunsetLeft > left + nowWidth + labelSize * 0.6f
        canvas.drawText(timeline.sunsetLabel, sunsetLeft, if (sunsetOnFirstRow) firstRow else secondRow, sunsetPaint)

        // Maintenant : un triangle blanc sous le rail, à l'origine.
        val tri = Path().apply {
            moveTo(railLeft, railBottom + railHeight * 0.4f)
            lineTo(railLeft - railHeight * 0.7f, railBottom + railHeight * 1.6f)
            lineTo(railLeft + railHeight * 0.7f, railBottom + railHeight * 1.6f)
            close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary })
        canvas.drawText(timeline.nowLabel, left, firstRow, nowPaint)

        timeline.duskFraction?.let { dusk ->
            val duskX = x(dusk)
            canvas.drawLine(
                duskX,
                railBottom,
                duskX,
                railBottom + railHeight * 1.6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textSecondary
                    alpha = DUSK_ALPHA
                    strokeWidth = 1.5f
                },
            )
            timeline.duskLabel?.let { label ->
                val paint = labelPaint(labelSize, palette.textSecondary)
                val duskLeft = anchored(paint, label, duskX, left, right)
                val row = if (sunsetOnFirstRow) secondRow else firstRow
                // Au rang du haut, la nuit doit aussi laisser sa place à « maintenant ».
                val clear = row == secondRow || duskLeft > left + nowWidth + labelSize * 0.6f
                if (clear) canvas.drawText(label, duskLeft, row, paint)
            }
        }
    }

    /** Un libellé centré sur [x], mais jamais hors du champ. */
    private fun anchored(paint: Paint, text: String, x: Float, left: Float, right: Float): Float {
        val width = paint.measureText(text)
        return (x - width / 2f).coerceIn(left, max(left, right - width))
    }

    private fun labelPaint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rulePaint(palette: Palette) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.outline
        alpha = RULE_ALPHA
        strokeWidth = 1f
    }

    /** Réduit le corps jusqu'à ce que le texte tienne dans [maxWidth]. */
    private fun fit(paint: Paint, text: String, maxWidth: Float) {
        val measured = paint.measureText(text)
        if (measured > maxWidth && maxWidth > 0f) paint.textSize = (paint.textSize * maxWidth / measured).coerceAtLeast(8f)
    }

    private fun drawCentered(
        canvas: Canvas,
        message: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        palette: Palette,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = ((bottom - top) * 0.08f).coerceIn(10f, 26f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        fit(paint, message, (right - left) * 0.92f)
        val x = left + (right - left - paint.measureText(message)) / 2f
        val y = (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(message, max(x, left), y, paint)
    }

    /**
     * La couleur du verdict, dans la langue du Karoo.
     *
     * Le vert vif dit « donnée vive » et sert ici de feu vert ; le rouge est celui des états
     * d'erreur, et arriver de nuit sans lampe en est un. Entre les deux, le jaune des pentes
     * moyennes — ni le jaune citron, qui veut dire itinéraire, ni l'orange des rampes.
     */
    fun verdictColor(verdict: NightVerdict?): Int = when (verdict) {
        NightVerdict.YES -> KarooColors.HIGH_VIS_GREEN
        NightVerdict.TIGHT -> TIGHT
        NightVerdict.NO -> KarooColors.UI_RED
        null -> FieldPalette.NEUTRAL
    }

    const val TIGHT = 0xFFF0D800.toInt()

    /** Le rail après le coucher, puis après la nuit civile. */
    private const val TWILIGHT = KarooColors.AEGEAN_BLUE
    private const val NIGHT = 0xFF11181C.toInt()

    private const val BAND_ALPHA = 0x47
    private const val DUSK_ALPHA = 0x80
    private const val RULE_ALPHA = 0x59
    private const val FOOTNOTE_ALPHA = 0xB3

    /** Sous cette hauteur, le pied de page ne vaut pas la ligne qu'il coûte. */
    private const val MIN_HEIGHT_FOR_FOOTNOTE = 300f
}
