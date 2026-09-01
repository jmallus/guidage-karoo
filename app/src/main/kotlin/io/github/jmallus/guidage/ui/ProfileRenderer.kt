package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
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
    /** Points d'intérêt de l'itinéraire, jalonnés sur la bande quand ils sont devant. */
    val pois: List<RoutePoi> = emptyList(),
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
        draw(
            Canvas(bitmap),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            model,
            palette,
        )
        return bitmap
    }

    /**
     * Le même profil, dessiné dans une zone d'un canevas plutôt que dans une image à lui.
     *
     * C'est ce qui permet au tableau de bord de porter ce champ en bandeau sans le composer
     * dans un bitmap intermédiaire : une image de plus par seconde, et surtout une seconde
     * écriture du profil, qui aurait dérivé de celle-ci.
     */
    fun draw(canvas: Canvas, area: RectF, model: ProfileFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        val labelSize = (height * 0.16f).coerceIn(9f, 20f)
        val padding = (min(width, height) * 0.04f).coerceIn(2f, 8f)
        val tickSize = (labelSize * TICK_TEXT_RATIO).coerceAtLeast(MIN_TICK_TEXT)
        val labelled = height >= tickSize * LABELLED_AXIS_HEIGHTS
        val axis = if (labelled) TICK_LENGTH + tickSize * 1.35f else TICK_LENGTH

        val top = area.top + padding + labelSize
        val bottom = area.bottom - padding - axis
        val left = area.left + padding
        val right = area.right - padding

        val scale = FisheyeScale(model.window.distanceSpan)
        if (model.window.isEmpty || !scale.usable || bottom <= top || right <= left) {
            drawEmpty(canvas, area, model.emptyMessage, palette)
            return
        }

        drawProfile(canvas, model, scale, left, top, right, bottom, palette)
        drawClimbMarkers(canvas, model, scale, left, top, right, bottom, labelSize, palette)
        drawPoiMarkers(canvas, model, scale, left, top, right, bottom)
        drawAxis(canvas, model, scale, left, right, bottom, tickSize, labelled, palette)
        drawPositionMarker(canvas, left, top, bottom, palette)
        drawLabels(canvas, model, left, right, top, labelSize, palette)
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

    /**
     * Les points d'intérêt à venir, jalonnés sur la bande.
     *
     * La marque est un **triangle pointe en bas**, et non un disque, pour la raison même que
     * le rendu de la carte donne à son repère : une pointe désigne un endroit précis, là où un
     * disque ne fait que le couvrir. À cette échelle, un point d'intérêt occupe une distance
     * nulle et l'œil doit pouvoir lire laquelle sans arbitrer entre deux bords.
     *
     * La marque seule flotterait : c'est le trait qui la rattache à la bande, en descendant de
     * sa pointe jusqu'à la base. Il est **pointillé** là où le repère de position est plein —
     * deux verticales qui ne disent pas la même chose ne peuvent pas se ressembler, et la
     * forme les sépare mieux que la teinte à trois pixels de large.
     *
     * Le magenta est celui des pastilles de la carte, pris au même endroit : c'est ce qui
     * permet de reconnaître la même chose d'un champ à l'autre.
     *
     * L'écart minimal n'est pas cosmétique. L'échelle comprime le lointain au point que
     * plusieurs points d'intérêt de la fin y tombent dans la même colonne : sans lui, ils se
     * superposeraient en une tache dont on ne saurait ni combien ils sont, ni où.
     *
     * Il se mesure sur l'**épaisseur du trait**, jamais sur la demi-largeur de la marque.
     * Celle-ci dit la taille du dessin, pas la résolution de la lecture : indexé sur elle,
     * grossir la marque faisait disparaître des points bien distincts — deux ravitaillements
     * séparés de 1,4 km s'effaçaient l'un l'autre dans le champ plein. Deux marques qui se
     * touchent restent deux ; deux marques au même endroit n'en font qu'une.
     */
    private fun drawPoiMarkers(
        canvas: Canvas,
        model: ProfileFieldModel,
        scale: FisheyeScale,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (model.pois.isEmpty()) return
        val window = model.window
        val demiLargeur = ((bottom - top) * POI_MARK_FRACTION).coerceIn(6f, 11f)
        // Le trait reste mince quand la marque grossit : c'est une tige, pas une barre, et
        // c'est la tête qu'on doit voir. Les tirets suivent l'épaisseur plutôt que la
        // demi-largeur, sans quoi une grosse marque les espaçait au point de n'en laisser deux.
        val epaisseur = max(2f, demiLargeur * 0.34f)
        val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.POI
            style = Paint.Style.STROKE
            strokeWidth = epaisseur
            pathEffect = DashPathEffect(floatArrayOf(epaisseur * 1.8f, epaisseur * 1.8f), 0f)
        }
        val marque = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FieldPalette.POI
            style = Paint.Style.FILL
        }
        val cy = top + demiLargeur + 1f
        val ecartMini = max(4f, epaisseur * 1.6f)
        val triangle = Path()
        var precedent = Float.NEGATIVE_INFINITY

        model.pois
            .filter { it.distanceAlongRoute >= window.start && it.distanceAlongRoute <= window.end }
            .sortedBy { it.distanceAlongRoute }
            .forEach { poi ->
                val fraction = scale.fractionAt(poi.distanceAlongRoute - window.start)
                val x = left + (fraction * (right - left)).toFloat()
                if (x - precedent < ecartMini) return@forEach
                precedent = x
                val pointe = cy + demiLargeur * POI_TIP_RATIO
                canvas.drawLine(x, pointe, x, bottom, trait)
                triangle.rewind()
                triangle.moveTo(x - demiLargeur, cy - demiLargeur)
                triangle.lineTo(x + demiLargeur, cy - demiLargeur)
                triangle.lineTo(x, pointe)
                triangle.close()
                canvas.drawPath(triangle, marque)
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

    private fun drawEmpty(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (area.height() * 0.22f).coerceIn(10f, 26f)
        }
        val centerY = area.centerY() - (paint.descent() + paint.ascent()) / 2f
        // Le message est ramené à la largeur du champ. Ces phrases-là sont longues — elles
        // expliquent une absence — et débordaient d'un champ étroit, où elles se lisaient
        // tronquées sans que rien ne le signale. Mieux vaut l'écrire plus petit que le couper.
        val text = message ?: "—"
        val place = area.width() * 0.92f
        val mesure = paint.measureText(text)
        if (mesure > place && place > 0f) paint.textSize *= place / mesure
        canvas.drawText(text, area.centerX(), centerY, paint)
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

    /**
     * Demi-largeur de la marque d'un point d'intérêt, en part de la hauteur de la bande.
     *
     * Relevé sur l'appareil : à 5,5 %, la marque faisait 2,8 px de demi-largeur dans le
     * bandeau du tableau de bord, soit **0,37 mm** sur l'écran du Karoo 3 — invisible en
     * roulant. Elle vaut désormais 7,2 px là, et 11 px dans le champ plein.
     */
    private const val POI_MARK_FRACTION = 0.14f

    /**
     * Longueur de la pointe du triangle, en part de sa demi-largeur.
     *
     * Au-delà de 1, le triangle descend plus bas que ne l'aurait fait un disque de même
     * largeur : c'est voulu. Un triangle inscrit dans le cercle ne couvre que quatre dixièmes
     * de sa surface et paraîtrait avoir rapetissé, alors qu'on vient de le grossir.
     */
    private const val POI_TIP_RATIO = 1.2f

    /** Longueur du trait d'une graduation sous l'axe. */
    private const val TICK_LENGTH = 4f

    /**
     * Corps des graduations, en part de celui des libellés du haut.
     *
     * Elles ne sont pas une mention légale : c'est **par elles** que la compression de
     * l'échelle se lit. Un œil qui suppose une échelle régulière lit un faux relief, et
     * l'espacement inégal des chiffres est la seule chose qui le détrompe. À 72 % elles
     * étaient au bord du lisible en roulant, et 88 % ne suffisait toujours pas.
     *
     * À parité, elles atteignent le corps des libellés du haut — c'est le plafond de ce
     * réglage-ci, et il est volontaire : au-delà, l'axe pèserait plus que la distance et le
     * dénivelé qu'il sert à situer. Les grossir encore demanderait de desserrer le plafond
     * des libellés, pas ce rapport.
     */
    private const val TICK_TEXT_RATIO = 1.0f
    private const val MIN_TICK_TEXT = 11f

    /**
     * Le champ ne porte les chiffres de l'axe qu'à partir de cette hauteur, en corps.
     *
     * Desserré chaque fois que les chiffres grossissent : le seuil se compte en multiples du
     * corps, si bien qu'agrandir la police relève le seuil et peut faire disparaître les
     * chiffres là où on voulait mieux les voir. Le bandeau bas du tableau de bord (102 px)
     * est le cas court qui décide : à 6,2 il tombait à 0,8 px du seuil, une marge qu'un
     * simple changement de gabarit efface sans prévenir. Les traits, eux, subsistent toujours.
     */
    private const val LABELLED_AXIS_HEIGHTS = 5.6f

    /** Largeur réservée à une étiquette de graduation, en corps. */
    private const val TICK_LABEL_WIDTHS = 3.4f

    private const val MIN_GAP = 0.08f
    private const val MAX_GAP = 0.34f
}
