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
import kotlin.math.hypot
import kotlin.math.ln

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
 * Seul le tracé de l'itinéraire est dessiné, sans fond de carte : tout est calculé sur
 * l'appareil, sans réseau ni tuiles à télécharger.
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

        // Le fond clair est posé même sans voies : c'est lui qui rend la carte lisible au
        // soleil, l'écran du Karoo réfléchissant la lumière au lieu de lutter contre elle.
        canvas.drawRect(area, Paint().apply { color = RoadStyle.BACKGROUND })
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
            area = area,
            model = model,
            projection = projection,
            palette = palette,
            width = routeWidth(model.rangeMeters),
            riderX = riderX,
            riderY = riderY,
            metersToPixels = metersToPixels,
        )
        drawPois(canvas, area, model, projection, palette)
        drawRider(canvas, riderX, riderY, area.height(), palette)
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
     * Le tracé, coupé en deux à hauteur du coureur.
     *
     * Ce qui est fait passe en sourdine, ce qui reste garde sa pleine couleur. C'est ce qui
     * permet de s'y retrouver quand l'itinéraire se recroise : au carrefour, la branche
     * éteinte est celle qu'on a déjà prise.
     *
     * Les chevrons ne courent pas jusqu'au bout de ce qui reste, mais seulement sur les
     * quelques centaines de mètres qui viennent. Sur une boucle qui repasse par son départ,
     * la branche du retour est là, à quelques mètres, et elle appartient bien à « ce qui
     * reste » : ses chevrons désignaient donc une direction qui n'était pas celle du moment.
     * Bornés, ils ne montrent plus qu'un chemin à la fois.
     *
     * Hors itinéraire, tout le tracé passe au rouge — mieux vaut le voir tout de suite que
     * de le découvrir au bout de deux kilomètres — et ses chevrons prennent le même rouge,
     * comme ils prennent ailleurs le jaune du ruban. Ils ne sont alors plus bornés : la
     * borne se compte depuis le point où l'on est *sur* le tracé, or ce point n'existe plus.
     * Il se plaçait là où l'on a quitté l'itinéraire, souvent hors du cadre, et les chevrons
     * avec lui : le ruban rouge qu'on voyait à l'écran n'en portait aucun.
     */
    private fun drawPath(
        canvas: Canvas,
        area: RectF,
        model: MapModel,
        projection: Projection,
        palette: Palette,
        width: Float,
        riderX: Float,
        riderY: Float,
        metersToPixels: Float,
    ) {
        if (model.path.size < 2) return
        val ahead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (model.offRoute) OFF_ROUTE_COLOR else palette.routeLine
        }
        val behind = Paint(ahead).apply {
            strokeWidth = width * TRAVELED_WIDTH_RATIO
            color = FieldPalette.translucent(ahead.color, TRAVELED_ALPHA)
        }
        val outline = Paint(ahead).apply {
            strokeWidth = width + ROUTE_OUTLINE_WIDTH * 2
            color = palette.routeOutline
        }
        // La part parcourue a son propre cerne, à sa propre épaisseur. Sans lui, elle se
        // réduisait à un jaune pâle posé sur un fond crème, c'est-à-dire à rien : le coureur
        // voyait sa flèche flotter sur du vide, sans plus rien pour dire d'où il venait ni
        // qu'il était bien sur le tracé. Le cerne est ce qui fait tenir le ruban sur un fond
        // clair ; le lui refuser revenait à l'effacer.
        val behindOutline = Paint(outline).apply {
            strokeWidth = width * TRAVELED_WIDTH_RATIO + ROUTE_OUTLINE_WIDTH * 2
        }

        val screenPoints = model.path.map { projection.toScreen(it) }
        val here = anchor(screenPoints, riderX, riderY)
        val aheadPoints = buildList {
            add(here.point)
            addAll(screenPoints.subList(here.index, screenPoints.size))
        }
        val traveled = polyline(screenPoints.subList(0, here.index) + here.point)
        val remaining = polyline(aheadPoints)

        canvas.drawPath(traveled, behindOutline)
        canvas.drawPath(remaining, outline)
        canvas.drawPath(traveled, behind)
        canvas.drawPath(remaining, ahead)
        drawDirectionChevrons(
            canvas = canvas,
            area = area,
            points = aheadPoints,
            color = ahead.color,
            routeWidth = width,
            limitPixels = if (model.offRoute) {
                Float.POSITIVE_INFINITY
            } else {
                (model.chevronRangeMeters * metersToPixels).toFloat()
            },
        )
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
     * accroché à un sommet, le point de coupure — donc le début des chevrons — restait planté
     * là pendant qu'on roulait vers lui, puis sautait d'un coup au sommet suivant dès qu'on
     * passait à mi-chemin. Un tracé de route ayant un sommet tous les cinquante à cent mètres,
     * cela faisait un bond de cette longueur, suivi d'une remontée : « le tracé saute devant
     * moi et je le rattrape ». Projeté sur le segment, le point glisse continûment et les
     * chevrons commencent toujours sous la flèche.
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
        return ((ROUTE_WIDTH_NEAR - (ROUTE_WIDTH_NEAR - ROUTE_WIDTH_FAR) * ratio) * TRACE_THINNING).toFloat()
    }

    /**
     * Chevrons semés le long du tracé pour indiquer le sens de la marche, comme sur la
     * carte native du Karoo : cernés de noir, et de la couleur du ruban qu'ils jalonnent —
     * le jaune de la flèche de position sur l'itinéraire, le rouge de rejointe quand on
     * l'a quitté.
     *
     * Le cerne n'est pas un ornement. Un chevron d'une seule couleur disparaît dès que le
     * tracé passe sur un fond de la même valeur — et le tracé est justement clair. Deux
     * passes, l'une plus large et sombre puis l'autre claire, le tiennent lisible partout.
     */
    private fun drawDirectionChevrons(
        canvas: Canvas,
        area: RectF,
        points: List<PlanePoint>,
        color: Int,
        routeWidth: Float,
        limitPixels: Float,
    ) {
        if (limitPixels <= 0f) return
        // Les chevrons débordent le ruban de part et d'autre, cerne compris : c'est ce
        // débord qui les fait lire comme des pointes posées dessus. Contenus dans la
        // largeur du tracé, ils s'y noieraient — le tracé est jaune comme eux.
        // Les chevrons gardent la taille qu'ils avaient avant l'amincissement du ruban :
        // c'est le tracé qu'on a voulu plus fin, pas les pointes qui le jalonnent.
        val reference = (routeWidth / TRACE_THINNING).toFloat()
        val spacing = reference * CHEVRON_SPACING_RATIO
        val stroke = reference * CHEVRON_STROKE_RATIO
        val borderWidth = stroke + (stroke * CHEVRON_BORDER_RATIO).coerceAtLeast(1.6f) * 2
        val halfSpan =
            reference / 2 + ROUTE_OUTLINE_WIDTH + reference * CHEVRON_OVERHANG_RATIO
        val size = ((halfSpan - borderWidth / 2) / CHEVRON_ARM).coerceAtLeast(reference * 0.4f)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ARROW_BORDER_COLOR
        }
        val fill = Paint(border).apply {
            strokeWidth = stroke
            this.color = color
        }
        // Le tracé peut courir des kilomètres hors du cadre ; sans borne de longueur, il
        // faut donc une borne d'écran. La marge tient au débord du chevron sur le segment
        // qui entre dans le cadre.
        val margin = size * CHEVRON_SWEEP + borderWidth
        val left = area.left - margin
        val right = area.right + margin
        val top = area.top - margin
        val bottom = area.bottom + margin

        // Les chevrons sont accumulés puis dessinés en deux passes : sans cela, le cerne du
        // chevron suivant viendrait mordre le jaune du précédent quand ils se serrent.
        val chevrons = Path()
        var carry = 0f
        // Distance parcourue le long du tracé depuis le coureur : c'est elle qui borne les
        // chevrons, et non la distance à vol d'oiseau, sans quoi une épingle en aurait
        // encore là où le tracé est déjà reparti dans l'autre sens.
        var travelled = 0f
        for (index in 1 until points.size) {
            if (travelled >= limitPixels) break
            val from = points[index - 1]
            val to = points[index]
            val dx = (to.x - from.x).toFloat()
            val dy = (to.y - from.y).toFloat()
            val length = hypot(dx, dy)
            if (length < 0.01f) continue

            val ux = dx / length
            val uy = dy / length
            val reach = minOf(length, limitPixels - travelled)
            // Un segment entièrement hors du cadre n'est pas semé, mais il compte : c'est
            // l'écart cumulé qui garde les chevrons régulièrement espacés au retour.
            val visible = maxOf(from.x, to.x) >= left && minOf(from.x, to.x) <= right &&
                maxOf(from.y, to.y) >= top && minOf(from.y, to.y) <= bottom
            if (visible) {
                var position = spacing - carry
                while (position <= reach) {
                    addChevron(
                        path = chevrons,
                        x = from.x.toFloat() + ux * position,
                        y = from.y.toFloat() + uy * position,
                        ux = ux,
                        uy = uy,
                        size = size,
                    )
                    position += spacing
                }
            }
            carry = (carry + length) % spacing
            travelled += length
        }
        canvas.drawPath(chevrons, border)
        canvas.drawPath(chevrons, fill)
    }

    private fun addChevron(path: Path, x: Float, y: Float, ux: Float, uy: Float, size: Float) {
        // Perpendiculaire à la direction de marche.
        val px = -uy
        val py = ux
        val tipX = x + ux * size
        val tipY = y + uy * size
        val backX = tipX - ux * size * CHEVRON_SWEEP
        val backY = tipY - uy * size * CHEVRON_SWEEP
        val armX = px * size * CHEVRON_ARM
        val armY = py * size * CHEVRON_ARM
        path.moveTo(backX + armX, backY + armY)
        path.lineTo(tipX, tipY)
        path.lineTo(backX - armX, backY - armY)
    }

    /**
     * Points d'intérêt : une pastille cerclée de blanc, avec son nom à côté.
     *
     * Le cerne blanc et le halo du texte ne sont pas décoratifs : sans eux, la pastille se
     * confond avec le tracé qu'elle jouxte et le nom devient illisible dès qu'il tombe sur
     * une route. Ils sont dimensionnés d'après la case, pour rester lisibles en roulant.
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
            color = 0xFFFFFFFF.toInt()
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
     * Flèche de position reprenant celle de la navigation Karoo : une pointe large, presque
     * aussi étalée que haute, échancrée à la base, cernée de sombre pour rester lisible
     * au-dessus du tracé.
     *
     * L'écartement des branches est relevé sur l'appareil : une flèche étroite se confond
     * avec les chevrons du tracé, alors que celle-ci se lit d'emblée comme « moi ». Sa
     * hauteur, elle, ne change pas.
     *
     * Les angles sont adoucis en épaississant le contour au lieu d'arrondir le tracé point
     * par point : une jointure ronde d'épaisseur *r* arrondit d'un rayon *r* les quatre
     * sommets d'un coup. Le contour étant tracé vers l'extérieur, la silhouette est calculée
     * en retrait d'autant pour que la flèche garde sa taille.
     */
    private fun drawRider(canvas: Canvas, x: Float, y: Float, height: Float, palette: Palette) {
        val size = (height * 0.085f).coerceIn(14f, 30f)
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

    /** Point d'intérêt : un bleu soutenu, seule couleur froide de la carte. */
    private const val POI_COLOR = 0xFF1B5E9A.toInt()

    private const val ARROW_COLOR = 0xFFE6E24C.toInt()
    private const val ARROW_BORDER_COLOR = 0xFF1E1E1E.toInt()
    private const val ARROW_BORDER_WIDTH = 2.5f

    /**
     * Rayon d'arrondi des sommets de la flèche, en part de sa taille.
     *
     * Juste de quoi ôter l'agressivité des pointes : au-delà, la flèche s'émousse et perd
     * la franchise de direction qui fait tout son intérêt. Sa taille hors tout n'en dépend
     * pas — la silhouette est calculée en retrait du rayon.
     */
    private const val CORNER_RATIO = 0.10f

    /**
     * Silhouette de la flèche, en part de sa demi-hauteur : demi-largeur, ordonnée des
     * branches, fond de l'échancrure. La largeur vaut ainsi 0,95 fois la hauteur totale,
     * proportion relevée sur la flèche de la navigation Karoo.
     */
    private const val ARROW_HALF_WIDTH = 0.82f
    private const val ARROW_BASE = 0.72f
    private const val ARROW_NOTCH = 0.19f

    /**
     * Amincissement du tracé : il masquait le carrefour qu'il traverse. Les chevrons, eux,
     * gardent leur envergure — c'est par eux que le tracé se signale désormais.
     */
    private const val TRACE_THINNING = 2.0 / 3.0

    /**
     * Part de l'épaisseur et opacité du tracé déjà parcouru.
     *
     * Il s'agit de le mettre en sourdine, pas de l'effacer : le tracé doit rester apparent
     * d'un bout à l'autre, seuls les chevrons disant le sens sur les quelques centaines de
     * mètres qui viennent. À trente-cinq pour cent, la part faite était invisible sur le fond
     * clair de la carte ; quatre-vingts la laissent lire sans qu'on la confonde avec la suite.
     */
    private const val TRAVELED_WIDTH_RATIO = 0.75f
    private const val TRAVELED_ALPHA = 0xCC

    /** Rouge du hors-itinéraire : celui du Karoo, pour dire la même chose de la même façon. */
    private val OFF_ROUTE_COLOR = FieldPalette.REJOIN

    /** Épaisseur du tracé aux deux bouts de la plage de portées, et cerne. */
    private const val ROUTE_WIDTH_NEAR = 14.0
    private const val ROUTE_WIDTH_FAR = 6.0
    private const val ROUTE_OUTLINE_WIDTH = 4f

    /** Bornes de la plage de portées, reprises de ZoomLevels. */
    private const val MIN_RANGE = 200.0
    private const val MAX_RANGE = 10_000.0

    /** Chevrons de direction, exprimés en part de l'épaisseur du tracé. */
    private const val CHEVRON_SPACING_RATIO = 4.3f
    private const val CHEVRON_STROKE_RATIO = 0.43f

    /** Débord du chevron au-delà du bord du tracé, cerne du tracé compris. */
    private const val CHEVRON_OVERHANG_RATIO = 0.35f

    /** Cerne du chevron, en part de son propre trait. */
    private const val CHEVRON_BORDER_RATIO = 0.35f

    /** Recul des branches derrière la pointe, et leur écartement, en part de la taille. */
    private const val CHEVRON_SWEEP = 1.6f
    private const val CHEVRON_ARM = 0.9f

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
