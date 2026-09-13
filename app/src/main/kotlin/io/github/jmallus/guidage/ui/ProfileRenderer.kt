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
    /**
     * Où se trouve le coureur, en distance depuis le départ de l'itinéraire.
     *
     * La fenêtre commence un peu **avant** lui, de sorte que sa marque ne soit pas collée au
     * bord : une marque posée sur le bord se confond avec le cadre, et l'on ne sait plus si
     * la silhouette commence sous les roues ou si elle est coupée. Le court bout de terrain
     * déjà parcouru qu'on voit alors n'est pas perdu — c'est la pente dont on sort.
     */
    val positionDistance: Double? = null,
    /** Message affiché quand il n'y a rien à montrer. */
    val emptyMessage: String? = null,
    /**
     * Vrai pour l'échelle comprimée au loin, faux pour une échelle régulière.
     *
     * La compression répond à « qu'est-ce qui reste » et vaut sur tout le parcours restant.
     * Sur une fenêtre courte, elle n'a plus rien à faire tenir et écrase le fond de la
     * fenêtre pour rien : c'est alors une échelle régulière qu'il faut, et la question posée
     * n'est plus la même — « qu'est-ce qui arrive ».
     */
    val compressed: Boolean = true,
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

    fun render(
        width: Int,
        height: Int,
        model: ProfileFieldModel,
        palette: Palette,
        encreMinimaleMm: Float = Lisibilite.ENCRE_MINIMALE_MM,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(
            Canvas(bitmap),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            model,
            palette,
            encreMinimaleMm,
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
    fun draw(
        canvas: Canvas,
        area: RectF,
        model: ProfileFieldModel,
        palette: Palette,
        encreMinimaleMm: Float = Lisibilite.ENCRE_MINIMALE_MM,
    ) {
        val width = area.width()
        val height = area.height()
        val padding = (min(width, height) * 0.04f).coerceIn(2f, 8f)
        // Les textes ne descendent pas sous le plancher : ils sont écrits assez gros pour être
        // lus, ou pas écrits du tout. Le corps se déduit de la hauteur d'encre voulue, jamais
        // de la place qui reste — c'est l'inversion de règle qui rendait le bandeau illisible.
        val corpsMinimal = Lisibilite.corpsPourCapitale(encreMinimaleMm)
        val labelSize = max((height * 0.16f).coerceAtMost(26f), corpsMinimal)
        val tickSize = max(labelSize * TICK_TEXT_RATIO, corpsMinimal)
        // Les chiffres de l'axe ne s'écrivent que si la bande peut les porter à cette taille,
        // la silhouette gardant sa part. Sinon les traits restent seuls : ils disent la
        // compression de l'échelle, qui est l'essentiel, et n'occupent aucune hauteur de texte.
        val labelled = height >= tickSize * LABELLED_AXIS_HEIGHTS
        val axis = if (labelled) TICK_LENGTH + tickSize * 1.35f else TICK_LENGTH

        // Les libellés du haut disparaissent avec la même règle que les chiffres de l'axe.
        val entetes = height >= labelSize * ENTETE_HEIGHTS
        val top = area.top + padding + if (entetes) labelSize else 0f
        val bottom = area.bottom - padding - axis
        val left = area.left + padding
        val right = area.right - padding

        val scale = FisheyeScale(model.window.distanceSpan, compressed = model.compressed)
        if (model.window.isEmpty || !scale.usable || bottom <= top || right <= left) {
            drawEmpty(canvas, area, model.emptyMessage, palette)
            return
        }

        val positionX = model.positionDistance
            ?.let { left + (scale.fractionAt(it - model.window.start) * (right - left)).toFloat() }
            ?.coerceIn(left, right)
            ?: left

        drawProfile(canvas, model, scale, left, top, right, bottom, positionX, palette)
        drawClimbMarkers(canvas, model, scale, left, top, right, bottom, labelSize, palette, positionX)
        drawPoiMarkers(canvas, model, scale, left, top, right, bottom)
        drawAxis(canvas, model, scale, left, right, bottom, tickSize, labelled, palette)
        drawPositionMarker(canvas, positionX, top, bottom)
        if (entetes) drawLabels(canvas, model, left, right, top, labelSize, palette)
    }

    /**
     * La silhouette, à la manière du profil natif du Karoo : devant le coureur, un aplat du
     * jaune de l'itinéraire, voilé, sous une crête du même jaune, franc ; derrière lui, la
     * crête seule, en blanc, sans aplat.
     *
     * Elle a porté les couleurs de pente du Karoo, colonne par colonne, et les a perdues sur
     * demande après une sortie : sur la bande, elles faisaient une mosaïque là où l'on cherche
     * une forme. La pente se lit à la silhouette, et la pente moyenne d'une côte s'écrit
     * au-dessus d'elle — c'est ce chiffre-là qu'on regarde, pas une teinte à décoder.
     *
     * La crête reste calculée colonne de pixels par colonne, et non segment par segment :
     * sous une échelle comprimée, cent segments du relevé tombent dans la même colonne, et
     * c'est le point le plus haut qu'elle couvre qui fait sa hauteur — un sommet ne peut pas
     * disparaître entre deux colonnes.
     */
    private fun drawProfile(
        canvas: Canvas,
        model: ProfileFieldModel,
        scale: FisheyeScale,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        positionX: Float,
        palette: Palette,
    ) {
        val window = model.window
        val points = window.points
        val elevationSpan = window.elevationSpan.takeIf { it > 0 } ?: return
        val first = ceil(left).toInt()
        val columns = (floor(right).toInt() - first).coerceAtLeast(1)

        fun y(elevation: Double) =
            bottom - ((elevation - window.minElevation) / elevationSpan * (bottom - top)).toFloat()

        val xs = FloatArray(columns) { first + it + 0.5f }
        val ys = FloatArray(columns)
        for (column in 0 until columns) {
            val from = window.start + scale.distanceAt(column.toDouble() / columns)
            val to = window.start + scale.distanceAt((column + 1).toDouble() / columns)
            // Au moins un pixel de relief, toujours : sur un plat au bas de l'échelle, la
            // silhouette se trouerait précisément là où le terrain est le plus régulier.
            ys[column] = min(y(crest(points, from, to)), bottom - 1f)
        }

        // La coupure entre le fait et le restant est à l'aplomb du coureur, pas au bord de la
        // colonne qui le porte : la marque de position s'y pose, et l'aplat doit partir d'elle.
        val split = (positionX - first).toInt().coerceIn(0, columns - 1)
        val devant = Path().apply {
            moveTo(positionX, ys[split])
            for (column in split until columns) lineTo(xs[column], ys[column])
        }
        val aplat = Path(devant).apply {
            lineTo(xs[columns - 1], bottom)
            lineTo(positionX, bottom)
            close()
        }
        canvas.drawPath(
            aplat,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = FILL
            },
        )
        canvas.drawPath(devant, crestPaint(CREST, CREST_WIDTH))

        if (split > 0) {
            val derriere = Path().apply {
                moveTo(xs[0], ys[0])
                for (column in 1..split) lineTo(xs[column], ys[column])
                lineTo(positionX, ys[split])
            }
            canvas.drawPath(derriere, crestPaint(palette.textPrimary, BEHIND_WIDTH))
        }
    }

    private fun crestPaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
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
        // Graisse moyenne : à corps égal, des chiffres maigres en bleu pâle par-dessus une
        // silhouette colorée se lisent nettement moins bien qu'en medium, pour le même
        // encombrement. C'est la fonte que le Karoo emploie lui-même pour ses libellés.
        val text = Lisibilite.pinceau(tickSize, palette.textSecondary).apply {
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
        positionX: Float,
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

        // La pente moyenne est écrite sur les trois prochaines côtes, même étroites : ce sont
        // celles qu'on prépare, et l'échelle comprimée les rétrécit précisément à mesure
        // qu'elles s'éloignent — la troisième n'aurait jamais eu son chiffre. Au-delà, seules
        // les côtes assez larges le portent : à dix kilomètres, un pourcentage n'est plus une
        // information mais un encombrement.
        var precedentDroite = Float.NEGATIVE_INFINITY
        model.climbs
            .filter { it.endDistance > window.start && it.startDistance < window.end }
            .forEachIndexed { rang, climb ->
                val startX = x(max(climb.startDistance, window.start))
                val endX = x(min(climb.endDistance, window.end))
                if (endX - startX < 6f) return@forEachIndexed

                // Un voile clair, sans teinte de pente : il dit l'étendue de la côte, et
                // c'est le chiffre au-dessus qui dit sa pente.
                overlay.color = FieldPalette.translucent(palette.textPrimary, CLIMB_OVERLAY_ALPHA)
                canvas.drawRect(startX, top, endX, bottom, overlay)

                val etiquette = "${climb.grade.toInt()}%"
                val demi = text.measureText(etiquette) / 2f
                // Le trait de position ne doit jamais barrer un chiffre : quand il tomberait
                // dedans, l'étiquette s'écarte du côté où il reste de la place. C'est presque
                // toujours vers la droite — le trait se tient dans le premier dixième — mais
                // un coureur au tout début de sa côte pousserait l'étiquette hors du cadre.
                val ecart = demi + labelSize * ECART_TRAIT
                val vise = (startX + endX) / 2
                val decale = when {
                    kotlin.math.abs(vise - positionX) >= ecart -> vise
                    positionX + ecart + demi <= right -> positionX + ecart
                    else -> positionX - ecart
                }
                val centre = decale.coerceIn(left + demi, right - demi)
                // Deux chiffres qui se chevauchent n'en font qu'un illisible : le second cède.
                if ((rang < COTES_ETIQUETEES || endX - startX > labelSize * 2.4f) &&
                    centre - demi > precedentDroite
                ) {
                    canvas.drawText(etiquette, centre, top + labelSize, text)
                    precedentDroite = centre + demi + labelSize * 0.3f
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

    /**
     * Le trait de position : vertical, du jaune de la crête, sur toute la hauteur.
     *
     * Il a été bleu, avec un triangle sous l'axe. Le Karoo le fait jaune et nu, et c'est la
     * silhouette qui change de teinte à son passage — blanc derrière, jaune devant — qui le
     * rend lisible, non sa propre couleur.
     */
    private fun drawPositionMarker(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        canvas.drawLine(x, top, x, bottom, crestPaint(CREST, CREST_WIDTH))
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

    /**
     * Le jaune de l'itinéraire du Karoo, franc sur la crête et le trait de position, voilé
     * sous la crête. Le voile laisse le fond de l'écran assombrir l'aplat, comme sur le
     * profil natif où il tire vers l'ocre.
     */
    internal const val CREST = KarooColors.LEMON_YELLOW
    internal const val FILL = 0xA6F2D600.toInt()
    private const val CREST_WIDTH = 3f

    /** La crête de ce qui est fait, en blanc et un peu plus fine : elle n'est plus l'enjeu. */
    private const val BEHIND_WIDTH = 2.5f

    /** Opacité du voile qui marque l'étendue d'une côte. */
    private const val CLIMB_OVERLAY_ALPHA = 36

    /** Longueur du trait d'une graduation sous l'axe. */
    /** Nombre de côtes portant leur pente moyenne quelle que soit leur largeur à l'écran. */
    private const val COTES_ETIQUETEES = 3

    /** Blanc gardé entre le trait de position et une étiquette de pente, en corps de celle-ci. */
    private const val ECART_TRAIT = 0.45f

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
    /** Hauteurs de libellé qu'il faut à la bande pour porter aussi les en-têtes. */
    private const val ENTETE_HEIGHTS = 4.2f

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
