package io.github.jmallus.guidage.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.PlanePoint
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.fromMicroDegrees
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/** Un point d'intérêt à poser sur la carte. */
data class MapPoi(val position: GeoPoint, val label: String)

/** Ce qu'affiche la minicarte. */
data class MapModel(
    /** Tracé de l'itinéraire. */
    val path: List<GeoPoint> = emptyList(),
    /** Position du coureur ; sans elle, rien ne peut être orienté. */
    val position: GeoPoint? = null,
    /** Cap en degrés (0 = nord). Null quand il est inconnu : la carte reste alors nord en haut. */
    val heading: Double? = null,
    val pois: List<MapPoi> = emptyList(),
    /** Voies du fond de carte, quand un fond est installé. */
    val roads: List<RoadSegment> = emptyList(),
    /** Pourquoi le fond ne montre rien, à porter discrètement sur la carte vide. */
    val roadsMessage: String? = null,
    /** Distance visible devant le coureur (m). */
    val rangeMeters: Double = 1_000.0,
    /** Longueur de tracé, devant le coureur, sur laquelle les chevrons sont semés (m). */
    val chevronRangeMeters: Double = 300.0,
    /** Vrai quand le Karoo estime qu'on a quitté l'itinéraire. */
    val offRoute: Boolean = false,
    val emptyMessage: String? = null,
)

/**
 * Minicarte orientée « cap en haut », à la manière d'un GPS de voiture : le coureur est
 * fixe dans le bas de la vue, la carte tourne autour de lui, et ce qui est devant est en haut.
 *
 * Le fond est clair et les voies portent chacune la teinte de sa classe, comme sur une carte
 * routière ; elles s'effacent vers le fond à mesure qu'elles s'écartent de l'itinéraire, de
 * sorte que la carte ne montre qu'un couloir autour de ce qu'on va faire. Le tracé y est un
 * ruban bleu, d'une seule encre sur toute sa longueur, portant deux sortes de chevrons.
 *
 * Un **blanc et plein**, unique, posé sur le coureur : c'est le repère de position, le ruban
 * ne changeant d'aspect nulle part ailleurs. Des **noirs et creux**, semés devant lui sur les
 * prochaines centaines de mètres : ils disent le sens à prendre, ce que le ruban seul ne dit
 * pas — à un carrefour en T, ses deux branches se ressemblent. Deux rôles, deux encres : on
 * ne cherche pas sa position parmi des jalons qui lui ressembleraient.
 *
 * Tout est calculé sur l'appareil, sans réseau ni tuiles à télécharger.
 */
object MapRenderer {

    /** Part de la hauteur laissée devant le coureur. */
    private const val AHEAD_FRACTION = 0.80f

    fun draw(canvas: Canvas, area: RectF, model: MapModel, palette: Palette) {
        val origin = model.position
        if (origin == null || area.width() <= 0 || area.height() <= 0) {
            drawMessage(canvas, area, model.emptyMessage, palette)
            return
        }

        val riderX = area.centerX()
        val riderY = area.top + area.height() * AHEAD_FRACTION
        val metersToPixels =
            ((area.height() * AHEAD_FRACTION) / model.rangeMeters.coerceAtLeast(1.0)).toFloat()
        val heading = model.heading ?: 0.0
        val projection = Projection(origin, heading, riderX, riderY, metersToPixels)

        canvas.save()
        canvas.clipRect(area)

        canvas.drawRect(area, Paint().apply { color = RoadStyle.BACKGROUND })
        val screenPath = model.path.map { projection.toScreen(it) }
        if (model.roads.isEmpty()) {
            // Un fond vide se confond avec un fond qui n'existe pas : le dire coûte une
            // ligne et évite de chercher une panne là où il n'y en a pas.
            drawBasemapNotice(canvas, area, model.roadsMessage)
        } else {
            val (areas, lines) = model.roads.partition { it.kind.isArea }
            // Le fond entier est dessiné dans un calque, dont l'opacité est ensuite
            // multipliée par celle du couloir. C'est le seul moyen d'obtenir un fondu qui
            // suive l'itinéraire sans avoir à calculer, pour chacun des milliers de sommets
            // du fond, sa distance à la trace — ce qui serait à refaire à chaque image.
            val ground = canvas.saveLayer(area, null)
            drawAreas(canvas, areas, projection)
            drawRoads(canvas, lines, projection, metersToPixels)
            if (screenPath.size >= 2) {
                val corridor = canvas.saveLayer(area, CORRIDOR_PAINT)
                drawCorridor(canvas, screenPath, area.width())
                canvas.restoreToCount(corridor)
            }
            canvas.restoreToCount(ground)
        }
        drawPath(
            canvas = canvas,
            model = model,
            screenPath = screenPath,
            area = area,
            width = routeWidth(model.rangeMeters),
            riderX = riderX,
            riderY = riderY,
            chevronLimit = if (model.offRoute) {
                Float.POSITIVE_INFINITY
            } else {
                (model.chevronRangeMeters * metersToPixels).toFloat()
            },
        )
        drawPois(canvas, area, model, projection, palette)
        drawScaleBar(canvas, area, model.rangeMeters, metersToPixels, palette)

        canvas.restore()
    }

    /**
     * Mention portée en haut de la carte quand le fond ne donne rien.
     *
     * Discrète et sur une seule ligne : elle explique une absence, elle ne remplace pas la
     * carte. Le tracé continue de se dessiner par-dessus, et c'est lui qu'on regarde.
     */
    private fun drawBasemapNotice(canvas: Canvas, area: RectF, message: String?) {
        if (message.isNullOrBlank()) return
        val size = (area.height() * 0.055f).coerceIn(10f, 16f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            color = RoadStyle.INK
            alpha = NOTICE_ALPHA
        }
        canvas.drawText(message, area.left + 6f, area.top + size + 4f, paint)
    }

    /** La mention du fond absent s'efface derrière le tracé : c'est une note, pas une alerte. */
    private const val NOTICE_ALPHA = 0x9E

    /** Position géographique → pixels, cap en haut et coureur fixe. */
    private class Projection(
        private val origin: GeoPoint,
        private val heading: Double,
        private val riderX: Float,
        private val riderY: Float,
        private val metersToPixels: Float,
    ) {
        fun toScreen(point: GeoPoint): PlanePoint {
            val plane = Geo.toTrackUpPlane(origin, heading, point)
            return PlanePoint(
                x = riderX + plane.x * metersToPixels,
                y = riderY - plane.y * metersToPixels,
            )
        }

        /** Abscisse écran, sans allouer de point intermédiaire. */
        fun screenX(latitude: Double, longitude: Double): Float {
            val plane = Geo.toTrackUpPlane(origin, heading, GeoPoint(latitude, longitude))
            return (riderX + plane.x * metersToPixels).toFloat()
        }

        fun screenY(latitude: Double, longitude: Double): Float {
            val plane = Geo.toTrackUpPlane(origin, heading, GeoPoint(latitude, longitude))
            return (riderY - plane.y * metersToPixels).toFloat()
        }
    }

    /**
     * Surfaces du fond de carte : eau, bois, bâti, sous tout le reste.
     *
     * Elles sont dessinées de la plus étendue à la plus rare — campagne, bâti, bois, eau —
     * pour que l'eau reste visible là où un étang borde un bois, cas fréquent et qui se lit
     * mal dans l'autre sens. Chaque famille forme un seul chemin : le remplissage est ce qui
     * coûte le plus cher à dessiner, autant ne changer de pinceau que quatre fois.
     */
    private fun drawAreas(canvas: Canvas, areas: List<RoadSegment>, projection: Projection) {
        if (areas.isEmpty()) return
        AREA_ORDER.forEach { kind ->
            val ofKind = areas.filter { it.kind == kind }
            if (ofKind.isEmpty()) return@forEach
            val path = Path().apply { fillType = Path.FillType.WINDING }
            ofKind.forEach { area ->
                for (index in 0 until area.size) {
                    val latitude = area.latitudes[index].fromMicroDegrees()
                    val longitude = area.longitudes[index].fromMicroDegrees()
                    val x = projection.screenX(latitude, longitude)
                    val y = projection.screenY(latitude, longitude)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = RoadStyle.color(kind, RoadSurface.UNKNOWN)
                },
            )
        }
    }

    /** Du plus lointain au plus proche du regard : la campagne, le bâti, le bois, l'eau. */
    private val AREA_ORDER =
        listOf(RoadKind.FARMLAND, RoadKind.BUILT_UP, RoadKind.FOREST, RoadKind.WATER)

    /**
     * Voies du fond de carte, sous le tracé.
     *
     * Elles sont dessinées de la plus fine à la plus large, de sorte qu'une nationale
     * passe par-dessus le chemin qui la longe et non l'inverse. Chaque famille est tracée
     * d'un seul tenant : changer de pinceau coûte plus cher que de dessiner.
     */
    private fun drawRoads(
        canvas: Canvas,
        roads: List<RoadSegment>,
        projection: Projection,
        metersToPixels: Float,
    ) {
        roads
            .groupBy { Triple(it.kind, it.surface, RoadStyle.isDashed(it.kind, it.surface)) }
            .entries
            .sortedBy { RoadStyle.widthMeters(it.key.first) }
            .forEach { (style, segments) ->
                val (kind, surface, dashed) = style
                val width = (RoadStyle.widthMeters(kind) * metersToPixels)
                    .coerceIn(MIN_ROAD_WIDTH, MAX_ROAD_WIDTH)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = RoadStyle.color(kind, surface)
                    if (dashed) {
                        val dash = (width * 2.5f).coerceAtLeast(4f)
                        pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.8f), 0f)
                    }
                }

                val path = Path()
                segments.forEach { segment ->
                    for (index in 0 until segment.size) {
                        val latitude = segment.latitudes[index].fromMicroDegrees()
                        val longitude = segment.longitudes[index].fromMicroDegrees()
                        val x = projection.screenX(latitude, longitude)
                        val y = projection.screenY(latitude, longitude)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                canvas.drawPath(path, paint)
            }
    }

    private const val MIN_ROAD_WIDTH = 1f
    private const val MAX_ROAD_WIDTH = 26f

    /**
     * Le couloir : le fondu du fond de carte à mesure qu'on s'écarte de l'itinéraire.
     *
     * Il est dessiné comme une suite d'anneaux — la trace repassée de plus en plus large et
     * de moins en moins encrée — plutôt que comme un trait unique qu'on flouterait. Un flou
     * gaussien assez large pour couvrir la carte donne un plateau d'opacité pleine sur toute
     * la surface visible, et sa décroissance tombe hors du cadre : on obtient un fond
     * uniforme, c'est-à-dire exactement le contraire d'un dégradé. Les anneaux, eux, donnent
     * la décroissance point par point.
     *
     * Les largeurs sont en part de la largeur de la carte, non en pixels : le fondu occupe
     * ainsi la même place quelle que soit la taille de la case.
     *
     * Les opacités posées ne sont pas celles qu'on veut obtenir : chaque anneau se pose sur
     * les précédents, du plus large au plus étroit, d'où a = (cible − acquis) / (1 − acquis).
     *
     * Seize paliers, et non six : à six, chaque marche vaut une dizaine de points d'opacité
     * et se voit sur une chaussée large, qui traverse plusieurs anneaux d'un coup — la route
     * paraît alors carrelée. À seize, la marche passe sous le seuil de perception.
     */
    private const val CORRIDOR_STEPS = 16
    private const val CORRIDOR_WIDEST = 1.05f
    private const val CORRIDOR_NARROWEST = 0.06f

    /** Opacité au bord du couloir : le fondu doux, retenu sur planche. */
    private const val CORRIDOR_EDGE = 0.36f

    /**
     * Exposant de la courbe d'opacité.
     *
     * En deçà de 1, l'encre remonte vite en quittant le bord puis s'aplatit : le lointain
     * garde sa lisibilité et c'est le voisinage immédiat du tracé qui gagne le contraste.
     */
    private const val CORRIDOR_CURVE = 0.7f

    private val CORRIDOR_RINGS: List<Pair<Float, Float>> = buildList {
        var reached = 0f
        for (index in 0 until CORRIDOR_STEPS) {
            val t = index.toFloat() / (CORRIDOR_STEPS - 1)
            val widthRatio = CORRIDOR_WIDEST - (CORRIDOR_WIDEST - CORRIDOR_NARROWEST) * t
            val target = CORRIDOR_EDGE + (1f - CORRIDOR_EDGE) * t.pow(CORRIDOR_CURVE)
            add(widthRatio to (target - reached) / (1f - reached))
            reached = target
        }
    }

    /** Le calque du couloir ne garde du fond que ce qu'il recouvre. */
    private val CORRIDOR_PAINT =
        Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private fun drawCorridor(canvas: Canvas, points: List<PlanePoint>, width: Float) {
        val path = polyline(points)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFF000000.toInt()
        }
        CORRIDOR_RINGS.forEach { (widthRatio, opacity) ->
            paint.strokeWidth = width * widthRatio
            paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Le tracé : un ruban bleu d'une seule encre, du départ à l'arrivée.
     *
     * La part déjà faite s'éteignait autrefois derrière le coureur. Le fondu disait à la fois
     * où l'on était et dans quel sens on allait, mais il coupait le ruban en deux à l'écran :
     * sur une épingle ou une boucle, la branche pâle du retour longe la branche pleine de
     * l'aller et l'on ne sait plus laquelle est l'itinéraire. Le ruban est donc plein partout,
     * et ce sont les chevrons qui portent le sens et l'endroit où l'on en est.
     *
     * Hors itinéraire, tout passe au rouge de rejointe du Karoo — mieux vaut le voir tout de
     * suite que de le découvrir au bout de deux kilomètres.
     */
    private fun drawPath(
        canvas: Canvas,
        model: MapModel,
        screenPath: List<PlanePoint>,
        area: RectF,
        width: Float,
        riderX: Float,
        riderY: Float,
        chevronLimit: Float,
    ) {
        if (screenPath.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (model.offRoute) OFF_ROUTE_COLOR else ROUTE_COLOR
        }

        val route = polyline(screenPath)
        canvas.drawPath(route, paint)

        // Marque et jalons partent tous du point du tracé le plus proche du coureur, et se
        // comptent le long de ce qui reste à faire.
        val here = anchor(screenPath, riderX, riderY)
        val ahead = buildList {
            add(here.point)
            addAll(screenPath.subList(here.index, screenPath.size))
        }

        // Le ruban sert de gabarit aux chevrons. Leurs branches sont taillées à sa
        // demi-largeur, si bien qu'elles s'arrêtent d'elles-mêmes au bord ; le gabarit ne fait
        // que rogner ce que la courbure fait dépasser. Le ruban tourne, sa largeur
        // perpendiculaire au chevron n'est pas tout à fait la sienne, et le coin du trait
        // passait alors outre — mordant sur le fond et sur les voies que le tracé croise.
        val ribbon = Path()
        paint.getFillPath(route, ribbon)
        val clip = canvas.save()
        canvas.clipPath(ribbon)
        drawChevrons(canvas, area, ahead, width, chevronLimit)
        canvas.restoreToCount(clip)

        // La flèche, elle, n'est pas rognée par le ruban : elle est plus large que lui et se
        // pose par-dessus, comme sur la carte native du Karoo.
        drawRider(canvas, riderX, riderY, area.height())
    }

    /**
     * Doubles chevrons noirs semés le long de ce qui reste à faire.
     *
     * Le ruban dit où passe l'itinéraire, non dans quel sens le prendre : à un carrefour en T,
     * ses deux branches se ressemblent. Les chevrons le disent. Doubles parce qu'une pointe
     * seule, à cette taille, se lit comme un accident du tracé ; deux qui se suivent ne peuvent
     * être qu'un sens.
     *
     * Noirs et creux, quand la marque de position est blanche et pleine : ce sont deux choses
     * différentes, et il ne faut pas avoir à chercher la sienne parmi des jalons qui lui
     * ressembleraient. Le premier est posé un intervalle plus loin que la marque, de sorte
     * qu'aucun ne vienne se superposer à elle.
     */
    private fun drawChevrons(
        canvas: Canvas,
        area: RectF,
        points: List<PlanePoint>,
        routeWidth: Float,
        limitPixels: Float,
    ) {
        if (limitPixels <= 0f || points.size < 2) return
        val spacing = routeWidth * CHEVRON_SPACING_RATIO
        val gap = routeWidth * CHEVRON_GAP_RATIO
        val size = routeWidth * CHEVRON_SIZE_RATIO
        val stroke = routeWidth * CHEVRON_STROKE_RATIO
        val margin = size * CHEVRON_SWEEP + stroke

        // Le tracé est parcouru une fois, en abscisse curviligne, et pas plus loin que la
        // borne : un itinéraire de cent kilomètres ne coûte donc pas plus qu'un de un.
        val walk = ArrayList<PlanePoint>(64)
        val along = ArrayList<Float>(64)
        walk += points.first()
        along += 0f
        var total = 0f
        for (index in 1 until points.size) {
            total += hypot(
                (points[index].x - points[index - 1].x).toFloat(),
                (points[index].y - points[index - 1].y).toFloat(),
            )
            walk += points[index]
            along += total
            if (total > limitPixels + gap) break
        }
        if (total <= 0f) return

        // Les positions demandées ne font que croître : un curseur qui n'avance jamais en
        // arrière suffit, sans quoi il faudrait rechercher le segment à chaque pointe.
        var cursor = 1
        fun locate(distance: Float): FloatArray? {
            if (distance > along.last()) return null
            while (cursor < along.size && along[cursor] < distance) cursor++
            if (cursor >= along.size) return null
            val from = walk[cursor - 1]
            val to = walk[cursor]
            val segment = along[cursor] - along[cursor - 1]
            if (segment <= 0f) return null
            val t = (distance - along[cursor - 1]) / segment
            return floatArrayOf(
                (from.x + (to.x - from.x) * t).toFloat(),
                (from.y + (to.y - from.y) * t).toFloat(),
                ((to.x - from.x) / segment).toFloat(),
                ((to.y - from.y) / segment).toFloat(),
            )
        }

        val chevrons = Path()
        var start = spacing
        while (start <= minOf(limitPixels, along.last())) {
            // Les deux pointes d'une paire sont posées à l'abscisse curviligne, non le long
            // du segment courant : autrement la seconde disparaissait dès qu'elle tombait
            // au-delà d'un sommet, ce qui, sur un tracé de route, arrive une fois sur deux —
            // et un double chevron qui se réduit à une pointe ne dit plus qu'un accident.
            listOf(start, start + gap).forEach { distance ->
                val at = locate(distance) ?: return@forEach
                if (at[0] >= area.left - margin && at[0] <= area.right + margin &&
                    at[1] >= area.top - margin && at[1] <= area.bottom + margin
                ) {
                    addChevron(chevrons, chevron(at, size))
                }
            }
            start += spacing
        }
        canvas.drawPath(
            chevrons,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.BUTT
                strokeJoin = Paint.Join.MITER
                color = CHEVRON_COLOR
            },
        )
    }

    /**
     * Flèche de position reprenant celle de la navigation Karoo : une pointe large, presque
     * aussi étalée que haute, échancrée à la base, cernée de sombre pour rester lisible
     * au-dessus du tracé.
     *
     * L'écartement des branches est relevé sur l'appareil : une flèche étroite se confond
     * avec les chevrons du tracé, alors que celle-ci se lit d'emblée comme « moi ». C'est
     * précisément ce qui lui vaut de revenir : le double chevron blanc qui l'avait remplacée
     * était de la même famille que les jalons noirs semés devant, et il fallait le chercher.
     *
     * Elle se pose à la place fixe du coureur et pointe vers le haut, la carte tournant sous
     * elle : rien à projeter, rien à orienter.
     *
     * Les angles sont adoucis en épaississant le contour au lieu d'arrondir le tracé point
     * par point : une jointure ronde d'épaisseur *r* arrondit d'un rayon *r* les quatre
     * sommets d'un coup. Le contour étant tracé vers l'extérieur, la silhouette est calculée
     * en retrait d'autant pour que la flèche garde sa taille.
     */
    private fun drawRider(canvas: Canvas, x: Float, y: Float, height: Float) {
        val size = (height * RIDER_HEIGHT_FRACTION).coerceIn(14f, 30f)
        val radius = size * CORNER_RATIO
        val body = size - radius
        val arrow = Path().apply {
            moveTo(x, y - body)
            lineTo(x + body * ARROW_HALF_WIDTH, y + body * ARROW_BASE)
            lineTo(x, y + body * ARROW_NOTCH)
            lineTo(x - body * ARROW_HALF_WIDTH, y + body * ARROW_BASE)
            close()
        }
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ARROW_BORDER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = radius * 2 + ARROW_BORDER_WIDTH * 2
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            },
        )
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ARROW_COLOR
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = radius * 2
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            },
        )
    }

    /** Les trois sommets d'un chevron — bras, pointe, bras — en `[x, y, x, y, x, y]`. */
    private fun chevron(at: FloatArray, size: Float): FloatArray {
        val x = at[0]
        val y = at[1]
        val ux = at[2]
        val uy = at[3]
        val px = -uy
        val py = ux
        val tipX = x + ux * size
        val tipY = y + uy * size
        val backX = tipX - ux * size * CHEVRON_SWEEP
        val backY = tipY - uy * size * CHEVRON_SWEEP
        val armX = px * size * CHEVRON_ARM
        val armY = py * size * CHEVRON_ARM
        return floatArrayOf(backX + armX, backY + armY, tipX, tipY, backX - armX, backY - armY)
    }

    private fun addChevron(path: Path, chevron: FloatArray) {
        path.moveTo(chevron[0], chevron[1])
        path.lineTo(chevron[2], chevron[3])
        path.lineTo(chevron[4], chevron[5])
    }

    private fun polyline(points: List<PlanePoint>): Path = Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x.toFloat(), point.y.toFloat())
            else lineTo(point.x.toFloat(), point.y.toFloat())
        }
    }

    /** Où le coureur se tient sur le tracé : le point, et le sommet qui le suit. */
    private class Anchor(val index: Int, val point: PlanePoint)

    /**
     * Point du tracé le plus proche du coureur, cherché à l'écran.
     *
     * On ne se fie pas à la distance parcourue rapportée par l'appareil : elle se décale, et
     * le tracé se couperait alors au mauvais endroit. La position à l'écran, elle, ne ment pas.
     *
     * La projection se fait sur le **segment** et non sur le sommet le plus proche. C'est
     * toute la différence entre un tracé qui avance avec le coureur et un tracé qui l'attend :
     * accroché à un sommet, le point de coupure — donc le début du fondu — restait planté
     * là pendant qu'on roulait vers lui, puis sautait d'un coup au sommet suivant dès qu'on
     * passait à mi-chemin. Un tracé de route ayant un sommet tous les cinquante à cent mètres,
     * cela faisait un bond de cette longueur, suivi d'une remontée : « le tracé saute devant
     * moi et je le rattrape ». Projeté sur le segment, le point glisse continûment et les
     * fondu commence toujours à l'aplomb du coureur.
     */
    private fun anchor(points: List<PlanePoint>, x: Float, y: Float): Anchor {
        var best = Anchor(1, points.first())
        var bestDistance = Float.MAX_VALUE
        for (index in 1 until points.size) {
            val projected = projectOnSegment(points[index - 1], points[index], x, y)
            val dx = projected.x.toFloat() - x
            val dy = projected.y.toFloat() - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = Anchor(index, projected)
            }
        }
        return best
    }

    /** Projection orthogonale d'un point sur un segment, bornée à ses deux extrémités. */
    private fun projectOnSegment(from: PlanePoint, to: PlanePoint, x: Float, y: Float): PlanePoint {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val squared = dx * dx + dy * dy
        if (squared <= 0.0) return from
        val t = (((x - from.x) * dx + (y - from.y) * dy) / squared).coerceIn(0.0, 1.0)
        return PlanePoint(from.x + dx * t, from.y + dy * t)
    }

    /**
     * Épaisseur du tracé, décroissante avec la portée affichée.
     *
     * Une épaisseur fixe ne peut pas convenir aux deux bouts de la plage : à 200 m elle
     * représente un ruban de quelques mètres, à 10 km une bande de plusieurs centaines. La
     * décroissance suit le logarithme de la portée plutôt que la portée elle-même — sinon
     * le trait s'effondrerait dès le premier cran et resterait filiforme sur toute la
     * moitié haute de la plage.
     */
    private fun routeWidth(rangeMeters: Double): Float {
        val range = rangeMeters.coerceIn(MIN_RANGE, MAX_RANGE)
        val ratio = ln(range / MIN_RANGE) / ln(MAX_RANGE / MIN_RANGE)
        return (ROUTE_WIDTH_NEAR - (ROUTE_WIDTH_NEAR - ROUTE_WIDTH_FAR) * ratio).toFloat()
    }

    /**
     * Points d'intérêt : une pastille cernée de la couleur du fond, avec son nom à côté.
     *
     * Le cerne et le halo du texte ne sont pas décoratifs : sans eux, la pastille se confond
     * avec le tracé qu'elle jouxte et le nom devient illisible dès qu'il tombe sur une route.
     * Le cerne prend l'encre de la carte, le halo du texte sa couleur de fond : chacun se pose
     * ainsi sur ce qu'il doit détacher, et tous deux suivraient un changement de fond.
     */
    private fun drawPois(
        canvas: Canvas,
        area: RectF,
        model: MapModel,
        projection: Projection,
        palette: Palette,
    ) {
        if (model.pois.isEmpty()) return
        val labelSize = (area.height() * 0.062f).coerceIn(12f, 20f)
        val radius = (area.height() * 0.026f).coerceIn(5f, 10f)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = POI_COLOR
            style = Paint.Style.FILL
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.45f
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.BACKGROUND
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = labelSize * 0.30f
            strokeJoin = Paint.Join.ROUND
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
            textSize = labelSize
            typeface = Typeface.DEFAULT_BOLD
        }

        model.pois.forEach { poi ->
            val screen = projection.toScreen(poi.position)
            val x = screen.x.toFloat()
            val y = screen.y.toFloat()
            if (x < area.left - 20 || x > area.right + 20 || y < area.top - 20 || y > area.bottom + 20) return@forEach
            canvas.drawCircle(x, y, radius, dot)
            canvas.drawCircle(x, y, radius, ring)

            // Le nom se met à gauche quand il déborderait à droite : la carte est étroite.
            val width = text.measureText(poi.label)
            val left = if (x + radius + 6f + width <= area.right) x + radius + 6f else x - radius - 6f - width
            val baseline = y + labelSize * 0.35f
            canvas.drawText(poi.label, left, baseline, halo)
            canvas.drawText(poi.label, left, baseline, text)
        }
    }

    /**
     * Point d'intérêt : un point blanc, cerné de sombre.
     *
     * Il a porté du bleu, puis le violet du Karoo. Aucune de ces teintes ne disait rien de
     * plus que « ici » — et chacune entrait en conflit avec un sens déjà pris : le bleu est
     * celui du tracé, le violet celui des tours dans le système de Hammerhead. Le blanc ne
     * prétend à rien : c'est un repère, pas une catégorie.
     *
     * C'est le cerne qui le fait tenir, et il passe donc au sombre : un point blanc cerné de
     * crème disparaîtrait sur le fond crème, alors que sombre il tient partout — sur le fond,
     * sur le ruban, sur une départementale orange.
     */
    private const val POI_COLOR = 0xFFFFFFFF.toInt()

    /**
     * Bleu du tracé.
     *
     * Le jaune d'avant s'est révélé faible sur le fond crème, où il fallait le cerner de noir
     * pour qu'il tienne. Ce bleu franc s'y détache seul, et aucune classe de voie ne le porte :
     * une départementale orange ou un chemin brun ne peuvent pas être pris pour l'itinéraire.
     */
    private const val ROUTE_COLOR = 0xFF2E8BFF.toInt()

    /**
     * Le double chevron, en part de l'épaisseur du ruban.
     *
     * Trois calibres ont été mis côte à côte à la taille de l'appareil ; celui-ci a été
     * retenu. La demi-envergure vaut exactement la demi-largeur du ruban : chaque branche
     * s'arrête ainsi d'elle-même au bord, d'une coupe courte et perpendiculaire à elle.
     * Dessinée plus longue et laissée au gabarit, elle était coupée *le long* du bord et
     * laissait un long coin qui longeait la bordure au lieu de s'y arrêter.
     *
     * Le pas entre deux paires vaut près de quatre fois la largeur du ruban : assez pour
     * qu'aucun jalon ne vienne se poser sur la flèche de position.
     *
     * Le trait s'est affiné d'un tiers. À sa première épaisseur, le double chevron mangeait
     * presque toute la largeur du ruban : il ne jalonnait plus le tracé, il le hachurait, et
     * le bleu ne se voyait plus qu'entre deux paires. Une marque doit se poser sur ce qu'elle
     * marque, pas le remplacer.
     */
    private const val CHEVRON_SPACING_RATIO = 3.88f
    private const val CHEVRON_GAP_RATIO = 0.71f
    private const val CHEVRON_SIZE_RATIO = 0.556f
    private const val CHEVRON_STROKE_RATIO = 0.155f

    /** Recul des branches derrière la pointe, et leur écartement, en part de la taille. */
    private const val CHEVRON_SWEEP = 1.6f
    private const val CHEVRON_ARM = 0.9f

    /** Le noir des jalons : il perce le ruban sans rien ajouter à la carte. */
    private const val CHEVRON_COLOR = 0xFF000000.toInt()

    /**
     * La flèche de position, et sa hauteur en part de celle de la carte.
     *
     * Elle ne partage rien avec les jalons — ni la forme, ni la couleur, ni le contour. C'est
     * tout l'intérêt : le double chevron blanc qui l'avait remplacée était de la même famille
     * qu'eux, et se cherchait au milieu d'eux.
     */
    private const val RIDER_HEIGHT_FRACTION = 0.085f
    private const val ARROW_COLOR = 0xFFE6E24C.toInt()
    private const val ARROW_BORDER_COLOR = 0xFF1E1E1E.toInt()
    private const val ARROW_BORDER_WIDTH = 2.5f

    /** Rayon d'adoucissement des angles de la flèche, en part de sa taille. */
    private const val CORNER_RATIO = 0.10f

    /**
     * Demi-envergure, hauteur de la base et profondeur de l'échancrure, en part du corps.
     *
     * Relevés sur l'appareil : une flèche étroite se confond avec les chevrons du tracé,
     * celle-ci se lit d'emblée comme « moi ».
     */
    private const val ARROW_HALF_WIDTH = 0.82f
    private const val ARROW_BASE = 0.72f
    private const val ARROW_NOTCH = 0.19f

    /** Rouge du hors-itinéraire : celui du Karoo, pour dire la même chose de la même façon. */
    private val OFF_ROUTE_COLOR = FieldPalette.REJOIN

    /** Épaisseur du tracé aux deux bouts de la plage de portées. */
    private const val ROUTE_WIDTH_NEAR = 17.0
    private const val ROUTE_WIDTH_FAR = 8.0

    /** Bornes de la plage de portées, reprises de ZoomLevels. */
    private const val MIN_RANGE = 200.0
    private const val MAX_RANGE = 10_000.0

    private fun drawScaleBar(
        canvas: Canvas,
        area: RectF,
        rangeMeters: Double,
        metersToPixels: Float,
        palette: Palette,
    ) {
        val scaleMeters = Geo.niceScale(rangeMeters / 2)
        val barWidth = scaleMeters.toFloat() * metersToPixels
        if (barWidth < 10f || barWidth > area.width()) return

        val labelSize = (area.height() * 0.045f).coerceIn(9f, 15f)
        val y = area.bottom - labelSize * 0.5f
        val left = area.left + 6f

        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
            strokeWidth = 3f
        }
        canvas.drawLine(left, y, left + barWidth, y, bar)
        canvas.drawLine(left, y - 4f, left, y + 4f, bar)
        canvas.drawLine(left + barWidth, y - 4f, left + barWidth, y + 4f, bar)

        val label = if (scaleMeters >= 1_000) "${(scaleMeters / 1_000).toInt()} km" else "${scaleMeters.toInt()} m"
        canvas.drawText(
            label,
            left,
            y - 6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = RoadStyle.INK
                textSize = labelSize
                typeface = Typeface.DEFAULT_BOLD
            },
        )
    }

    private fun drawMessage(canvas: Canvas, area: RectF, message: String?, palette: Palette) {
        canvas.drawRect(area, Paint().apply { color = RoadStyle.BACKGROUND })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
            textSize = (area.height() * 0.1f).coerceIn(10f, 22f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            message ?: "—",
            area.centerX(),
            area.centerY() - (paint.descent() + paint.ascent()) / 2f,
            paint,
        )
    }
}
