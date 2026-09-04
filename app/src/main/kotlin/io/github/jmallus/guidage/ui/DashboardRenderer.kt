package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.github.jmallus.guidage.core.Contrast
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

/** Ce qu'on affiche dans la colonne de guidage, à droite des mesures. */
sealed interface GuidanceZone {
    data class Map(val model: MapModel) : GuidanceZone

    data class Profile(val model: RouteGraphModel) : GuidanceZone
}

/**
 * Le champ plein écran.
 *
 * Cinq rangs : le bandeau de l'effort instantané en haut, puis la carte à droite sur deux
 * hauteurs avec la transmission et le cœur à sa gauche, les distances et la pente en
 * dessous, et la bande du soir tout en bas.
 */
data class DashboardModel(
    val guidance: GuidanceZone,
    /** Bandeau du haut : vitesse, cadence, puissance. */
    val topTiles: List<Tile>,
    /** Case de gauche sous le bandeau : la transmission. */
    val drivetrain: DrivetrainModel? = null,
    /** Case de gauche suivante : la fréquence cardiaque. */
    val heartRateTile: Tile? = null,
    /**
     * À droite du cœur : ce qu'il reste à parcourir.
     *
     * Il occupait un rang à lui, avec la distance parcourue et la pente instantanée. Ce rang
     * a disparu : la distance parcourue redit ce que le Karoo enregistre, la pente se lit à la
     * couleur du profil sous la position du coureur, et les quatre-vingts points ainsi rendus
     * étaient la seule réserve de place de l'écran. Le restant, lui, se regarde tout le temps :
     * il vient à côté du cœur.
     */
    val remainingTile: Tile? = null,
    /**
     * Dernier rang : le champ « Avant la nuit » réduit à une bande, sur toute la largeur.
     *
     * Il a remplacé la case de l'heure d'arrivée : l'heure y est toujours, sur la frise, mais
     * rapprochée de ce à quoi on la compare de tête en fin de journée — le coucher. Sans
     * position ni coucher, la bande le dit ; elle ne cède le rang à rien d'autre, pour que la
     * mise en page ne bouge pas en cours de route.
     */
    val night: NightFieldModel,
    /**
     * Bandeau du bas : le champ « Profil à venir », tel quel.
     *
     * Il portait les deux kilomètres qui entourent le coureur, à échelle régulière. Ce
     * cadrage-là répondait à « qu'est-ce que je monte », jamais à « qu'est-ce qui reste » —
     * et la première question a déjà sa réponse ailleurs sur l'écran, dans la pente et la
     * distance au sommet. Le bandeau montre donc maintenant tout le parcours restant, sur
     * l'échelle comprimée au loin du champ homonyme, dont il exécute le rendu même.
     */
    val profileBand: ProfileFieldModel? = null,
    val palette: Palette,
)

object DashboardRenderer {

    /** Largeur de la colonne de gauche. */
    private const val TILE_COLUMN_FRACTION = 0.5f

    /** Hauteur d'un rang de la grille, en part de la hauteur utile ; le reste va au pied. */
    private const val ROW_HEIGHT_FRACTION = 0.2455f

    /** Nombre de rangs de grille avant le pied : l'effort, puis les deux du guidage. */
    private const val GRID_ROWS = 3

    /** Nombre de hauteurs de rang occupées par le guidage. */
    private const val GUIDANCE_ROWS = 2

    /** Nombre de cases du bandeau du haut. */
    private const val TOP_TILES = 3

    /** Corps des chiffres du bandeau du haut, en part de celui des autres cases. */
    private const val TOP_VALUE_RATIO = 0.78f

    /** Proportions relevées sur la maquette, exprimées en part de la hauteur de case. */
    private const val VALUE_HEIGHT_FRACTION = 0.614f
    private const val LABEL_HEIGHT_FRACTION = 0.147f

    /** Taille du suffixe, en part de celle de la valeur. */
    private const val SUFFIX_RATIO = 0.52f

    /**
     * Hauteur du bandeau de profil.
     *
     * Près d'un quart, contre un sixième tant qu'un rang de nombres s'intercalait au-dessus du
     * pied. La moitié de ce rang lui est revenue, et c'est ce qui lui permet de porter les
     * chiffres de son axe à une taille qui se lise en roulant : à cent trois points il fallait
     * les écrire à sept dixièmes de millimètre, ou les abandonner.
     */
    private const val PROFILE_BAND_FRACTION = 0.224f

    fun render(
        context: Context,
        width: Int,
        height: Int,
        model: DashboardModel,
        encreMinimaleMm: Float = Lisibilite.ENCRE_MINIMALE_MM,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val padding = (min(width, height) * 0.015f).coerceIn(2f, 6f)
        val columnSplit = width * TILE_COLUMN_FRACTION
        val right = width - padding

        // Le bandeau mange le bas de l'écran ; tout le reste se serre au-dessus. Il est là
        // dès qu'on navigue, de sorte que la mise en page ne bouge plus en cours de route.
        val bandHeight = if (model.profileBand == null) 0f else height * PROFILE_BAND_FRACTION
        val contentHeight = height - bandHeight
        val usableHeight = contentHeight - 2 * padding
        val rowHeight = usableHeight * ROW_HEIGHT_FRACTION
        fun row(index: Int) = padding + index * rowHeight
        // Le pied commence où finit la grille : le rang « distance parcourue et pente » qui
        // s'intercalait ici a été supprimé, et sa hauteur partagée entre la bande du soir et
        // le bandeau de profil — les deux seuls endroits de l'écran qui manquaient de place
        // pour écrire lisiblement.
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

        // Colonne gauche, sous la transmission : le cœur et ce qu'il reste, côte à côte.
        //
        // Les deux partagent un rang que le cœur occupait seul. C'est ce que la suppression du
        // rang « distance parcourue · restant · pente » a rendu nécessaire — et possible : la
        // distance parcourue et la pente instantanée disaient ce que le Karoo enregistre de
        // son côté et ce que la couleur du profil montre déjà, quand la distance restante est
        // le nombre qu'on regarde le plus.
        val colonneTiles = listOfNotNull(model.heartRateTile, model.remainingTile)
        val valueSize = if (colonneTiles.isEmpty()) {
            rowHeight * VALUE_HEIGHT_FRACTION
        } else {
            val cellWidth = (columnSplit - padding) / colonneTiles.size
            drawTiles(
                context = context,
                canvas = canvas,
                bounds = colonneTiles.indices.map { index ->
                    RectF(
                        padding + index * cellWidth,
                        row(2),
                        padding + (index + 1) * cellWidth,
                        row(3),
                    )
                },
                tiles = colonneTiles,
                palette = model.palette,
                valueFraction = VALUE_HEIGHT_FRACTION,
                labelFraction = LABEL_HEIGHT_FRACTION,
            )
        }

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
                labelSize = fitLabelSize(
                    label = drivetrain.label,
                    hasIcon = drivetrain.icon != null,
                    maxWidth = columnSplit - padding - EDGE_INSET * 2,
                    preferredSize = rowHeight * LABEL_HEIGHT_FRACTION,
                ).size,
            )
        }

        // Ligne du bas : la bande « Avant la nuit », sur toute la largeur.
        NightRenderer.drawBand(
            canvas,
            RectF(padding, footerTop, right, footerBottom),
            model.night,
            model.palette,
            encreMinimaleMm,
        )

        // Tout en bas : le parcours restant, sur toute la largeur, par le rendu du champ
        // « Profil à venir » lui-même. Le redessiner ici en aurait fait une seconde écriture.
        model.profileBand?.let { band ->
            ProfileRenderer.draw(
                canvas = canvas,
                area = RectF(padding, contentHeight, width - padding, height.toFloat()),
                model = band,
                palette = model.palette,
                encreMinimaleMm = encreMinimaleMm,
            )
        }
        return bitmap
    }

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
        // La plus courte des cases, et non la première : une taille calculée sur une case
        // haute déborde de toutes les autres, et un débordement vertical ne se voit qu'à
        // l'écran — aucune mesure de largeur ne l'attrape.
        val cellHeight = bounds.minOf { it.height() }
        // Une seule taille de libellé pour le groupe, comme pour les valeurs : celle qui
        // laisse l'icône à découvert dans la plus étroite des cases.
        val ajustements = tiles.zip(bounds).map { (tile, box) ->
            fitLabelSize(tile.label, tile.icon != null, box.width() - EDGE_INSET * 2, cellHeight * labelFraction)
        }
        val labelSize = ajustements.minOf { it.size }
        // L'icône tombe pour tout le groupe ou pour personne : une case ornée à côté d'une case
        // nue se lit comme deux choses de nature différente.
        val avecIcones = ajustements.all { it.avecIcone }
        val preferred = cellHeight * valueFraction
        val valueSize = tiles.zip(bounds).minOf { (tile, box) ->
            fitValueSize(tile, box.width() - EDGE_INSET * 2, preferred)
        }

        tiles.zip(bounds).forEach { (tile, box) ->
            drawTile(context, canvas, box, tile, palette, valueSize, labelSize, avecIcones)
        }
        return valueSize
    }

    /**
     * Une case : le titre centré, la valeur centrée sous lui.
     *
     * Les deux étaient alignés à droite, ce qui faisait tomber les unités et les chiffres des
     * poids faibles sur une même verticale d'une case à l'autre. C'est une qualité de tableau,
     * où l'on compare des colonnes de nombres ; ici les cases ne sont pas d'une même colonne,
     * elles sont côte à côte et de largeurs différentes, et l'alignement à droite y rejetait
     * chaque valeur contre le bord de la suivante. Centrée, chacune se tient dans sa case et
     * l'œil la trouve là où il la cherche.
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
        avecIcone: Boolean = true,
    ) {
        val ink = tile.background?.let { Contrast.bestTextColor(it) } ?: palette.textPrimary
        val labelInk = if (tile.background == null) {
            palette.textSecondary
        } else {
            translucent(ink, LABEL_ALPHA)
        }

        // L'aplat est posé sans cerne. Il en portait un, noir, contre la contamination de deux
        // couleurs voisines à l'œil ; mais dans cette mise en page les cases colorées ne se
        // touchent jamais — la cadence sépare la vitesse de la puissance, le cœur est seul
        // dans sa colonne — et le cerne ne séparait donc rien. Il enfermait seulement chaque
        // valeur dans une boîte.
        tile.background?.let { background ->
            canvas.drawRect(bounds, Paint().apply { color = background })
        }

        // Sur fond neutre l'icône est verte ; sur un aplat de zone elle suit l'encre, le
        // vert n'ayant aucune raison d'être lisible sur les sept couleurs de la palette.
        val iconInk = if (tile.background == null) palette.iconTint else labelInk

        val labelPaint = paint(labelSize, labelInk, LABEL_TYPEFACE)
        val valuePaint = paint(valueSize, ink, VALUE_TYPEFACE)
        val suffixPaint = paint(valueSize * SUFFIX_RATIO, ink, VALUE_TYPEFACE)

        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val valueHeight = valuePaint.descent() - valuePaint.ascent()
        val top = labelTop(bounds, labelHeight)

        drawLabelRow(context, canvas, bounds, tile, labelPaint, iconInk, top, labelSize, avecIcone)

        // La valeur, sa décimale et son unité forment un bloc unique, centré dans ce qui reste
        // sous le libellé.
        val valueWidth = valuePaint.measureText(tile.value)
        val tail = tile.decimal ?: tile.suffix
        val tailWidth = tail?.let { suffixPaint.measureText(it) } ?: 0f
        val baseline = valueTop(bounds, top, labelHeight, valueHeight) - valuePaint.ascent()

        val bandLeft = bounds.left + EDGE_INSET
        val bandRight = bounds.right - EDGE_INSET
        val left = (bandLeft + (bandRight - bandLeft - (valueWidth + tailWidth)) / 2f)
            .coerceAtLeast(bandLeft)

        canvas.drawText(tile.value, left, baseline, valuePaint)
        if (tail != null) {
            // La décimale monte en exposant, l'unité reste sur la ligne de base.
            val rise = if (tile.decimal == null) 0f else decimalRise(tile.value, tail, valuePaint, suffixPaint)
            canvas.drawText(tail, left + valueWidth, baseline - rise, suffixPaint)
        }
    }

    /**
     * De combien la décimale monte au-dessus de la ligne de base : juste assez pour que son
     * sommet tombe sur celui des chiffres qui la précèdent.
     *
     * Elle montait auparavant d'une fraction fixe de la hauteur des chiffres, réglée à l'œil.
     * Une fraction ne peut pas convenir partout : le rapport entre le corps de la valeur et
     * celui de la décimale est constant, mais la hauteur des chiffres varie d'un rang à
     * l'autre, et la décimale flottait tantôt sous le sommet, tantôt au-dessus.
     *
     * Les deux sommets sont donc mesurés sur l'encre elle-même plutôt que calculés : la
     * hauteur des chiffres n'est pas celle que la fonte annonce — l'ascendante réserve de la
     * place pour des accents que les chiffres n'ont pas.
     */
    private fun decimalRise(value: String, decimal: String, valuePaint: Paint, decimalPaint: Paint): Float {
        val ink = Rect()
        valuePaint.getTextBounds(value, 0, value.length, ink)
        val sommetValeur = ink.top
        decimalPaint.getTextBounds(decimal, 0, decimal.length, ink)
        return (ink.top - sommetValeur).toFloat()
    }

    /**
     * Le titre de la case, calé en haut à droite, son icône à l'extrême droite.
     *
     * Ce n'est pas un choix mais une règle du système visuel de Hammerhead : « Labels are
     * always locked in the upper right hand corner to match the rest of the in-ride design
     * language. » Un champ d'extension qui range ses titres ailleurs se dénonce comme
     * étranger au milieu des champs natifs.
     *
     * L'icône passe donc **après** le mot et non devant. C'est l'ordre du système, et il se
     * défend : contre le bord, elle marque la fin du titre là où l'œil revient, et deux cases
     * voisines alignent leurs icônes sur une même verticale.
     *
     * Le bloc est retenu par le bord gauche s'il est plus large que la case : mieux vaut un
     * titre décalé qu'un titre dont le début sort du cadre.
     */
    private fun drawLabelRow(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        tile: Tile,
        labelPaint: Paint,
        iconInk: Int,
        top: Float,
        labelSize: Float,
        avecIcone: Boolean = true,
    ) {
        val icone = tile.icon.takeIf { avecIcone }
        val iconSize = labelSize * ICON_RATIO
        val labelWidth = labelPaint.measureText(tile.label)
        val iconWidth = if (icone != null) iconSize + LABEL_GAP else 0f
        // L'intitulé se centre sur sa case, icône comprise, comme la valeur qu'il surmonte.
        // Aligné à droite, il se collait au bord et l'œil devait le chercher ailleurs que là
        // où il cherche le chiffre — deux points de fixation par case au lieu d'un.
        val left = (bounds.centerX() - (labelWidth + iconWidth) / 2f)
            .coerceAtLeast(bounds.left + EDGE_INSET)

        canvas.drawText(tile.label, left, top - labelPaint.ascent(), labelPaint)

        icone?.let { resource ->
            val drawable = ContextCompat.getDrawable(context, resource)
            if (drawable != null) {
                // L'icône est plus grande que le libellé et se pose sur la même ligne médiane.
                val iconLeft = left + labelWidth + LABEL_GAP
                val iconTop = top + (labelPaint.descent() - labelPaint.ascent() - iconSize) / 2f
                drawable.setTint(iconInk)
                drawable.setBounds(
                    iconLeft.roundToInt(),
                    iconTop.roundToInt(),
                    (iconLeft + iconSize).roundToInt(),
                    (iconTop + iconSize).roundToInt(),
                )
                drawable.draw(canvas)
            }
        }
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
        val labelPaint = paint(labelSize, palette.textSecondary, LABEL_TYPEFACE)
        val valuePaint = paint(valueSize, 0, VALUE_TYPEFACE)
        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val valueHeight = valuePaint.descent() - valuePaint.ascent()
        val top = labelTop(bounds, labelHeight)
        val right = bounds.right - EDGE_INSET

        drawLabelRow(
            context = context,
            canvas = canvas,
            bounds = bounds,
            tile = Tile(label = model.label, value = "", icon = model.icon),
            labelPaint = labelPaint,
            iconInk = palette.iconTint,
            top = top,
            labelSize = labelSize,
        )

        // Le schéma occupe la même bande que les chiffres des autres cases, mais s'arrête
        // franchement au-dessus du bord. Ailleurs, le bas de cette bande est vide — les
        // chiffres n'ont pas de jambages — tandis qu'ici la ligne des dentures s'y pose. Or
        // la case du dessous porte un aplat de couleur qui commence net : une ligne écrite à
        // un cheveu de lui paraît lui appartenir, et déborder si peu que ce soit l'y jette.
        val schematicTop = valueTop(bounds, top, labelHeight, valueHeight)
        val schematicBottom = (schematicTop + valueHeight).coerceAtMost(bounds.bottom - EDGE_INSET)
        val area = RectF(bounds.left + EDGE_INSET, schematicTop, right, schematicBottom)
        if (area.width() <= 0 || area.height() <= 0) return

        val teeth = teethLabel(model)
        val teethSize = valueSize * TEETH_RATIO
        val teethPaint = paint(teethSize, palette.textPrimary, VALUE_TYPEFACE)

        // Les barres montent depuis le bas de cette bande et occupent toute sa largeur : elles
        // passent donc sous le titre et son icône, que rien ne décale plus sur le côté. Il
        // faut leur réserver du blanc en haut, sans quoi la plus haute vient le toucher.
        val combBottom = area.bottom - if (teeth == null) 0f else teethSize * TEETH_LEADING
        val combTop = (area.top + labelHeight * COMB_TOP_MARGIN).coerceAtMost(combBottom)

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

        // Un seul pas pour les deux peignes, donc une seule largeur de barre et un seul
        // écart : les plateaux se lisent comme la suite de la cassette, à sa propre échelle,
        // et non comme un second dessin aux proportions étrangères. Deux plateaux étalés sur
        // le quart gauche donnaient des barres trois fois plus larges que les onze pignons.
        val gap = if (front == null) 0f else area.width() * COMB_GAP_FRACTION
        val bars = (front?.second ?: 0) + (rear?.second ?: 0)
        if (bars <= 0) return
        val pitch = (area.width() - gap) / bars
        val frontWidth = (front?.second ?: 0) * pitch
        val rearLeft = area.left + frontWidth + gap
        val barWidth = (pitch * BAR_WIDTH_FRACTION).coerceAtLeast(2f)

        front?.let {
            drawComb(canvas, RectF(area.left, combTop, area.left + frontWidth, combBottom), it, barWidth, ascending = true, palette = palette)
        }
        rear?.let {
            drawComb(canvas, RectF(rearLeft, combTop, area.right, combBottom), it, barWidth, ascending = false, palette = palette)
        }

        // Les dentures restent contre le bord droit, seules de toute la page à ne pas se
        // centrer. Elles ne sont pas la valeur de la case — celle-ci est le schéma, qui prend
        // toute la largeur — mais une légende posée dessous. Centrée, elle tombait sous le
        // creux du peigne et se lisait comme la mesure de la barre qu'elle avait au-dessus.
        teeth?.let {
            canvas.drawText(it, right - teethPaint.measureText(it), area.bottom - teethPaint.descent(), teethPaint)
        }
    }

    /**
     * Rapport courant et nombre de rapports, quand les deux sont connus et cohérents.
     *
     * Un peigne d'une seule barre n'est pas dessiné : sur un mono-plateau, une barre unique
     * toujours allumée n'apprend rien et laisse croire à un second peigne amputé. Seule la
     * cassette reste, ce qui est exactement ce qu'il y a à savoir.
     */
    private fun comb(current: Int?, count: Int?): Pair<Int, Int>? {
        if (count == null || count <= 1) return null
        val gear = current?.coerceIn(1, count) ?: return null
        return gear to count
    }

    /**
     * Un peigne : une barre par rapport, de gauche à droite dans l'ordre où le groupe les
     * numérote, et dont la hauteur suit la denture.
     *
     * Les deux peignes ne vont pas dans le même sens, parce que les groupes ne numérotent
     * pas dans le même sens. Le plateau n° 1 est le petit, le peigne avant monte donc. Le
     * pignon n° 1 est le grand — celui qu'on prend pour monter —, le peigne arrière descend
     * donc : à gauche le grand pignon, à droite le petit, comme sur la cassette qu'on a sous
     * les yeux en tournant la tête.
     *
     * Ce sens a été retourné une fois par erreur, sur une observation mal comprise. Le vérifier
     * demande de regarder la cassette et le schéma en même temps, pas de raisonner : à gauche,
     * la barre la plus haute et le plus grand nombre de dents.
     */
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
        val stroke = (barWidth * BAR_STROKE_FRACTION).coerceIn(1.5f, 4f)

        // Contour pour tous, remplissage pour le seul rapport engagé : c'est le dessin du
        // système visuel de Hammerhead, et il dit deux choses là où nos barres pleines n'en
        // disaient qu'une — le contour donne la denture disponible, le remplissage l'endroit
        // où l'on se trouve dedans.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = palette.textPrimary
        }
        // Le rapport engagé est rempli de blanc, non du vert vif que le système réserve à la
        // donnée vive. C'est un écart assumé : le contour porte déjà toute la distinction —
        // creux partout, plein à un seul endroit — et la couleur n'y ajoutait qu'un signal de
        // plus, là où l'écran en compte déjà sept avec les aplats de zone. Le blanc est celui
        // des valeurs : le rapport engagé est un chiffre qu'on lit, pas un voyant.
        val engaged = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.textPrimary
        }

        for (index in 1..count) {
            val step = if (count == 1) 1f else (index - 1).toFloat() / (count - 1)
            val ratio = if (ascending) step else 1f - step
            val height = area.height() * (BAR_MIN_HEIGHT + (1f - BAR_MIN_HEIGHT) * ratio)
            val left = area.left + (index - 1) * pitch + (pitch - barWidth) / 2f
            val bar = RectF(left, area.bottom - height, left + barWidth, area.bottom)
            // Bouts arrondis : à cette taille, des angles vifs font des barres sales.
            val radius = barWidth / 2f

            if (index == current) {
                canvas.drawRoundRect(bar, radius, radius, engaged)
            } else {
                // Le contour se dessine sur la ligne médiane du trait : il faut donc rentrer
                // le rectangle d'une demi-épaisseur, sans quoi les barres se touchent et
                // débordent du bas de la bande.
                val demi = stroke / 2f
                canvas.drawRoundRect(
                    RectF(bar.left + demi, bar.top + demi, bar.right - demi, bar.bottom - demi),
                    radius,
                    radius,
                    outline,
                )
            }
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

    /** Écart entre les plateaux et la cassette, en part de la largeur du schéma. */
    private const val COMB_GAP_FRACTION = 0.08f

    /**
     * Largeur d'une barre, en part du pas.
     *
     * Elle a été élargie en passant au contour : une barre pleine se voit par sa surface, une
     * barre creuse par son seul trait, et onze traits fins de deux pixels sur cette largeur
     * faisaient une grille plutôt qu'un peigne.
     */
    private const val BAR_WIDTH_FRACTION = 0.74f

    /** Épaisseur du contour, en part de la largeur de la barre. */
    private const val BAR_STROKE_FRACTION = 0.22f

    /** Hauteur de la plus petite barre, en part de la plus grande. */
    private const val BAR_MIN_HEIGHT = 0.35f

    /**
     * Taille des dentures, en part de celle des chiffres des autres cases.
     *
     * « 50×17 » est un renseignement d'appoint — la position dans la cassette se lit sur les
     * barres, pas sur les nombres. À quatre dixièmes il pesait autant qu'une vraie valeur, et
     * il prend sa place sur la hauteur du peigne : chaque dixième rendu ici est rendu aux
     * barres, qui sont ce qu'on regarde.
     */
    private const val TEETH_RATIO = 0.28f

    /** Blanc entre le bas des barres et la ligne des dentures, en part du corps de celle-ci. */
    private const val TEETH_LEADING = 1.35f

    /**
     * Blanc réservé au-dessus des barres, en part de la hauteur du libellé.
     *
     * Juste de quoi détacher la plus haute barre du plateau dessiné à côté du libellé. Au-delà
     * on ne gagne rien de plus à l'œil et le peigne s'écrase, alors qu'il est le seul dessin
     * de la case.
     */
    private const val COMB_TOP_MARGIN = 0.32f

    /**
     * Plus grande taille de titre tenant dans la case, icône comprise.
     *
     * L'icône et le mot forment un bloc centré : ils ne peuvent plus se recouvrir, mais rien
     * n'empêche le bloc entier de dépasser des deux côtés. Les trois cases du bandeau du haut
     * font la moitié de la largeur des autres, et « VITESSE 3S » y sortait du cadre.
     *
     * L'icône étant dimensionnée d'après le libellé, les deux se réduisent ensemble : la
     * largeur totale est proportionnelle au corps, et une seule mise à l'échelle suffit.
     */
    /**
     * Le corps du libellé, et si l'icône y a encore sa place.
     *
     * Le libellé ne descend pas sous le plancher de lisibilité : dans une case étroite, c'est
     * l'**icône** qui cède, puis, si cela ne suffit toujours pas, le libellé se rétrécit — mais
     * jamais en dessous du plancher, où il ne serait plus lu. Les cases sont devenues deux fois
     * plus étroites en accueillant le cœur et le restant côte à côte, et « RESTANT KM » avec
     * son icône n'y tenait plus qu'en tombant à deux tiers de millimètre.
     */
    private fun fitLabelSize(
        label: String,
        hasIcon: Boolean,
        maxWidth: Float,
        preferredSize: Float,
    ): LabelFit {
        val plancher = Lisibilite.corpsPourCapitale()
        val size = preferredSize.coerceIn(max(MINIMUM_LABEL_SIZE, plancher), MAXIMUM_LABEL_SIZE)
        if (label.isEmpty() || maxWidth <= 0f) return LabelFit(size, hasIcon)
        val texte = paint(size, 0, LABEL_TYPEFACE).measureText(label)
        val icon = if (hasIcon) size * ICON_RATIO + LABEL_GAP else 0f
        if (texte + icon <= maxWidth) return LabelFit(size, hasIcon)
        if (hasIcon && texte <= maxWidth) return LabelFit(size, avecIcone = false)
        val reduit = (size * maxWidth / (texte + icon)).coerceAtLeast(plancher)
        return LabelFit(reduit, avecIcone = false)
    }

    /** Un corps de libellé, et le sort de l'icône qui l'accompagnait. */
    private data class LabelFit(val size: Float, val avecIcone: Boolean)

    /** Bornes du corps des libellés. */
    private const val MINIMUM_LABEL_SIZE = 9f
    private const val MAXIMUM_LABEL_SIZE = 26f

    /** Blanc gardé entre l'icône et le libellé qui la suit. */
    private const val LABEL_GAP = 6f

    /** Plus grande taille de valeur tenant dans la largeur, décimale et unité comprises. */
    private fun fitValueSize(tile: Tile, maxWidth: Float, preferredSize: Float): Float {
        val size = preferredSize.coerceIn(12f, 140f)
        val tail = tile.decimal ?: tile.suffix
        val measured = paint(size, 0, VALUE_TYPEFACE).measureText(tile.value) +
            (tail?.let { paint(size * SUFFIX_RATIO, 0, VALUE_TYPEFACE).measureText(it) } ?: 0f)
        if (measured <= maxWidth || measured <= 0f) return size
        return (size * maxWidth / measured).coerceAtLeast(10f)
    }

    private fun translucent(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private const val LABEL_ALPHA = 0xCC

    private const val ICON_RATIO = 1.15f

    /** Marge entre le bord de la case et ce qu'elle contient. */
    private const val EDGE_INSET = 8f

    /**
     * Ordonnée du libellé : suspendu au haut de la case, à une fraction de sa propre hauteur.
     *
     * Il tombe ainsi sur la même ligne d'un rang à l'autre, quelle que soit la taille des
     * chiffres qui le suivent. Le bloc libellé + valeur était auparavant centré dans la case,
     * de sorte que le bandeau du haut — dont les chiffres sont plus petits que partout
     * ailleurs — voyait tout son bloc descendre, et ses trois libellés flottaient deux
     * millimètres plus bas que ceux des autres cases.
     */
    private fun labelTop(bounds: RectF, labelHeight: Float): Float =
        bounds.top + labelHeight * LABEL_TOP_RATIO

    /**
     * Ordonnée de la valeur : centrée dans ce qui reste de la case sous le libellé.
     *
     * Elle retrouve à peu de chose près la place qu'elle occupait du temps du bloc centré :
     * seul le libellé remonte.
     */
    private fun valueTop(bounds: RectF, top: Float, labelHeight: Float, valueHeight: Float): Float {
        val below = top + labelHeight
        return below + ((bounds.bottom - below - valueHeight) / 2f).coerceAtLeast(0f)
    }

    /**
     * Suspension du libellé sous le bord de la case, en part de sa hauteur.
     *
     * Réglé pour que les rangs qui allaient déjà bien ne bougent pas : un libellé collé au
     * bord paraîtrait tombé de la case voisine.
     */
    private const val LABEL_TOP_RATIO = 0.6f
    /**
     * Les deux graisses du système visuel, prises dans les fontes d'Android.
     *
     * Le Karoo écrit ses libellés en **Medium** et ses données en **Regular** — la planche
     * *Typography* donne « Data Label : Medium 32 » et « Data 5 / 7 / 10 : Regular 75 / 55 /
     * 30 ». Les fontes elles-mêmes, *Hammerhead Relative* et *Ping*, sont sous licence et ne
     * peuvent pas voyager dans l'APK ; leurs graisses, elles, se reprennent telles quelles.
     *
     * Le gras d'Android tenait lieu de Medium et pesait un cran de trop : deux libellés
     * voisins formaient une ligne noire là où le système ne pose qu'une mention discrète. Les
     * valeurs, à l'inverse, étaient en Light — un cheveu trop maigre pour un écran
     * transflectif, qui délave tout ce qui manque de matière dès qu'il fait grand jour.
     */
    private val LABEL_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val VALUE_TYPEFACE: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

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
