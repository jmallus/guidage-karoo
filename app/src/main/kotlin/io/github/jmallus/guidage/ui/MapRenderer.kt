package io.github.jmallus.guidage.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.PlanePoint
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.fromMicroDegrees
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

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
 * routière, jusqu'aux bords du cadre. Elles s'effaçaient autrefois à mesure qu'elles
 * s'écartaient de l'itinéraire, ne laissant qu'un couloir autour de lui : c'était une réponse
 * au fond noir, où tout ce qui n'était pas le tracé faisait du bruit blanc. Sur un fond en
 * couleur, les classes se distinguent d'elles-mêmes et le tracé se détache par sa teinte ;
 * éteindre le reste ne faisait plus qu'effacer ce qu'on est venu chercher — un carrefour à
 * cent mètres du tracé est précisément ce qu'on regarde.
 *
 * Le tracé est un ruban bleu, franc devant le coureur et clair derrière lui, jalonné de
 * doubles chevrons noirs qui disent le sens à prendre : à un carrefour en T, les deux branches
 * du ruban se ressemblent. La position est une pastille ronde à flèche, posée à la place fixe
 * du coureur ; les points d'intérêt en sont d'autres, magenta, portant un repère de carte ; et
 * une rose des vents, en haut à droite, rend le nord que la vue « cap en haut » efface.
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
            drawAreas(canvas, areas, projection)
            drawRoads(canvas, lines, projection, metersToPixels)
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
        drawCompass(canvas, area, heading)

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

    /**
     * La rose des vents, en haut à droite : une aiguille dans une pastille, moitié rouge vers
     * le nord et moitié blanche vers le sud.
     *
     * La carte tourne avec le coureur — c'est ce qui la rend lisible en roulant, ce qui est
     * devant étant en haut — mais on y perd le nord au sens propre. Une carte papier dépliée
     * sur le bord de la route, un panneau, le souvenir d'un versant : tout cela se raccroche
     * à une orientation absolue que la vue « cap en haut » efface.
     *
     * Le nord est à l'angle **moins le cap** de la verticale, puisque la vue a été tournée
     * d'autant : cap nul, l'aiguille pointe en haut ; cap à l'est, elle pointe à gauche.
     *
     * Le coin haut droit est le seul libre : la mention du fond absent occupe le gauche,
     * l'échelle le bas.
     */
    private fun drawCompass(canvas: Canvas, area: RectF, heading: Double) {
        val rayon = (area.height() * COMPASS_RADIUS_FRACTION).coerceIn(27f, 48f)
        val cx = area.right - COMPASS_INSET - rayon
        val cy = area.top + COMPASS_INSET + rayon

        canvas.drawCircle(
            cx,
            cy,
            rayon,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = BADGE_COLOR
            },
        )
        val anneau = rayon * RING_FRACTION
        canvas.drawCircle(
            cx,
            cy,
            rayon - anneau / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = anneau
                color = COMPASS_RING
            },
        )

        val radians = Math.toRadians(heading)
        val nordX = (-sin(radians)).toFloat()
        val nordY = (-cos(radians)).toFloat()
        val longueur = rayon * NEEDLE_LENGTH
        val demiBase = rayon * NEEDLE_BASE

        fun aiguille(sens: Float, teinte: Int) {
            canvas.drawPath(
                Path().apply {
                    moveTo(cx + nordX * longueur * sens, cy + nordY * longueur * sens)
                    lineTo(cx - nordY * demiBase, cy + nordX * demiBase)
                    lineTo(cx + nordY * demiBase, cy - nordX * demiBase)
                    close()
                },
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = teinte
                },
            )
        }

        aiguille(-1f, COMPASS_SOUTH)
        aiguille(1f, COMPASS_NORTH)
    }

    /**
     * Rayon de la rose, en part de la hauteur de la carte, et sa marge au coin.
     *
     * Triplé après essai : au tiers de cette taille, l'aiguille tenait dans une trentaine de
     * points et il fallait la chercher pour la lire. Une orientation qu'on doit chercher ne
     * sert à rien — on la consulte d'un coup d'œil ou pas du tout.
     *
     * Elle occupe désormais un bon tiers de la largeur de la carte. C'est beaucoup, et c'est
     * le prix : elle est dessinée en dernier et couvre donc le fond, voire le ruban là où
     * l'itinéraire part vers la droite juste devant le coureur.
     */
    private const val COMPASS_RADIUS_FRACTION = 0.135f
    private const val COMPASS_INSET = 6f

    /** Longueur d'une demi-aiguille et demi-largeur de sa base, en part du rayon. */
    private const val NEEDLE_LENGTH = 0.70f
    private const val NEEDLE_BASE = 0.26f

    /**
     * Le rouge du nord et le blanc du sud, convention de toutes les boussoles.
     *
     * Le rouge est celui que le système réserve aux erreurs. Sur une aiguille de quelques
     * pixels, dans un coin, il ne peut pas se confondre avec un ruban devenu rouge : c'est la
     * forme qui parle, et une boussole rouge et blanche ne se lit pas autrement.
     */
    private val COMPASS_NORTH = KarooColors.UI_RED
    private const val COMPASS_SOUTH = 0xFFFFFFFF.toInt()
    private const val COMPASS_RING = 0xFFFFFFFF.toInt()

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
     * Le tracé : un ruban bleu, plus clair derrière le coureur que devant.
     *
     * Il a été d'une seule encre, après avoir été d'un dégradé qui s'éteignait vers le
     * transparent. Le dégradé avait un vrai défaut — sur une épingle, la branche évanouie du
     * retour longeait la branche pleine de l'aller et l'on ne savait plus laquelle était
     * l'itinéraire. Deux bleus francs n'ont pas ce défaut : le clair reste un ruban, il ne
     * disparaît pas, et l'on voit du premier coup d'œil ce qui est fait et ce qui reste.
     *
     * Hors itinéraire, tout passe au rouge de rejointe du Karoo, d'un seul tenant : ce n'est
     * plus le moment de savoir où l'on en est du parcours, mais qu'on n'y est plus.
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
        }

        // Marque, jalons et coupure des deux bleus partent tous du point du tracé le plus
        // proche du coureur.
        val here = anchor(screenPath, riderX, riderY)
        val ahead = buildList {
            add(here.point)
            addAll(screenPath.subList(here.index, screenPath.size))
        }
        val behind = buildList {
            addAll(screenPath.subList(0, here.index))
            add(here.point)
        }

        val route = polyline(screenPath)
        if (model.offRoute) {
            paint.color = OFF_ROUTE_COLOR
            canvas.drawPath(route, paint)
        } else {
            // La part faite d'abord, celle qui reste par-dessus : leur point commun est
            // l'aplomb du coureur, et c'est le bout rond du second qui recouvre la couture.
            paint.color = ROUTE_BEHIND_COLOR
            canvas.drawPath(polyline(behind), paint)
            paint.color = ROUTE_COLOR
            canvas.drawPath(polyline(ahead), paint)
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
     * La marque de position : une pastille ronde portant une flèche, comme sur les compteurs
     * qui savent se faire trouver.
     *
     * La flèche nue qui la précédait avait le défaut de sa forme : posée sur un ruban qu'elle
     * dépassait à peine, entre des chevrons de même famille, elle se cherchait. Le disque la
     * détache du fond quel qu'il soit — c'est lui qui la fait voir, la flèche ne servant plus
     * qu'à dire le sens.
     *
     * Le disque est sombre et la flèche blanche, l'anneau blanc aussi. Un disque de couleur
     * serait plus voyant encore, mais toutes les couleurs sont prises : le rouge dit l'erreur,
     * le jaune l'itinéraire, le vert la donnée vive, le violet les tours. Le sombre ne dit
     * rien, et c'est ce qu'on lui demande.
     *
     * Elle se pose à la place fixe du coureur et pointe vers le haut, la carte tournant sous
     * elle : rien à projeter, rien à orienter.
     */
    private fun drawRider(canvas: Canvas, x: Float, y: Float, height: Float) {
        val rayon = (height * RIDER_HEIGHT_FRACTION).coerceIn(22f, 40f)
        val anneau = rayon * RING_FRACTION

        canvas.drawCircle(
            x,
            y,
            rayon,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = BADGE_COLOR
            },
        )
        // L'anneau se dessine sur la ligne médiane du trait : il faut donc rentrer son rayon
        // d'une demi-épaisseur pour qu'il reste dans le disque au lieu de le déborder.
        canvas.drawCircle(
            x,
            y,
            rayon - anneau / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = anneau
                color = ARROW_COLOR
            },
        )

        // La flèche garde la silhouette d'avant — pointe large, base échancrée — réduite pour
        // tenir dans l'anneau. Ses angles sont adoucis en épaississant le contour plutôt qu'en
        // arrondissant le tracé point par point : une jointure ronde d'épaisseur *r* arrondit
        // d'un rayon *r* les quatre sommets d'un coup.
        val corps = rayon * ARROW_FRACTION
        val adouci = corps * CORNER_RATIO
        canvas.drawPath(
            Path().apply {
                moveTo(x, y - corps)
                lineTo(x + corps * ARROW_HALF_WIDTH, y + corps * ARROW_BASE)
                lineTo(x, y + corps * ARROW_NOTCH)
                lineTo(x - corps * ARROW_HALF_WIDTH, y + corps * ARROW_BASE)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ARROW_COLOR
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = adouci * 2
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
     * Points d'intérêt : une pastille magenta portant un repère de carte, avec son nom à côté.
     *
     * Ce n'était qu'un point coloré. Un point ne dit que « ici », et il faut savoir d'avance
     * ce qu'il désigne pour ne pas le prendre pour un carrefour ou un sommet du tracé. Le
     * repère, lui, se reconnaît sans qu'on l'ait appris : c'est le pictogramme qu'emploient
     * toutes les cartes du monde, et il a une pointe, qui désigne un endroit précis là où un
     * disque ne fait que le couvrir.
     *
     * Le magenta n'est pris par rien d'autre. C'est ce qui l'a fait choisir : le bleu est
     * celui du tracé, le jaune celui de l'itinéraire dans le système de Hammerhead, le violet
     * celui des tours, le rouge celui des erreurs.
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
        val radius = (area.height() * POI_RADIUS_FRACTION).coerceIn(16f, 28f)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = POI_COLOR
            style = Paint.Style.FILL
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RoadStyle.INK
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.22f
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
            drawPoiPin(canvas, x, y, radius)

            // Le nom se met à gauche quand il déborderait à droite : la carte est étroite.
            val width = text.measureText(poi.label)
            val left = if (x + radius + 6f + width <= area.right) x + radius + 6f else x - radius - 6f - width
            val baseline = y + labelSize * 0.35f
            canvas.drawText(poi.label, left, baseline, halo)
            canvas.drawText(poi.label, left, baseline, text)
        }
    }

    /**
     * Le repère de carte posé dans la pastille : une goutte blanche, percée en son centre.
     *
     * Le trou n'est pas un détail. Sans lui, la goutte n'est qu'une tache blanche vaguement
     * pointue ; avec, on reconnaît le pictogramme sans y penser — c'est l'anneau, et non la
     * silhouette, qui le fait lire. Il est percé en repeignant la teinte de la pastille
     * par-dessus, ce qui coûte un cercle et évite un chemin à deux contours.
     *
     * La tête et la pointe forment deux sous-chemins qui se recouvrent : remplis ensemble en
     * mode non nul, leur réunion donne la goutte sans qu'il faille calculer les tangentes.
     */
    private fun drawPoiPin(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val tete = radius * PIN_HEAD
        val centre = y - radius * PIN_LIFT
        val pointe = y + radius * PIN_TIP

        canvas.drawPath(
            Path().apply {
                fillType = Path.FillType.WINDING
                addCircle(x, centre, tete, Path.Direction.CW)
                moveTo(x - tete * PIN_SHOULDER, centre + tete * PIN_SHOULDER)
                lineTo(x + tete * PIN_SHOULDER, centre + tete * PIN_SHOULDER)
                lineTo(x, pointe)
                close()
            },
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = PIN_COLOR
            },
        )
        canvas.drawCircle(
            x,
            centre,
            tete * PIN_HOLE,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = POI_COLOR
            },
        )
    }

    /**
     * Le magenta des points d'intérêt, défini une seule fois dans [FieldPalette].
     *
     * Le profil le reprend pour ses jalons : la teinte doit rester commune aux deux champs,
     * sinon rien ne dirait que la pastille de la carte et le jalon du profil désignent la
     * même chose.
     */
    private const val POI_COLOR = FieldPalette.POI

    /** Le blanc du repère, et ses proportions en part du rayon de la pastille. */
    private const val PIN_COLOR = 0xFFFFFFFF.toInt()
    private const val PIN_HEAD = 0.38f
    private const val PIN_LIFT = 0.16f
    private const val PIN_TIP = 0.58f
    private const val PIN_SHOULDER = 0.74f
    private const val PIN_HOLE = 0.44f

    /**
     * Rayon de la pastille, en part de la hauteur de la carte.
     *
     * Doublé comme celle de la position et comme la rose des vents. Un repère de carte n'a
     * d'intérêt qu'à la taille où l'on distingue sa forme : plus petit, il redevient le point
     * coloré qu'il remplace, et le trou qui le fait reconnaître disparaît le premier.
     */
    private const val POI_RADIUS_FRACTION = 0.080f

    /**
     * Bleu du tracé.
     *
     * Le jaune d'avant s'est révélé faible sur le fond crème, où il fallait le cerner de noir
     * pour qu'il tienne. Ce bleu franc s'y détache seul, et aucune classe de voie ne le porte :
     * une départementale orange ou un chemin brun ne peuvent pas être pris pour l'itinéraire.
     */
    private const val ROUTE_COLOR = 0xFF2E8BFF.toInt()

    /**
     * Le bleu clair de ce qui est déjà fait.
     *
     * Assez pâle pour qu'on distingue au premier coup d'œil l'avant de l'arrière, assez franc
     * pour rester un ruban : c'est là que le dégradé d'autrefois échouait, en s'éteignant vers
     * le transparent jusqu'à laisser une branche fantôme le long de celle qui compte.
     */
    private const val ROUTE_BEHIND_COLOR = 0xFF9CC7FF.toInt()

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
    /**
     * Rayon de la pastille de position, en part de la hauteur de la carte.
     *
     * Doublé après essai, comme la rose des vents avant elle. Une marque de position n'a pas
     * à être discrète : c'est le seul point de l'écran qu'on cherche des yeux sans y penser,
     * et le seul qu'on doive trouver sans le chercher.
     */
    private const val RIDER_HEIGHT_FRACTION = 0.12f

    /**
     * Le blanc de la flèche et de son anneau, le sombre du disque qui les porte.
     *
     * La flèche a porté du jaune, la couleur que le système réserve à l'itinéraire : la
     * position du coureur n'est pas l'itinéraire, et la teinte disait donc autre chose que la
     * forme. Le blanc ne prétend à rien, et le disque sombre lui donne le contraste qu'un
     * simple cerne ne suffisait pas à lui donner.
     */
    private const val ARROW_COLOR = 0xFFFFFFFF.toInt()
    private const val BADGE_COLOR = 0xFF1E1E1E.toInt()

    /** Épaisseur de l'anneau, et taille de la flèche, en part du rayon du disque. */
    private const val RING_FRACTION = 0.16f
    private const val ARROW_FRACTION = 0.62f

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
    private const val MIN_RANGE = 300.0
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
