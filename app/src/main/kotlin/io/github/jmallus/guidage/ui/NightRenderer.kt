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
            model.marginLabel?.let { margin ->
                cursor += marginSize * 1.5f
                val used = drawMargin(canvas, margin, model.uncertaintyLabel, left, cursor, right, marginSize, accent, palette)
                if (!used && model.uncertaintyLabel != null && cursor + marginSize * 1.3f < contentBottom) {
                    cursor += marginSize * 1.3f
                    canvas.drawText(model.uncertaintyLabel, left, cursor, labelPaint(marginSize, palette.textSecondary))
                }
            }
            cursor += padding
        }

        if (timeline != null) {
            val timelineHeight = (height * 0.27f).coerceIn(40f, 180f)
            if (cursor + timelineHeight <= contentBottom) {
                drawTimeline(
                    canvas = canvas,
                    timeline = timeline,
                    left = left,
                    top = cursor,
                    right = right,
                    bottom = cursor + timelineHeight,
                    verdict = model.verdict,
                    palette = palette,
                    labelSize = (timelineHeight * 0.12f).coerceIn(8f, 18f),
                    railHeight = (timelineHeight * 0.07f).coerceIn(3f, 10f),
                    compact = false,
                )
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
     * Le champ réduit à une bande : le pied du tableau de bord.
     *
     * Le mot à gauche, la marge à sa suite, la frise dessous. Ni titre — le tableau de bord
     * n'en met à aucune case, et le mot se comprend seul —, ni pied de page, ni le pire cas :
     * une bande de quatre-vingts points n'a de place que pour la réponse et ce qui la montre.
     * La frise perd ses libellés « maintenant » et « nuit » : le triangle et le rail assombri
     * les disent encore, et deux rangs de texte n'entrent pas dans la hauteur.
     */
    fun drawBand(canvas: Canvas, area: RectF, model: NightFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        if (width <= 0f || height <= 0f) return
        val padding = (height * 0.05f).coerceIn(2f, 6f)
        val left = area.left + padding
        val right = area.right - padding

        val headRow = height * BAND_HEAD_FRACTION
        val baseline = area.top + headRow * 0.82f
        val verdictLabel = model.verdictLabel
        if (verdictLabel != null && model.verdict != null) {
            val accent = verdictColor(model.verdict)
            val verdictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = headRow * 0.9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            fit(verdictPaint, verdictLabel, (right - left) * 0.6f)
            canvas.drawText(verdictLabel, left, baseline, verdictPaint)
            model.marginLabel?.let { margin ->
                val marginSize = (height * 0.17f).coerceIn(10f, 16f)
                val x = left + verdictPaint.measureText(verdictLabel) + marginSize * 0.6f
                drawMargin(canvas, margin, model.uncertaintyLabel, x, baseline, right, marginSize, accent, palette)
            }
        } else {
            model.emptyMessage?.let { message ->
                val paint = labelPaint((height * 0.17f).coerceIn(10f, 16f), palette.textSecondary, bold = true)
                fit(paint, message, right - left)
                canvas.drawText(message, left, baseline, paint)
            }
        }

        model.timeline?.let { timeline ->
            // La frise prend ce que le mot lui laisse, et son corps s'en déduit plutôt que de
            // la hauteur de la bande : c'est la place réellement disponible qui décide, et
            // l'ancien calcul laissait un doigt de vide sous les libellés.
            val friseHeight = area.bottom - (area.top + headRow)
            val labelSize = (friseHeight / TIMELINE_ROWS).coerceIn(9f, 18f)
            drawTimeline(
                canvas = canvas,
                timeline = timeline,
                left = left,
                top = area.top + headRow,
                right = right,
                bottom = area.bottom,
                verdict = model.verdict,
                palette = palette,
                labelSize = labelSize,
                railHeight = (labelSize * RAIL_RATIO).coerceIn(3f, 8f),
                compact = true,
            )
        }
    }

    /**
     * La marge, puis l'incertitude à sa suite si elle tient sur la même ligne.
     *
     * Rend vrai quand l'incertitude a été écrite — ou qu'il n'y en avait pas à écrire.
     */
    private fun drawMargin(
        canvas: Canvas,
        margin: String,
        uncertainty: String?,
        left: Float,
        baseline: Float,
        right: Float,
        size: Float,
        accent: Int,
        palette: Palette,
    ): Boolean {
        val marginPaint = labelPaint(size, accent, bold = true)
        fit(marginPaint, margin, right - left)
        canvas.drawText(margin, left, baseline, marginPaint)
        if (uncertainty == null) return true
        val uncertaintyPaint = labelPaint(size, palette.textSecondary)
        val x = left + marginPaint.measureText(margin) + size * 0.4f
        if (x + uncertaintyPaint.measureText(uncertainty) > right) return false
        canvas.drawText(uncertainty, x, baseline, uncertaintyPaint)
        return true
    }

    /**
     * La frise, du présent à la nuit.
     *
     * Le rail est gris ; il se teinte de crépuscule après le coucher et de nuit après la fin
     * du crépuscule civil. L'arrivée est une bande translucide — la fourchette — barrée d'un
     * trait à sa moyenne, dans la couleur du verdict. Le coucher est un trait jaune, le jaune
     * du Karoo, qui est ici la référence et non un itinéraire. Les libellés se répartissent
     * sur deux rangs sous le rail, pour que « coucher » et « nuit », souvent proches, ne se
     * chevauchent jamais. En [compact], un seul rang dessous, qui ne porte que le coucher.
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
        labelSize: Float,
        railHeight: Float,
        compact: Boolean,
    ) {
        // Un rang de libellé au-dessus (l'arrivée), un ou deux dessous. La place nécessaire est
        // comptée sur ce qui sera réellement dessiné — le trait d'arrivée qui dépasse le rail,
        // la hampe des majuscules au-dessus, le jambage des lettres du dernier rang — et non
        // sur un multiple choisi au jugé : c'est ce qui permet d'écrire aussi gros que la
        // hauteur le permet, sans rien couper.
        val rowsBelow = if (compact) 1 else 2
        val aboveRoom = railHeight * MARK_OVERSHOOT + labelSize * ASCENT_RATIO
        val belowRoom = railHeight * MARK_OVERSHOOT * 0.55f +
            labelSize * (ROW_LEADING + ROW_PITCH * (rowsBelow - 1)) +
            labelSize * DESCENT_RATIO
        if (aboveRoom + railHeight + belowRoom > bottom - top) return
        val railTop = top + aboveRoom
        val railBottom = railTop + railHeight

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
                canvas.drawText(label, anchored(paint, label, x(arrival), left, right), railTop - railHeight * MARK_OVERSHOOT, paint)
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
        val firstRow = railBottom + railHeight * MARK_OVERSHOOT * 0.55f + labelSize * ROW_LEADING
        val secondRow = firstRow + labelSize * ROW_PITCH
        val sunsetPaint = labelPaint(labelSize, KarooColors.LEMON_YELLOW)
        val sunsetLeft = anchored(sunsetPaint, timeline.sunsetLabel, sunsetX, left, right)

        // Maintenant : un triangle blanc sous le rail, à l'origine.
        val tri = Path().apply {
            moveTo(railLeft, railBottom + railHeight * 0.4f)
            lineTo(railLeft - railHeight * 0.7f, railBottom + railHeight * 1.6f)
            lineTo(railLeft + railHeight * 0.7f, railBottom + railHeight * 1.6f)
            close()
        }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textPrimary })

        if (compact) {
            canvas.drawText(timeline.sunsetLabel, sunsetLeft, firstRow, sunsetPaint)
            return
        }

        // « Maintenant » au bord gauche ; le coucher au rang du haut s'il ne le touche pas,
        // au rang du bas sinon — et la nuit prend le rang qui reste libre.
        val nowPaint = labelPaint(labelSize, palette.textSecondary)
        val nowWidth = nowPaint.measureText(timeline.nowLabel)
        val sunsetOnFirstRow = sunsetLeft > left + nowWidth + labelSize * 0.6f
        canvas.drawText(timeline.sunsetLabel, sunsetLeft, if (sunsetOnFirstRow) firstRow else secondRow, sunsetPaint)
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

    /** Part de la bande donnée au mot et à sa marge ; la frise prend le reste. */
    private const val BAND_HEAD_FRACTION = 0.34f

    /**
     * De combien le trait d'arrivée dépasse le rail, en multiples de son épaisseur, et où se
     * pose le libellé qui le nomme.
     */
    private const val MARK_OVERSHOOT = 2.2f

    /** Distance du rail à la ligne de base du premier rang, et pas entre deux rangs. */
    private const val ROW_LEADING = 1.1f
    private const val ROW_PITCH = 1.25f

    /** Ce que la hampe des majuscules monte, et le jambage des lettres descend. */
    private const val ASCENT_RATIO = 0.78f
    private const val DESCENT_RATIO = 0.25f

    /**
     * Hauteurs de libellé qu'une frise compacte occupe en tout, rail et marges compris.
     *
     * Elle sert à choisir le corps d'après la place : `labelSize = hauteur / TIMELINE_ROWS`.
     * Sa valeur se déduit des ratios ci-dessus avec un rail à [RAIL_RATIO] du corps — la somme
     * fait 3,89 — arrondie au-dessus pour que l'arrondi des calculs ne fasse pas manquer la
     * frise d'un dixième de point : elle ne se dessine pas du tout quand elle ne tient pas.
     */
    private const val TIMELINE_ROWS = 4.0f

    /** Épaisseur du rail, en part du corps des libellés. */
    private const val RAIL_RATIO = 0.4f

    private const val BAND_ALPHA = 0x47
    private const val DUSK_ALPHA = 0x80
    private const val RULE_ALPHA = 0x59
    private const val FOOTNOTE_ALPHA = 0xB3

    /** Sous cette hauteur, le pied de page ne vaut pas la ligne qu'il coûte. */
    private const val MIN_HEIGHT_FOR_FOOTNOTE = 300f
}
