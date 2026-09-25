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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    /**
     * Ce que le coureur a au compteur, écrit dans une étiquette au-dessus de sa marque —
     * « 27,1 », dans l'unité que l'axe porte déjà. Null pour ne pas l'écrire.
     */
    val positionLabel: String? = null,
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
    /**
     * La côte sur laquelle la fenêtre s'est cadrée, du pied au sommet, ou null.
     *
     * Le bandeau du tableau de bord bascule dessus tant qu'on la monte, comme le ClimbPro du
     * Karoo : la silhouette prend alors les couleurs de pente, tronçon par tronçon, parce que
     * c'est la question du moment — où est le passage dur, et combien en reste-t-il.
     */
    val climbZoom: RouteClimb? = null,
    /**
     * La portion de la côte que détaillent les cases de pente, depuis le départ (m) : une
     * fenêtre glissante, à son échelle à elle, plus fine que celle du profil.
     */
    val climbDetail: ClosedFloatingPointRange<Double>? = null,
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
        // Dans une côte, la ligne sous le profil porte des cases de pente, plus hautes que les
        // chiffres de l'axe.
        val axis = when {
            model.climbZoom != null -> tickSize * GRADE_TILE_HEIGHT
            labelled -> TICK_LENGTH + tickSize * 1.35f
            else -> TICK_LENGTH
        }

        // Les libellés du haut disparaissent avec la même règle que les chiffres de l'axe.
        val entetes = height >= labelSize * ENTETE_HEIGHTS
        // L'étiquette de position est plus grosse que les libellés : l'en-tête s'élargit d'autant
        // quand elle s'y pose, sans quoi elle déborderait du champ par le haut.
        //
        // L'en-tête du zoom de côte grossit de même : distance et dénivelé jusqu'au sommet sont
        // alors ce qu'on vient chercher sur le bandeau.
        val grosEnTete = model.positionLabel != null || model.climbZoom != null
        val enTete = if (grosEnTete) labelSize * POSITION_LABEL_SCALE else labelSize
        val top = area.top + padding + if (entetes) enTete else 0f
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

        // L'étiquette de position tient dans l'en-tête ; sans en-tête, elle ne s'écrit pas.
        val etiquette = model.positionLabel?.takeIf { entetes }

        drawProfile(canvas, model, scale, left, top, right, bottom, positionX, palette)
        // Cadré sur une côte, le voile qui en marque l'étendue couvrirait toute la bande, et sa
        // pente moyenne est déjà écrite dans l'en-tête.
        if (model.climbZoom == null) {
            drawClimbMarkers(canvas, model, scale, left, top, right, bottom, labelSize, palette)
        }
        drawPoiMarkers(canvas, model, scale, left, top, right, bottom)
        val cote = model.climbZoom
        if (cote != null) {
            // Dans une côte, la ligne sous le profil porte la pente de chaque tronçon plutôt que
            // les kilomètres : la distance au sommet est déjà dans l'en-tête, et c'est la pente
            // du morceau qui vient qu'on cherche — comme sur le ClimbPro du Karoo.
            val detail = model.climbDetail ?: (cote.startDistance..cote.endDistance)
            drawDetailBracket(canvas, model.window, detail, scale, left, right, bottom)
            drawGradeRow(canvas, detail, troncons(cote, model.window.points), left, right, bottom, model.positionDistance, tickSize)
        } else {
            drawAxis(canvas, model, scale, left, right, bottom, tickSize, labelled, palette)
        }
        drawPositionMarker(canvas, positionX, top, bottom)
        if (entetes) {
            val corpsEnTete = if (model.climbZoom != null) labelSize * POSITION_LABEL_SCALE else labelSize
            drawLabels(canvas, model, left, right, top, corpsEnTete, palette)
        }
        if (etiquette != null) {
            drawPositionLabel(canvas, etiquette, positionX, left, right, top, labelSize * POSITION_LABEL_SCALE, palette)
        }
    }

    /**
     * L'étiquette de position : une boîte claire aux coins ronds, posée sur l'en-tête à
     * l'aplomb de la marque, et le nombre en sombre dedans — le dessin du profil natif.
     *
     * Elle est retenue dans le cadre quand la marque approche d'un bord ; le trait, lui,
     * reste à l'aplomb, et c'est la boîte qui glisse.
     */
    private fun drawPositionLabel(
        canvas: Canvas,
        label: String,
        positionX: Float,
        left: Float,
        right: Float,
        top: Float,
        labelSize: Float,
        palette: Palette,
    ) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = POSITION_LABEL_INK
            textSize = labelSize * POSITION_LABEL_RATIO
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val demi = positionLabelHalfWidth(label, labelSize)
        val centre = positionX.coerceIn(left + demi, right - demi)
        val hauteur = labelSize
        val boite = RectF(centre - demi, top - hauteur, centre + demi, top)
        val rayon = hauteur * POSITION_LABEL_CORNER
        canvas.drawRoundRect(
            boite,
            rayon,
            rayon,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = palette.textPrimary
            },
        )
        canvas.drawText(label, centre, boite.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    /** Demi-largeur de l'étiquette de position, le blanc autour du nombre compris. */
    private fun positionLabelHalfWidth(label: String, labelSize: Float): Float {
        val text = Paint().apply {
            textSize = labelSize * POSITION_LABEL_RATIO
            typeface = Typeface.DEFAULT_BOLD
        }
        return text.measureText(label) / 2f + labelSize * POSITION_LABEL_PADDING
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
        val cote = model.climbZoom
        if (cote != null) {
            // Pas de crête : le Climber du Karoo n'en trace pas, la limite des aplats la dessine.
            drawGradeFill(canvas, window, cote, scale, xs, ys, first, left, right, bottom, positionX)
        } else {
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
        }

        if (split > 0) {
            val derriere = Path().apply {
                moveTo(xs[0], ys[0])
                for (column in 1..split) lineTo(xs[column], ys[column])
                lineTo(positionX, ys[split])
            }
            canvas.drawPath(derriere, crestPaint(palette.textPrimary, BEHIND_WIDTH))
        }
    }

    /**
     * L'aplat d'une côte en couleurs de pente, à la manière du ClimbPro du Karoo.
     *
     * La côte est découpée en tronçons de longueur ronde (voir [troncons]), chacun peint à
     * la couleur de sa pente propre. Des tronçons et non des colonnes : la mosaïque colonne par
     * colonne est ce qui avait fait retirer les couleurs du bandeau ordinaire, alors qu'une
     * poignée de marches se lit d'un coup d'œil — le passage à 9 % dans quatre cents mètres.
     *
     * Ce qui est monté passe au gris, comme sur l'appareil : sa pente n'est plus une question.
     */
    private fun drawGradeFill(
        canvas: Canvas,
        window: ProfileWindow,
        climb: RouteClimb,
        scale: FisheyeScale,
        xs: FloatArray,
        ys: FloatArray,
        first: Int,
        left: Float,
        right: Float,
        bottom: Float,
        positionX: Float,
    ) {
        fun x(distance: Double) =
            (left + scale.fractionAt(distance - window.start) * (right - left)).toFloat().coerceIn(left, right)
        fun crestAt(x: Float) = ys[(x - first).toInt().coerceIn(0, ys.size - 1)]

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val hachure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = HATCH_WIDTH
        }
        // Le profil montre toute la côte : à cent mètres, un col se peignait en soixante bandes
        // d'un pixel ou deux, la mosaïque qui avait fait retirer les couleurs du bandeau. Ses
        // tronçons s'allongent donc avec la côte — seize au plus —, quand les cases, dessous,
        // gardent les cent mètres de leur fenêtre.
        val pas = PROFILE_GRADE_STEPS.firstOrNull { ceil(climb.length / it) <= MAX_PROFILE_SEGMENTS }
            ?: PROFILE_GRADE_STEPS.last()
        for ((debut, fin, pente) in troncons(climb, window.points, pas)) {
            val xa = x(debut)
            val xb = x(fin)
            if (xb <= xa) continue
            val troncon = Path().apply {
                moveTo(xa, bottom)
                lineTo(xa, crestAt(xa))
                for (column in xs.indices) if (xs[column] > xa && xs[column] < xb) lineTo(xs[column], ys[column])
                lineTo(xb, crestAt(xb))
                lineTo(xb, bottom)
                close()
            }
            // Le tronçon sous les roues est coupé à l'aplomb du coureur : hachuré derrière, de
            // la couleur de sa pente, comme sur le Climber ; plein devant.
            val couleur = climbColor(pente)
            canvas.save()
            canvas.clipPath(troncon)
            canvas.clipRect(left, 0f, positionX, bottom)
            hachure.color = couleur
            var trait = xa - (bottom - crestAt(xa))
            while (trait < xb) {
                canvas.drawLine(trait, bottom, trait + (bottom - 0f), 0f, hachure)
                trait += HATCH_STEP
            }
            canvas.restore()
            canvas.save()
            canvas.clipRect(positionX, 0f, right, bottom)
            paint.color = couleur
            canvas.drawPath(troncon, paint)
            canvas.restore()
        }
    }

    /** Un tronçon de côte : où il commence et finit, depuis le départ (m), et sa pente (%). */
    internal data class Troncon(val debut: Double, val fin: Double, val pente: Double)

    /**
     * La côte découpée en tronçons de cent mètres, comptés depuis son pied.
     *
     * Toujours cent mètres, quelle que soit la côte : c'est la fenêtre du zoom qui s'ajuste
     * — elle n'en montre que seize à la fois —, et un « 9 » se lit ainsi toujours « 9 % sur les
     * cent mètres qui viennent ». Les bornes partent du pied et non du coureur, pour qu'un
     * tronçon ne change pas de pente à mesure qu'on avance. Un reste de moins d'un demi-pas
     * rejoint le dernier tronçon plutôt que de faire un bout de couleur trop étroit.
     */
    internal fun troncons(climb: RouteClimb, points: List<ProfilePoint>, pas: Double = GRADE_SEGMENT_METERS): List<Troncon> {
        if (climb.length <= 0.0) return emptyList()
        val bornes = mutableListOf(climb.startDistance)
        var suivante = climb.startDistance + pas
        while (suivante < climb.endDistance - pas / 2) {
            bornes += suivante
            suivante += pas
        }
        bornes += climb.endDistance
        return bornes.zipWithNext { debut, fin ->
            Troncon(debut, fin, (interpolate(points, fin) - interpolate(points, debut)) / (fin - debut) * 100.0)
        }
    }

    /**
     * Les couleurs de pente du Climber du Karoo, de la plus douce à la plus raide, et les
     * seuils qui les séparent (%).
     *
     * Relevées sur la légende que Hammerhead publie pour son Climber — les seuils y sont écrits,
     * les teintes lues sur l'image, hors reflet. Ce ne sont pas les couleurs des zones de
     * puissance, que Barberfish appliquait aux pentes : le Karoo a une palette à lui, plus
     * sourde, où 5 % est un jaune olive et non un vert menthe.
     */
    internal val CLIMB_COLORS = listOf(
        0xFF74BE96.toInt(), // moins de 2 % — vert menthe
        0xFF489A78.toInt(), // 2 à 4,9 % — vert
        0xFFCCC344.toInt(), // 5 à 7,9 % — jaune olive
        0xFFC9876A.toInt(), // 8 à 10,9 % — saumon
        0xFFC95A33.toInt(), // 11 à 13,9 % — orange
        0xFFA8302A.toInt(), // 14 à 19,9 % — rouge
        0xFFA0339A.toInt(), // 20 % et plus — violet
    )
    private val CLIMB_THRESHOLDS = listOf(2.0, 5.0, 8.0, 11.0, 14.0, 20.0)

    /** Le rang de la première couleur, l'orange, sur laquelle la pente s'écrit en blanc. */
    private const val CLIMB_WHITE_INK_FROM = 4

    /** La couleur d'un tronçon de côte, à une décimale près comme le chiffre de sa case. */
    internal fun climbColor(grade: Double): Int {
        val arrondi = Math.round(grade * 10.0) / 10.0
        return CLIMB_COLORS[CLIMB_THRESHOLDS.count { arrondi >= it }]
    }

    /**
     * L'encre d'une case de pente : noire jusqu'au saumon, blanche à partir de l'orange, comme
     * sur la légende du Karoo. Pas de mesure de contraste ici : elle donnait du blanc sur le
     * vert, que le Karoo écrit en noir, et c'est à lui que la case doit ressembler.
     */
    private fun climbInk(grade: Double): Int =
        if (CLIMB_COLORS.indexOf(climbColor(grade)) >= CLIMB_WHITE_INK_FROM) 0xFFFFFFFF.toInt() else GRADE_TILE_INK

    /**
     * Les cases de pente sous le profil, une par tronçon, à la manière du Climber du Karoo :
     * l'aplat de la couleur du tronçon, bordé de noir, et la pente à une décimale en noir.
     *
     * Toutes les cases visibles sont posées, même celle qu'on a derrière soi, coupée au bord :
     * c'est la rangée entière qui fait règle. Un chiffre qui ne tient pas dans sa case — une
     * case tronquée au bord — ne s'écrit pas.
     */
    private fun drawGradeRow(
        canvas: Canvas,
        detail: ClosedFloatingPointRange<Double>,
        troncons: List<Troncon>,
        left: Float,
        right: Float,
        bottom: Float,
        position: Double?,
        tickSize: Float,
    ) {
        val etendue = (detail.endInclusive - detail.start).takeIf { it > 0.0 } ?: return
        fun x(distance: Double) =
            (left + (distance - detail.start) / etendue * (right - left)).toFloat().coerceIn(left, right)
        val haut = bottom + GRADE_TILE_GAP
        val bas = bottom + tickSize * GRADE_TILE_HEIGHT
        val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = GRADE_TILE_BORDER
            color = GRADE_TILE_INK
        }
        // Le corps se règle sur une case entière, pour qu'un « 10,5 » y tienne ; il ne dépasse
        // pas la hauteur de la case, ni ne descend sous le plancher.
        val largeurCase = (right - left) * (GRADE_SEGMENT_METERS / etendue).toFloat()
        val etalon = Lisibilite.pinceau(tickSize, 0).measureText("10,5")
        val corps = (tickSize * largeurCase * GRADE_LABEL_FILL / etalon)
            .coerceIn(Lisibilite.corpsPourCapitale(), (bas - haut) * 0.8f)
        val text = Lisibilite.pinceau(corps, GRADE_TILE_INK).apply { textAlign = Paint.Align.CENTER }
        val ligne = (haut + bas) / 2f - (text.descent() + text.ascent()) / 2f
        troncons.forEach { troncon ->
            val xa = x(troncon.debut)
            val xb = x(troncon.fin)
            if (xb - xa < 1f) return@forEach
            val case = RectF(xa, haut, xb, bas)
            fond.color = climbColor(troncon.pente)
            canvas.drawRect(case, fond)
            canvas.drawRect(case, bord)
            val chiffre = String.format(Locale.getDefault(), "%.1f", troncon.pente)
            if (text.measureText(chiffre) + corps * GRADE_LABEL_MARGIN > xb - xa) return@forEach
            text.color = climbInk(troncon.pente)
            canvas.drawText(chiffre, (xa + xb) / 2f, ligne, text)
        }
        // Le coureur sur la rangée, à l'échelle de celle-ci : la marque du profil, au-dessus,
        // n'est pas à l'aplomb, les deux échelles différant.
        // Un triangle posé sur le bord haut des cases, pointe en bas : un trait les traversait
        // et coupait le chiffre de la case où l'on roule.
        position?.takeIf { it in detail }?.let {
            val xp = x(it)
            val demi = tickSize * POSITION_TRIANGLE
            val pointe = Path().apply {
                moveTo(xp - demi, haut - demi)
                lineTo(xp + demi, haut - demi)
                lineTo(xp, haut + demi * 0.6f)
                close()
            }
            canvas.drawPath(pointe, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = CREST
            })
            canvas.drawPath(pointe, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = GRADE_TILE_INK
            })
        }
    }

    /**
     * Sur le profil, la portion que détaillent les cases : un trait au pied de la silhouette,
     * de la largeur de la fenêtre glissante. C'est lui qui relie les deux échelles — sans lui,
     * rien ne dirait que les six cases ne couvrent qu'un bout de la côte.
     */
    private fun drawDetailBracket(
        canvas: Canvas,
        window: ProfileWindow,
        detail: ClosedFloatingPointRange<Double>,
        scale: FisheyeScale,
        left: Float,
        right: Float,
        bottom: Float,
    ) {
        fun x(distance: Double) =
            (left + scale.fractionAt(distance - window.start) * (right - left)).toFloat().coerceIn(left, right)
        val y = bottom - DETAIL_BRACKET_WIDTH / 2f
        canvas.drawLine(x(detail.start), y, x(detail.endInclusive), y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DETAIL_BRACKET
            strokeWidth = DETAIL_BRACKET_WIDTH
        })
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
        val unitMeters = Format.longDistanceUnitMeters(model.units)
        val unit = Format.longDistanceUnit(model.units)
        // Graisse moyenne : à corps égal, des chiffres maigres en bleu pâle par-dessus une
        // silhouette colorée se lisent nettement moins bien qu'en medium, pour le même
        // encombrement. C'est la fonte que le Karoo emploie lui-même pour ses libellés.
        val text = Lisibilite.pinceau(tickSize, palette.textSecondary).apply {
            textAlign = Paint.Align.CENTER
        }
        val baseline = bottom + TICK_LENGTH + tickSize

        // Sous l'échelle comprimée, les graduations comptent depuis le coureur et leur
        // espacement inégal est ce qui trahit la compression. À échelle régulière, elles
        // portent le compteur du parcours — « 26, 28, 30 » — comme sur le profil natif : la
        // marque de position s'y lit alors comme un point sur cette règle, et la portée n'a
        // plus à s'écrire, elle se lit sur les kilomètres.
        val graduations: List<Pair<Float, String>> = if (model.compressed) {
            val ticks = scale.ticks(minimumGap = gap.toDouble(), unit = unitMeters)
            ticks.mapIndexed { index, tick ->
                (left + (tick.fraction * usable).toFloat()) to Format.axisValue(tick.value) + unitSuffix(index, ticks.lastIndex, unit)
            }
        } else {
            // Les kilomètres du parcours tiennent en deux ou trois chiffres : l'écart exigé
            // se mesure sur eux, et non sur les libellés à décimale de l'échelle comprimée,
            // sans quoi l'axe sautait de 2 en 5 pour rien.
            val ecart = (tickSize * ABSOLUTE_TICK_LABEL_WIDTHS / usable).coerceIn(MIN_GAP, MAX_GAP)
            // Sans unité : les kilomètres du parcours se reconnaissent, comme sur le profil
            // natif, et « 14 km » sortait du cadre là où « 14 » y tient.
            absoluteTicks(model, left, usable, if (labelled) ecart else MIN_GAP, unitMeters)
        }
        if (graduations.isEmpty()) return

        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            strokeWidth = 2f
        }
        graduations.forEach { (x, caption) ->
            canvas.drawLine(x, bottom, x, bottom + TICK_LENGTH, rule)
            if (!labelled) return@forEach
            // Un chiffre qui sortirait du cadre à droite ne s'écrit pas ; son trait reste.
            if (x + text.measureText(caption) / 2f > right) return@forEach
            canvas.drawText(caption, x, baseline, text)
        }
    }

    /**
     * L'unité une seule fois, sur le dernier repère : la répéter à chaque graduation
     * remplirait l'axe du mot le moins informatif qu'il porte.
     */
    private fun unitSuffix(index: Int, last: Int, unit: String): String =
        if (index == last) " $unit" else ""

    /**
     * Les graduations d'une fenêtre à échelle régulière : les multiples ronds de l'unité
     * — tous les 1, 2 ou 5 kilomètres selon la place — comptés depuis le départ du parcours.
     */
    private fun absoluteTicks(
        model: ProfileFieldModel,
        left: Float,
        usable: Float,
        gap: Float,
        unitMeters: Double,
    ): List<Pair<Float, String>> {
        val window = model.window
        val span = window.distanceSpan.takeIf { it > 0.0 } ?: return emptyList()
        val step = ABSOLUTE_LADDER.firstOrNull { it * unitMeters / span >= gap } ?: return emptyList()
        val pas = step * unitMeters
        val premier = ceil(window.start / pas).toLong()
        val dernier = floor(window.end / pas).toLong()
        return (premier..dernier).map { k ->
            val x = left + ((k * pas - window.start) / span * usable).toFloat()
            x to Format.axisValue(k * step)
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

        // La pente moyenne est écrite sur les trois prochaines côtes, même étroites : ce sont
        // celles qu'on prépare, et l'échelle comprimée les rétrécit précisément à mesure
        // qu'elles s'éloignent — la troisième n'aurait jamais eu son chiffre. Au-delà, seules
        // les côtes assez larges le portent : à dix kilomètres, un pourcentage n'est plus une
        // information mais un encombrement.
        var precedentDroite = Float.NEGATIVE_INFINITY
        // Une côte passée ne se marque plus : son chiffre annonçait ce qui arrivait, et
        // derrière le coureur il ne dirait plus que ce qui est fait — la crête blanche le
        // montre déjà. Celle qu'on est en train de monter garde le sien, jusqu'au sommet.
        val devant = model.positionDistance ?: window.start
        model.climbs
            .filter { it.endDistance > devant && it.startDistance < window.end }
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
                // Toujours au centre de la côte, et il défile avec elle. Il s'est écarté un
                // temps du trait de position pour ne pas passer dessous, mais l'écart le
                // faisait sauter sur la fin de la côte, où il annonçait une rampe qui n'arrive
                // pas. Il passe donc sous l'étiquette de position, qui le couvre le temps du
                // passage : c'est ce qui a été demandé.
                val centre = ((startX + endX) / 2).coerceIn(left + demi, right - demi)
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
            // Devant le coureur seulement : la fenêtre commence derrière lui, et un point
            // déjà passé n'est plus un jalon, c'est un souvenir.
            .filter { it.distanceAlongRoute >= (model.positionDistance ?: window.start) && it.distanceAlongRoute <= window.end }
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
        canvas.drawLine(x, top, x, bottom, crestPaint(CREST, MARKER_WIDTH))
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
            // En blanc dans une côte : distance, dénivelé et rang y sont ce qu'on vient lire,
            // et non des légendes du profil.
            color = if (model.climbZoom != null) palette.textPrimary else palette.textSecondary
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

    /** Le trait de position, un peu plus épais que la crête qu'il croise. */
    private const val MARKER_WIDTH = 4f

    /** La crête de ce qui est fait, en blanc et un peu plus fine : elle n'est plus l'enjeu. */
    private const val BEHIND_WIDTH = 2.5f

    /** Opacité du voile qui marque l'étendue d'une côte. */
    private const val CLIMB_OVERLAY_ALPHA = 36

    /**
     * La longueur d'un tronçon de pente sous le zoom de côte (m), celle du ClimbPro du Karoo.
     */
    private const val GRADE_SEGMENT_METERS = 100.0

    /** Blanc exigé de part et d'autre d'un pourcentage sous son tronçon, en corps. */
    private const val GRADE_LABEL_MARGIN = 0.1f

    /** La part de la largeur d'un tronçon qu'un « 10 » peut occuper. */
    private const val GRADE_LABEL_FILL = 0.88f

    /** Les hachures de la part montée : épaisseur du trait et pas entre deux traits (px). */
    private const val HATCH_WIDTH = 3f
    private const val HATCH_STEP = 9f

    /**
     * Les cases de pente : hauteur en corps de graduation, blanc au-dessus, bordure, encre —
     * celle du Climber, un noir bleuté.
     */
    private const val GRADE_TILE_HEIGHT = 1.9f
    private const val GRADE_TILE_GAP = 3f
    private const val GRADE_TILE_BORDER = 2f
    private const val GRADE_TILE_INK = 0xFF06141A.toInt()

    /** Les tronçons du profil de côte entière (m), et leur nombre maximal. */
    private val PROFILE_GRADE_STEPS = listOf(100.0, 200.0, 250.0, 500.0, 1_000.0)
    private const val MAX_PROFILE_SEGMENTS = 16

    /** Demi-largeur du triangle de position sur les cases, en corps de graduation. */
    private const val POSITION_TRIANGLE = 0.4f

    /** Le trait qui marque, au pied du profil, la portion détaillée par les cases. */
    private const val DETAIL_BRACKET = 0xFFFFFFFF.toInt()
    private const val DETAIL_BRACKET_WIDTH = 5f

    /**
     * L'étiquette de position : l'encre sombre du nombre, son corps en part de celui des
     * libellés, le blanc de part et d'autre et l'arrondi des coins, en part de la hauteur.
     */
    private const val POSITION_LABEL_INK = 0xFF11181C.toInt()
    private const val POSITION_LABEL_RATIO = 0.9f
    private const val POSITION_LABEL_PADDING = 0.3f
    private const val POSITION_LABEL_CORNER = 0.25f

    /**
     * L'étiquette de position, un cinquième plus grosse que les libellés du profil.
     *
     * C'est le compteur du coureur, le chiffre qu'il cherche en regardant le bandeau ; à la
     * taille des libellés, il fallait s'y reprendre à deux fois après une sortie.
     */
    private const val POSITION_LABEL_SCALE = 1.2f

    /** Les pas possibles des graduations à échelle régulière, dans l'unité du coureur. */
    private val ABSOLUTE_LADDER = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

    /** Longueur du trait d'une graduation sous l'axe. */
    /** Nombre de côtes portant leur pente moyenne quelle que soit leur largeur à l'écran. */
    private const val COTES_ETIQUETEES = 3

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

    /** La même réserve pour les kilomètres du parcours, plus courts. */
    private const val ABSOLUTE_TICK_LABEL_WIDTHS = 2.2f

    private const val MIN_GAP = 0.08f
    private const val MAX_GAP = 0.34f
}
