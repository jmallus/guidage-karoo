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
import io.github.jmallus.guidage.core.DashboardLayout
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
 * Trois bandes, comptées en rangs : l'effort instantané en tient un, la zone de guidage deux
 * et demi — carte ou graphe à droite, transmission puis cœur et restant à sa gauche — et le
 * profil de ce qui arrive prend ce qui reste, sur toute la largeur du bas.
 *
 * Seuls les deux rangs de chiffres ont une hauteur fixe. Le guidage prend l'entre-deux, si
 * bien que déplacer la frontière du profil fait descendre la carte sans toucher à un chiffre.
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
     * Le bas de l'écran, sur toute la largeur : le profil de ce qui arrive.
     *
     * Il occupe deux rangs, celui du bandeau et celui que tenait la bande « Avant la nuit ».
     * Cette bande portait l'heure d'arrivée, et une sortie réelle a tranché : à cette hauteur
     * l'heure ne se lisait pas. Elle a désormais sa propre case de bilan, où elle voyage avec
     * le coucher et le verdict, en grand — et les cent points qu'elle occupait ici reviennent
     * au seul endroit de l'écran qui en manquait vraiment.
     *
     * Le bandeau exécute le rendu du champ « Profil à venir » lui-même : le redessiner ici en
     * aurait fait une seconde écriture. Il lui passe en revanche une portée, là où le champ
     * montre tout ce qui reste — les deux ne répondent pas à la même question.
     */
    val profileBand: ProfileFieldModel? = null,
    val palette: Palette,
    /** Cases, ou carte d'abord. Le rendu et la coupe de l'appui en dépendent tous deux. */
    val layout: DashboardLayout = DashboardLayout.MAP_FIRST,
)

object DashboardRenderer {

    /** Largeur de la colonne de gauche. */
    private const val TILE_COLUMN_FRACTION = 0.5f

    /**
     * Hauteur d'un rang de chiffres, en part de la hauteur utile.
     *
     * Deux rangs seulement la portent désormais : le bandeau du haut et celui du cœur. Le
     * guidage prend tout ce qui reste entre les deux, et grandit donc quand le profil rend de
     * la hauteur — c'est ce qui permet de déplacer la frontière entre carte et profil sans
     * toucher à un seul chiffre.
     *
     * La valeur est celle qu'avait le rang quand il valait 0,2455 de la hauteur diminuée du
     * bandeau : elle est réexprimée sur la hauteur entière pour que les cases gardent, au
     * pixel près, la taille qu'elles avaient — elles étaient jugées bonnes en roulant.
     */
    private const val ROW_HEIGHT_FRACTION = 0.1895f

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
     * Rangs occupés au-dessus du profil : l'effort, puis le guidage.
     *
     * Toute la mise en page se lit ici, dans l'unité où elle se pense — le rang. L'effort en
     * tient un, le guidage deux et demi, le profil ce qui reste, un peu moins de deux.
     *
     * Le guidage en tenait deux, et le profil autant, depuis que la bande du soir lui avait
     * laissé son rang. Une sortie a tranché : la carte manquait de hauteur et le profil en
     * avait de trop. Elle en gagne **un demi**, et la transmission le gagne avec elle pour que
     * les deux colonnes restent alignées — un rang entier, essayé d'abord, faisait une carte
     * en tour et un bandeau où les étiquettes de côte se marchaient dessus.
     */
    private const val ROWS_ABOVE_BAND = 3.5f

    private fun padding(width: Int, height: Int): Float =
        (min(width, height) * 0.015f).coerceIn(2f, 6f)

    /**
     * Où commence le bandeau de profil, en pixels depuis le haut du champ.
     *
     * Rendue publique parce que l'appui sur le champ en dépend : le tableau de bord est
     * découpé à cette hauteur en deux images, celle du haut changeant la portée de la carte
     * et celle du bas celle du profil. La frontière doit être **la même** que celle du
     * dessin, sinon le doigt agirait sur ce qu'il ne désigne pas — et il n'y a qu'un moyen
     * d'en être sûr, c'est que les deux la lisent au même endroit.
     */
    fun bandTop(width: Int, height: Int, hasBand: Boolean, layout: DashboardLayout = DashboardLayout.TILES): Float {
        if (!hasBand) return height.toFloat()
        val padding = padding(width, height)
        return when (layout) {
            DashboardLayout.TILES -> padding + ROWS_ABOVE_BAND * (height - 2 * padding) * ROW_HEIGHT_FRACTION
            DashboardLayout.MAP_FIRST -> height - padding - height * HUD_BAND_FRACTION
        }
    }

    fun render(
        context: Context,
        width: Int,
        height: Int,
        model: DashboardModel,
        encreMinimaleMm: Float = Lisibilite.ENCRE_MINIMALE_MM,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (model.layout == DashboardLayout.MAP_FIRST) {
            drawMapFirst(context, canvas, width, height, model, encreMinimaleMm)
            return bitmap
        }

        val padding = padding(width, height)
        val columnSplit = width * TILE_COLUMN_FRACTION
        val right = width - padding

        // Le bandeau mange le bas de l'écran ; tout le reste se serre au-dessus. Il est là
        // dès qu'on navigue, de sorte que la mise en page ne bouge plus en cours de route.
        val bandTop = bandTop(width, height, model.profileBand != null)

        // Deux rangs de chiffres à hauteur fixe — l'effort en haut, le cœur juste au-dessus du
        // profil — et le guidage qui prend tout l'entre-deux. Écrire la mise en page dans ce
        // sens-là, plutôt qu'en rangs égaux comptés depuis le haut, est ce qui permet de
        // déplacer la frontière carte/profil sans qu'aucun chiffre ne change de corps.
        val rowHeight = (height - 2 * padding) * ROW_HEIGHT_FRACTION
        val topBottom = padding + rowHeight
        val heartTop = bandTop - rowHeight

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
                    padding,
                    padding + (index + 1) * topWidth,
                    topBottom,
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
                        heartTop,
                        padding + (index + 1) * cellWidth,
                        bandTop,
                    )
                },
                tiles = colonneTiles,
                palette = model.palette,
                valueFraction = VALUE_HEIGHT_FRACTION,
                labelFraction = LABEL_HEIGHT_FRACTION,
            )
        }

        // Colonne droite : le guidage, de sous le bandeau du haut jusqu'au profil. C'est lui
        // qui absorbe la hauteur rendue par le profil — la carte descend.
        val guidanceArea = RectF(columnSplit, topBottom, right, bandTop)
        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, guidanceArea, guidance.model, model.palette)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, guidanceArea, guidance.model, model.palette)
        }

        // Colonne gauche, à hauteur du haut de la carte : la transmission.
        model.drivetrain?.let { drivetrain ->
            drawDrivetrain(
                context = context,
                canvas = canvas,
                bounds = RectF(padding, topBottom, columnSplit, heartTop),
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

        // Tout le bas : le profil, sur toute la largeur et sur deux rangs — le sien et celui
        // qu'occupait la bande du soir. Il commence donc où finit la grille des chiffres, et
        // non au pied de l'écran.
        model.profileBand?.let { band ->
            ProfileRenderer.draw(
                canvas = canvas,
                area = RectF(padding, bandTop, width - padding, height - padding),
                model = band,
                palette = model.palette,
                encreMinimaleMm = encreMinimaleMm,
            )
        }
        return bitmap
    }

    // --- Carte d'abord ---------------------------------------------------------------------

    /**
     * La carte sur tout le champ, et le reste posé dessus.
     *
     * Choisie sur planches contre deux autres. Ce qu'elle achète : une carte deux fois plus
     * large et une fois et demie plus haute, qui montre loin devant et sur les côtés. Ce
     * qu'elle coûte : les chiffres se posent sur des voiles à demi transparents, et sur un
     * fond de carte chargé leur contraste n'est plus garanti par le fond. Il l'est par un
     * **cerne** : chaque texte est tracé d'abord en sombre et épais, puis en clair par-dessus,
     * si bien qu'un blanc de carte ne peut pas manger un blanc de chiffre.
     *
     * La colonne de gauche empile la vitesse, la puissance, le cœur et la cadence — l'ordre
     * des aplats d'avant, moins la cadence remontée d'un rang parce qu'elle n'a pas de zone —
     * et finit par la transmission. L'aplat de zone devient une barre au bord gauche : la
     * couleur est là, elle n'est plus le fond. Le restant va sous la boussole, seul en haut à
     * droite, et le profil prend le pied du champ sur toute la largeur.
     *
     * Le coureur, la boussole et l'échelle se calent sur la partie de carte à découvert, et
     * non sur le champ entier : voir `MapRenderer.draw` et son paramètre `focus`.
     */
    private fun drawMapFirst(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int,
        model: DashboardModel,
        encreMinimaleMm: Float,
    ) {
        val padding = padding(width, height)
        val bandTop = bandTop(width, height, model.profileBand != null, model.layout)
        val column = width * HUD_COLUMN_FRACTION
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val focus = RectF(column, 0f, width.toFloat(), bandTop)

        // Les encres sont fixes, quel que soit le thème : la carte a ses propres couleurs, et
        // les voiles sont sombres dans les deux cas. Un thème clair mettrait du noir sur eux.
        val hud = model.palette.copy(
            textPrimary = HUD_INK,
            textSecondary = HUD_SOFT_INK,
            iconTint = HUD_SOFT_INK,
        )

        when (val guidance = model.guidance) {
            is GuidanceZone.Map -> MapRenderer.draw(canvas, full, guidance.model, model.palette, focus)
            is GuidanceZone.Profile -> drawRouteGraph(canvas, focus, guidance.model, model.palette)
        }

        val veil = Paint().apply { color = HUD_VEIL }
        val columnBottom = bandTop - padding
        canvas.drawRect(0f, 0f, column, columnBottom, veil)

        // Vitesse, puissance, cœur, cadence : l'ordre de lecture, la cadence en dernier parce
        // qu'elle est la seule sans zone et donc sans couleur à porter.
        val blocks = buildList {
            model.topTiles.getOrNull(0)?.let { add(it) }
            model.topTiles.getOrNull(2)?.let { add(it) }
            model.heartRateTile?.let { add(it) }
            model.topTiles.getOrNull(1)?.let { add(it) }
        }
        val drivetrainHeight = if (model.drivetrain == null) 0f else columnBottom * HUD_DRIVETRAIN_FRACTION
        val blockHeight = (columnBottom - padding - drivetrainHeight) / blocks.size.coerceAtLeast(1)
        val labelSize = max(blockHeight * HUD_LABEL_FRACTION, Lisibilite.corpsPourCapitale(encreMinimaleMm))
        val textWidth = column - HUD_BAR_WIDTH - EDGE_INSET * 2
        val valueSize = blocks.minOfOrNull { fitValueSize(it, textWidth, blockHeight * HUD_VALUE_FRACTION) }
            ?: blockHeight * HUD_VALUE_FRACTION

        blocks.forEachIndexed { index, tile ->
            val top = padding + index * blockHeight
            drawHudBlock(canvas, RectF(0f, top, column, top + blockHeight), tile, hud, valueSize, labelSize)
        }
        model.drivetrain?.let { drivetrain ->
            drawDrivetrain(
                context = context,
                canvas = canvas,
                bounds = RectF(0f, padding + blocks.size * blockHeight, column, columnBottom),
                model = drivetrain,
                palette = hud,
                valueSize = valueSize,
                labelSize = labelSize,
            )
        }

        // Le restant, sous la boussole : seul en haut à droite, dans son propre voile.
        model.remainingTile?.let { remaining ->
            val restWidth = width * HUD_REST_FRACTION
            val restTop = MapRenderer.compassBottom(focus) + padding
            val rest = RectF(width - restWidth - padding, restTop, width - padding, restTop + blockHeight)
            canvas.drawRoundRect(rest, HUD_CORNER, HUD_CORNER, veil)
            drawHudBlock(canvas, rest, remaining, hud, valueSize, labelSize, bar = false)
        }

        // Le profil au pied, sur toute la largeur, par le rendu du champ « Profil à venir ».
        model.profileBand?.let { band ->
            canvas.drawRect(0f, bandTop, width.toFloat(), height.toFloat(), veil)
            ProfileRenderer.draw(
                canvas = canvas,
                area = RectF(padding, bandTop, width - padding, height - padding),
                model = band,
                palette = hud,
                encreMinimaleMm = encreMinimaleMm,
            )
        }
    }

    /**
     * Un bloc de la colonne : la barre de zone au bord, le libellé, le chiffre — tous cernés.
     *
     * Calé à gauche et non centré : la colonne est étroite, et des chiffres de longueurs
     * différentes centrés y flotteraient. Alignés sur la barre, ils font une colonne.
     */
    private fun drawHudBlock(
        canvas: Canvas,
        bounds: RectF,
        tile: Tile,
        palette: Palette,
        valueSize: Float,
        labelSize: Float,
        bar: Boolean = true,
    ) {
        var left = bounds.left + EDGE_INSET
        if (bar) {
            tile.background?.let { color ->
                canvas.drawRect(
                    bounds.left,
                    bounds.top,
                    bounds.left + HUD_BAR_WIDTH,
                    bounds.bottom - HUD_BAR_GAP,
                    Paint().apply { this.color = color },
                )
            }
            left += HUD_BAR_WIDTH
        }
        val labelPaint = paint(labelSize, palette.textSecondary, LABEL_TYPEFACE)
        val valuePaint = paint(valueSize, palette.textPrimary, VALUE_TYPEFACE)
        val suffixPaint = paint(valueSize * SUFFIX_RATIO, palette.textPrimary, VALUE_TYPEFACE)

        drawHaloed(canvas, tile.label, left, bounds.top + HUD_LABEL_INSET - labelPaint.ascent(), labelPaint)

        val baseline = bounds.bottom - HUD_VALUE_INSET - valuePaint.descent()
        drawHaloed(canvas, tile.value, left, baseline, valuePaint)
        val tail = tile.decimal ?: tile.suffix
        if (tail != null) {
            val rise = if (tile.decimal == null) 0f else decimalRise(tile.value, tail, valuePaint, suffixPaint)
            drawHaloed(canvas, tail, left + valuePaint.measureText(tile.value), baseline - rise, suffixPaint)
        }
    }

    /**
     * Un texte cerné : tracé d'abord en sombre et épais, puis en sa couleur par-dessus.
     *
     * C'est ce qui rend les voiles possibles à demi-transparence. Sans cerne, le contraste
     * d'un chiffre dépendrait de ce que la carte met dessous — un champ, un bois, un blanc de
     * route — et se jugerait donc au hasard du paysage.
     */
    private fun drawHaloed(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val halo = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = (paint.textSize * HUD_HALO_RATIO).coerceAtLeast(2f)
            strokeJoin = Paint.Join.ROUND
            color = HUD_HALO
        }
        canvas.drawText(text, x, y, halo)
        canvas.drawText(text, x, y, paint)
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
        val labelHeight = labelPaint.descent() - labelPaint.ascent()
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

        // Le schéma prend toute la hauteur sous le titre, et non la seule bande d'un chiffre.
        //
        // Il occupait cette bande-là du temps où la case en faisait la hauteur. La case a
        // doublé quand la carte est descendue, et le peigne s'y est retrouvé à flotter au
        // milieu d'un vide, de la taille qu'il avait dans une case deux fois plus courte. Un
        // schéma n'a pas de corps de texte à respecter : il prend la place qu'on lui donne.
        //
        // Il s'arrête franchement au-dessus du bord : la case du dessous porte un aplat de
        // couleur qui commence net, et ce qui se pose à un cheveu de lui paraît lui appartenir.
        val schematicTop = top + labelHeight
        val schematicBottom = bounds.bottom - EDGE_INSET
        val area = RectF(bounds.left + EDGE_INSET, schematicTop, right, schematicBottom)
        if (area.width() <= 0 || area.height() <= 0) return

        val teethSize = valueSize * TEETH_RATIO
        val teethPaint = paint(teethSize, palette.textPrimary, VALUE_TYPEFACE)
        val teeth = teethLabel(model)

        // Les barres montent depuis le bas de cette bande et occupent toute sa largeur : elles
        // passent donc sous le titre et son icône, que rien ne décale plus sur le côté. Il
        // faut leur réserver du blanc en haut, sans quoi la plus haute vient le toucher.
        //
        // Elles rendent le pied de la bande aux dentures. Celles-ci avaient été retirées quand
        // la case ne faisait qu'un rang : elles y coûtaient un bon huitième de la hauteur, et
        // le peigne, seul dessin de la case, s'en trouvait écrasé. La case en fait un et demi
        // depuis que la carte est descendue — la place est là, et le renseignement revient
        // sans que le schéma y perde ce qui le rendait lisible.
        val combBottom = area.bottom - if (teeth == null) 0f else teethSize * TEETH_LEADING
        val combTop = (area.top + labelHeight * COMB_TOP_MARGIN).coerceAtMost(combBottom)

        val front = comb(model.front, model.frontCount)
        val rear = comb(model.rear, model.rearCount)
        if (front == null && rear == null) {
            // Le « -- » a son propre corps : il ne remplace pas les dentures mais le peigne
            // entier, et grossir avec elles en aurait fait un tiret de la taille d'un chiffre
            // de case dans une case par ailleurs vide.
            val videPaint = paint(valueSize * PLACEHOLDER_RATIO, palette.textPrimary, VALUE_TYPEFACE)
            canvas.drawText(
                PLACEHOLDER,
                right - videPaint.measureText(PLACEHOLDER),
                area.top + area.height() / 2f - (videPaint.descent() + videPaint.ascent()) / 2f,
                videPaint,
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

        // Les dentures au pied du peigne, calées à droite comme un chiffre de case.
        teeth?.let {
            canvas.drawText(it, right - teethPaint.measureText(it), area.bottom - teethPaint.descent(), teethPaint)
        }
    }

    /**
     * « 50×17 » quand les deux dentures sont connues, l'une des deux sinon, rien du tout à
     * défaut.
     *
     * C'est un renseignement d'appoint : la position dans la cassette se lit sur le peigne, et
     * l'on ne change pas de braquet parce qu'on a lu 21. Mais savoir sur quel plateau l'on est
     * a son usage au pied d'une bosse, et le nombre le dit sans qu'il faille compter les barres.
     */
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

        // Rapports libres : creux, contour blanc sur le fond de l'écran. Rapport engagé :
        // plein. C'est le dessin retenu après essai sur le vélo — les barres grises pleines
        // l'avaient emporté sur planche, mais en roulant le peigne devenait un bloc où le
        // rapport tenu ne ressortait plus assez. Le vide entre les contours donne au plein
        // tout son contraste.
        val stroke = (barWidth * BAR_STROKE_FRACTION).coerceIn(1f, 3f)
        val libre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = palette.textPrimary
        }
        // Le rapport engagé est blanc, non du vert vif que le système réserve à la donnée
        // vive. C'est un écart assumé : creux partout, plein à un seul endroit porte déjà
        // toute la distinction, et la couleur n'y ajoutait qu'un signal de plus, là où
        // l'écran en compte déjà sept avec les aplats de zone. Le blanc est celui des
        // valeurs : le rapport engagé est un chiffre qu'on lit, pas un voyant.
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
                // Le contour se trace sur la ligne médiane : rentrer d'une demi-épaisseur,
                // sans quoi les barres se touchent et débordent du bas de la bande.
                val demi = stroke / 2f
                canvas.drawRoundRect(
                    RectF(bar.left + demi, bar.top + demi, bar.right - demi, bar.bottom - demi),
                    radius,
                    radius,
                    libre,
                )
            }
        }
    }

    private const val PLACEHOLDER = "--"

    /** Écart entre les plateaux et la cassette, en part de la largeur du schéma. */
    private const val COMB_GAP_FRACTION = 0.08f

    /**
     * Largeur d'une barre, en part du pas.
     *
     * Amincie en même temps que les barres ont grandi : à trois quarts du pas, les barres se
     * touchaient presque et le peigne faisait un bloc où le rapport engagé se perdait. C'est
     * le vide entre elles qui les rend comptables d'un coup d'œil — savoir qu'il reste deux
     * pignons demande de les compter, et on ne compte pas des barres jointives.
     */
    private const val BAR_WIDTH_FRACTION = 0.52f

    /** Épaisseur du contour d'une barre libre, en part de sa largeur. */
    private const val BAR_STROKE_FRACTION = 0.26f

    /** Hauteur de la plus petite barre, en part de la plus grande. */
    private const val BAR_MIN_HEIGHT = 0.35f

    /**
     * Taille des dentures, « 50×17 », en part du chiffre d'une case.
     *
     * Elles ont été retirées un temps, quand la case ne faisait qu'un rang : elles y coûtaient
     * un bon huitième de la hauteur, et le peigne, seul dessin de la case, s'en trouvait
     * écrasé. La case en fait un et demi depuis que la carte est descendue, et la place est
     * revenue avec — assez pour les écrire au double du corps qu'elles avaient alors, et
     * qu'elles se lisent d'un coup d'œil et non en cherchant.
     *
     * Ce qu'elles prennent, les barres le rendent : le peigne s'arrête à leur hauteur, si
     * bien que ce réglage-ci suffit à arbitrer entre les deux.
     */
    private const val TEETH_RATIO = 0.56f

    /** Taille du « -- » qui remplace le peigne entier quand le groupe ne rapporte rien. */
    private const val PLACEHOLDER_RATIO = 0.28f

    /** Hauteur réservée aux dentures sous le peigne, en part de leur corps. */
    private const val TEETH_LEADING = 1.35f

    /**
     * Carte d'abord : le voile, choisi sur planches à cinquante pour cent.
     *
     * Plus clair, on voit la carte sous les chiffres ; plus sombre, on ne voit plus qu'une
     * colonne. Cinquante est ce qui a été retenu, et c'est le cerne qui rend ce chiffre-là
     * tenable.
     */
    private const val HUD_VEIL = 0x80202224.toInt()
    private const val HUD_HALO = 0xFF202224.toInt()
    private const val HUD_INK = 0xFFFFFFFF.toInt()
    private const val HUD_SOFT_INK = KarooColors.POWDER_BLUE

    /** Part de la largeur donnée à la colonne de gauche. */
    private const val HUD_COLUMN_FRACTION = 0.335f

    /** Part de la hauteur donnée au profil, choisie sur planches. */
    private const val HUD_BAND_FRACTION = 0.25f

    /**
     * Part de la colonne donnée à la transmission ; les quatre blocs se partagent le reste.
     *
     * Un cinquième ne suffisait pas : les dentures, écrites au double du corps depuis qu'on
     * les a rendues, réservent quarante points sous le peigne, et il ne lui en restait treize
     * — une rangée de points. Un bon quart lui rend une hauteur où l'on distingue un pignon
     * du suivant, et coûte quatre points de corps aux quatre chiffres du dessus.
     */
    private const val HUD_DRIVETRAIN_FRACTION = 0.26f

    /** Corps du libellé et du chiffre, en part de la hauteur d'un bloc. */
    private const val HUD_LABEL_FRACTION = 0.19f
    private const val HUD_VALUE_FRACTION = 0.60f

    /** La barre de zone au bord gauche, et le blanc qui la sépare de la suivante. */
    private const val HUD_BAR_WIDTH = 8f
    private const val HUD_BAR_GAP = 4f

    /** Blancs au-dessus du libellé et sous le chiffre. */
    private const val HUD_LABEL_INSET = 4f
    private const val HUD_VALUE_INSET = 6f

    /** Largeur de la case du restant, en part de celle du champ, et son arrondi. */
    private const val HUD_REST_FRACTION = 0.36f
    private const val HUD_CORNER = 6f

    /** Épaisseur du cerne, en part du corps. */
    private const val HUD_HALO_RATIO = 0.09f

    /**
     * Blanc réservé au-dessus des barres, en part de la hauteur du libellé.
     *
     * Juste de quoi détacher la plus haute barre du plateau dessiné à côté du libellé. Au-delà
     * on ne gagne rien de plus à l'œil et le peigne s'écrase, alors qu'il est le seul dessin
     * de la case.
     */
    private const val COMB_TOP_MARGIN = 0.32f

    /**
     * Le corps du libellé tenant dans la case, et si l'icône y a encore sa place.
     *
     * L'icône et le mot forment un bloc centré : ils ne peuvent pas se recouvrir, mais rien
     * n'empêche le bloc entier de dépasser des deux côtés — « VITESSE 3S » sortait du cadre
     * des cases du haut, qui font la moitié de la largeur des autres.
     *
     * L'ordre des sacrifices a été retourné. On faisait céder l'**icône** d'abord, pour garder
     * au libellé sa taille de confort ; mais le symbole ne se lit pas, il se reconnaît, et
     * deux points de corps en moins ne coûtent rien à côté de lui. Le libellé se réduit donc
     * avec son icône jusqu'au plancher de lisibilité, et l'icône ne cède que si, au plancher,
     * elle ne tient toujours pas.
     *
     * Le sort en est commun à tout le rang : c'est le libellé le plus large qui décide pour
     * les autres. « RESTANT KM » le décidait, et privait la fréquence cardiaque de son cœur.
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

        // Texte et icône grandissent ensemble avec le corps : une seule règle de trois donne
        // le corps qui les fait tenir tous les deux.
        val avecIcone = size * maxWidth / (texte + icon)
        if (avecIcone >= plancher) return LabelFit(avecIcone, hasIcon)

        if (hasIcon && texte <= maxWidth) return LabelFit(size, avecIcone = false)
        return LabelFit((size * maxWidth / texte).coerceAtLeast(plancher), avecIcone = false)
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
