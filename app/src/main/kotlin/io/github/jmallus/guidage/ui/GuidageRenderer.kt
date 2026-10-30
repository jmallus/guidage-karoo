package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.map.RoadKind
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Une donnée du rang du haut : son libellé, sa valeur, et la couleur de sa zone. */
data class DonneeGuidage(val libelle: String, val valeur: String, val couleur: Int? = null)

/** Ce que montre le milieu de l'écran : une seule chose à la fois, la plus urgente. */
sealed interface CentreGuidage {

    /** Le prochain carrefour où il faut faire quelque chose, quand on en approche. */
    data class Carrefour(
        val titre: String,
        val distance: String,
        /** L'angle que prend l'itinéraire, relatif au cap d'arrivée (degrés, positif à droite). */
        val virage: Double,
        val branches: List<Double>,
        /** Les voies à laisser avant, en part de l'approche (0 au coureur, 1 au carrefour). */
        val voiesAvant: List<Pair<Float, List<Double>>>,
        /** Part de l'approche déjà faite, pour la barre et la place du coureur. */
        val progression: Float,
        /** Distance qui reste (m), pour les bips d'approche. */
        val metres: Double = 0.0,
        /** Où est le carrefour sur le tracé (m) : ce qui le distingue du suivant. */
        val repere: Double = 0.0,
    ) : CentreGuidage


    /**
     * La côte qu'on monte, du pied au sommet : le même cadrage que le Climber du tableau de
     * bord, redessiné à la main.
     */
    data class Montee(
        val profil: ProfileFieldModel,
        val suite: Suite?,
        /** « 1,3 km · +78 m » : ce qui reste jusqu'au sommet. */
        val titre: String = "",
        /** « 1/3 » : la côte parmi celles de l'itinéraire. */
        val rang: String? = null,
    ) : CentreGuidage

    /** Rien d'urgent : la carte, et la prochaine manœuvre en étiquette. */
    data class Carte(val carte: MapModel, val suite: Suite?) : CentreGuidage
}

/** La prochaine manœuvre au loin : de quel côté, et dans combien. */
data class Suite(val virage: Double, val texte: String)

/**
 * Le bandeau du bas : la silhouette de ce qui vient, et ce qui reste — ou, en côte, les
 * chiffres de la côte à la place de la silhouette.
 */
data class BandeauGuidage(
    val fenetre: ProfileWindow,
    val texte: String?,
    val cote: List<DonneeGuidage>? = null,
    /** Où est le coureur, en distance le long de l'itinéraire ; à défaut, au bord gauche. */
    val position: Double? = null,
    /** Le kilométrage au compteur, écrit au-dessus du coureur. */
    val compteur: String? = null,
    /** Les points d'intérêt de l'itinéraire : où, et leur nom. */
    val pois: List<Pair<Double, String>> = emptyList(),
)

data class GuidageModel(
    val donnees: List<DonneeGuidage>,
    val centre: CentreGuidage,
    val bandeau: BandeauGuidage,
    val palette: Palette,
)

/**
 * Le tableau de guidage : l'écran qui suit la route.
 *
 * Trois étages. En haut trois chiffres, en bas la silhouette de ce qui vient, et au milieu
 * **une seule** chose, choisie par urgence : le carrefour qui approche, sinon la côte, sinon
 * la carte. L'œil n'a jamais à chercher où regarder — le milieu
 * de l'écran est toujours la réponse à « qu'est-ce que je dois faire maintenant ».
 *
 * Le dessin reprend celui des maquettes validées : aplats francs, traits ronds et épais, un
 * seul bleu pour l'itinéraire, et du texte blanc gras sur le gris très sombre du Karoo.
 */
object GuidageRenderer {

    const val FOND = 0xFF16181A.toInt()
    const val LIBELLE = 0xFF9CA3AF.toInt()
    const val ROUTE = 0xFF2F80FF.toInt()
    const val VOIE = 0xFF4B5563.toInt()
    const val PISTE = 0xFF2A2E33.toInt()
    /** Le jaune de l'itinéraire dans la langue du Karoo : le profil est celui de l'itinéraire. */
    const val SILHOUETTE = KarooColors.LEMON_YELLOW
    const val CARTE_FOND = 0xFFECE9E1.toInt()
    const val MONTEE_FAITE = 0xFFC2410C.toInt()
    const val MONTEE_RESTE = 0xFF3F8F5F.toInt()
    const val POSITION = 0xFFFACC15.toInt()
    const val CARTE_GRANDE_VOIE = 0xFFFFFFFF.toInt()
    const val CARTE_PETITE_VOIE = 0xFFFFFFFF.toInt()
    const val CARTE_CHEMIN = 0xFFD1C9B5.toInt()
    const val CARTE_EAU = 0xFFA9C8E8.toInt()
    const val BLANC = 0xFFFFFFFF.toInt()

    /** Part de la hauteur tenue par le rang des chiffres, et où commence le bandeau du bas. */
    private const val HAUT = 0.15f
    private const val BAS = 0.735f

    /**
     * Où commence le bandeau du bas (px) : la frontière entre les deux zones d'appui du champ,
     * lue au même endroit par le dessin et par le découpage.
     */
    fun bandeauHaut(height: Int): Float = height * BAS

    fun render(width: Int, height: Int, model: GuidageModel): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), width.toFloat(), height.toFloat(), model)
        return bitmap
    }

    fun draw(canvas: Canvas, w: Float, h: Float, model: GuidageModel) {
        // L'échelle des maquettes : elles sont dessinées sur les 478 points du Karoo 3.
        val k = w / 478f
        canvas.drawColor(FOND)
        donnees(canvas, RectF(0f, 0f, w, h * HAUT), model.donnees, k)
        val centre = RectF(0f, h * HAUT, w, h * BAS)
        when (val c = model.centre) {
            is CentreGuidage.Carrefour -> carrefour(canvas, centre, c, k)
            is CentreGuidage.Montee -> montee(canvas, centre, c, model.palette, k)
            is CentreGuidage.Carte -> carte(canvas, centre, c, model.palette, k)
        }
        bandeau(canvas, RectF(0f, bandeauHaut(h.toInt()), w, h), model.bandeau, k)
    }

    private fun donnees(canvas: Canvas, zone: RectF, donnees: List<DonneeGuidage>, k: Float) {
        if (donnees.isEmpty()) return
        val colonne = zone.width() / donnees.size
        val libelle = texte(15f * k, LIBELLE, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        donnees.forEachIndexed { i, d ->
            val x = zone.left + colonne * (i + 0.5f)
            canvas.drawText(d.libelle, x, zone.top + 28f * k, libelle)
            val valeur = texte(46f * k, d.couleur ?: BLANC, Typeface.DEFAULT_BOLD).apply {
                textAlign = Paint.Align.CENTER
                ajuster(this, d.valeur, colonne - 12f * k)
            }
            canvas.drawText(d.valeur, x, zone.top + 76f * k, valeur)
        }
    }

    private fun carrefour(canvas: Canvas, zone: RectF, c: CentreGuidage.Carrefour, k: Float) {
        val marge = 20f * k
        val titre = texte(38f * k, BLANC, Typeface.DEFAULT_BOLD)
        val distance = texte(38f * k, BLANC, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.RIGHT }
        val largeurDistance = distance.measureText(c.distance)
        ajuster(titre, c.titre, zone.width() - 2 * marge - largeurDistance - 16f * k)
        val ligne = zone.top + 46f * k
        canvas.drawText(c.titre, zone.left + marge, ligne, titre)
        canvas.drawText(c.distance, zone.right - marge, ligne, distance)

        // Le schéma, sous le titre et sans jamais le toucher : le carrefour un peu au-dessus
        // du milieu, l'approche qui monte vers lui depuis le bas, et des branches assez
        // courtes pour que celle qui file tout droit s'arrête avant le texte.
        val barre = zone.bottom - 30f * k
        val schema = ligne + 34f * k
        val pied = barre - 26f * k
        val cx = zone.centerX()
        val cy = schema + (pied - schema) * 0.42f
        val bras = min(zone.width() * 0.36f, cy - schema)

        val voie = trait(VOIE, 16f * k)
        val route = trait(ROUTE, 24f * k)

        // L'approche, en gris : les voies qu'on laisse s'y greffent à leur distance.
        canvas.drawLine(cx, pied, cx, cy, voie)
        c.voiesAvant.forEach { (part, angles) ->
            val y = pied + (cy - pied) * part
            angles.forEach { a -> canvas.drawLine(cx, y, cx + dx(a) * bras * 0.45f, y + dy(a) * bras * 0.45f, trait(VOIE, 13f * k)) }
        }
        c.branches.forEach { a -> canvas.drawLine(cx, cy, cx + dx(a) * bras, cy + dy(a) * bras, voie) }

        // L'itinéraire, en bleu, du coureur jusqu'au bout de la branche à prendre.
        val coureur = pied + (cy - pied) * c.progression.coerceIn(0f, 1f)
        val bout = bras * 0.92f
        val fx = cx + dx(c.virage) * bout
        val fy = cy + dy(c.virage) * bout
        val chemin = Path().apply {
            moveTo(cx, coureur)
            lineTo(cx, cy)
            lineTo(fx, fy)
        }
        canvas.drawPath(chemin, route.apply { strokeJoin = Paint.Join.ROUND })
        pointe(canvas, fx, fy, c.virage, 46f * k, ROUTE)

        canvas.drawCircle(cx, coureur, 13f * k, plein(BLANC))
        canvas.drawCircle(cx, coureur, 13f * k, trait(FOND, 4f * k))

        // La barre d'approche : elle se remplit à mesure qu'on arrive.
        val piste = RectF(zone.left + marge, barre, zone.right - marge, barre + 10f * k)
        canvas.drawRoundRect(piste, 5f * k, 5f * k, plein(PISTE))
        val rempli = RectF(piste.left, piste.top, piste.left + piste.width() * c.progression.coerceIn(0f, 1f), piste.bottom)
        if (rempli.width() > 0f) canvas.drawRoundRect(rempli, 5f * k, 5f * k, plein(ROUTE))
    }

    /**
     * La côte, sur le principe exact du Climber du tableau de bord, redessiné à main levée.
     *
     * Le profil de toute la côte, du pied au sommet, peint tronçon par tronçon aux couleurs de
     * pente du Karoo ; derrière le coureur, les mêmes couleurs en hachures. Une bande pâle
     * marque les six cents mètres que détaillent les cases du dessous ; un trait jaune dit où
     * l'on est ; les points d'intérêt devant soi tombent en pointillés magenta sous leur
     * triangle. Ce qui change est le trait : aplats francs, angles arrondis, hachures larges.
     */
    private fun montee(canvas: Canvas, zone: RectF, c: CentreGuidage.Montee, palette: Palette, k: Float) {
        val suite = c.suite
        val p = c.profil
        val fenetre = p.window
        val cote = p.climbZoom
        val ici = p.positionDistance
        if (fenetre.points.size < 2 || ici == null || cote == null) {
            val bas = if (suite != null) zone.bottom - 46f * k else zone.bottom
            ProfileRenderer.draw(canvas, RectF(zone.left, zone.top + 6f * k, zone.right, bas), p, palette)
            if (suite != null) etiquette(canvas, zone.left + 20f * k, zone.bottom - 40f * k, suite, k)
            return
        }
        val marge = 20f * k
        canvas.drawText(c.titre, zone.left + marge, zone.top + 40f * k, texte(34f * k, BLANC, Typeface.DEFAULT_BOLD))
        c.rang?.let {
            canvas.drawText(it, zone.right - marge, zone.top + 40f * k, texte(30f * k, BLANC, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.RIGHT })
        }

        val basSuite = if (suite != null) 46f * k else 0f
        val casesHaut = zone.bottom - basSuite - 52f * k
        val graphe = RectF(zone.left + marge, zone.top + 62f * k, zone.right - marge, casesHaut - 8f * k)
        val points = fenetre.points
        val debut = fenetre.start
        val etendue = (fenetre.end - debut).coerceAtLeast(1.0)
        val altitudes = (fenetre.maxElevation - fenetre.minElevation).coerceAtLeast(1.0)
        fun x(d: Double) = (graphe.left + ((d - debut) / etendue * graphe.width()).toFloat()).coerceIn(graphe.left, graphe.right)
        fun y(e: Double) = graphe.bottom - ((e - fenetre.minElevation) / altitudes * graphe.height()).toFloat()
        val px = x(ici)

        // La portion que détaillent les cases, posée avant la silhouette : il n'en reste visible
        // que le ciel, et c'est lui qui relie les deux échelles.
        val detail = p.climbDetail ?: (cote.startDistance..cote.endDistance)
        canvas.drawRoundRect(RectF(x(detail.start), graphe.top, x(detail.endInclusive), graphe.bottom), 6f * k, 6f * k, plein(ProfileRenderer.DETAIL_BAND))

        // Les tronçons du Climber : leur longueur s'allonge avec la côte, ceux de même couleur
        // ne font qu'un aplat, et sous la bande ce sont ceux des cases.
        val pas = ProfileRenderer.PROFILE_GRADE_STEPS.firstOrNull { ceil(cote.length / it) <= ProfileRenderer.MAX_PROFILE_SEGMENTS }
            ?: ProfileRenderer.PROFILE_GRADE_STEPS.last()
        val aplats = ProfileRenderer.tronconsDuProfil(cote, points, pas, detail).fold(mutableListOf<ProfileRenderer.Troncon>()) { acc, t ->
            val dernier = acc.lastOrNull()
            if (dernier != null && ProfileRenderer.climbColor(dernier.pente) == ProfileRenderer.climbColor(t.pente)) {
                acc[acc.lastIndex] = ProfileRenderer.Troncon(dernier.debut, t.fin, t.pente)
            } else {
                acc += t
            }
            acc
        }
        val hachure = trait(0, 5f * k).apply { strokeCap = Paint.Cap.BUTT }
        for ((a, b, pente) in aplats) {
            val xa = x(a)
            val xb = x(b)
            if (xb <= xa) continue
            val troncon = Path().apply {
                moveTo(xa, graphe.bottom)
                lineTo(xa, y(altitudeA(points, a)))
                points.filter { it.distance > a && it.distance < b }.forEach { lineTo(x(it.distance), y(it.elevation)) }
                lineTo(xb, y(altitudeA(points, b)))
                lineTo(xb, graphe.bottom)
                close()
            }
            val couleur = ProfileRenderer.climbColor(pente)
            // Derrière le coureur, des hachures de la couleur du tronçon ; devant, l'aplat.
            canvas.save()
            canvas.clipPath(troncon)
            canvas.clipRect(graphe.left, graphe.top, px, graphe.bottom)
            hachure.color = couleur
            var t = xa - graphe.height()
            while (t < xb) {
                canvas.drawLine(t, graphe.bottom, t + graphe.height(), graphe.top, hachure)
                t += 13f * k
            }
            canvas.restore()
            canvas.save()
            canvas.clipRect(px, graphe.top, graphe.right, graphe.bottom)
            canvas.drawPath(troncon, plein(couleur))
            canvas.restore()
        }

        // Les points d'intérêt devant soi : un triangle magenta en haut, des pointillés jusqu'au pied.
        val pointilles = trait(FieldPalette.POI, 4f * k).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * k, 8f * k), 0f)
        }
        for (poi in p.pois) {
            val d = poi.distanceAlongRoute
            if (d < ici || d > fenetre.end) continue
            val qx = x(d)
            canvas.drawLine(qx, graphe.top + 14f * k, qx, graphe.bottom, pointilles)
            canvas.drawPath(
                Path().apply {
                    moveTo(qx - 11f * k, graphe.top - 4f * k)
                    lineTo(qx + 11f * k, graphe.top - 4f * k)
                    lineTo(qx, graphe.top + 16f * k)
                    close()
                },
                plein(FieldPalette.POI).apply { pathEffect = android.graphics.CornerPathEffect(3f * k) },
            )
        }

        // Le coureur : le trait jaune, du ciel au pied.
        canvas.drawLine(px, graphe.top - 4f * k, px, graphe.bottom, trait(KarooColors.LEMON_YELLOW, 5f * k).apply { strokeCap = Paint.Cap.BUTT })

        // Les cases : un tronçon de cent mètres chacune, sur l'échelle de la bande pâle, à leur
        // vraie place — celle qu'on a derrière soi sort à gauche avec sa pente.
        val largeurDetail = (detail.endInclusive - detail.start).coerceAtLeast(1.0)
        fun xc(d: Double) = graphe.left + ((d - detail.start) / largeurDetail * graphe.width()).toFloat()
        val chiffre = texte(24f * k, 0, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        canvas.save()
        canvas.clipRect(graphe.left, casesHaut, graphe.right, casesHaut + 44f * k)
        for ((a, b, pente) in ProfileRenderer.troncons(cote, points)) {
            val xa = xc(a) + 2f * k
            val xb = xc(b) - 2f * k
            if (xb <= graphe.left || xa >= graphe.right || xb - xa < 4f * k) continue
            val r = RectF(xa, casesHaut, xb, casesHaut + 44f * k)
            canvas.drawRoundRect(r, 6f * k, 6f * k, plein(ProfileRenderer.climbColor(pente)))
            val texteCase = String.format(java.util.Locale.getDefault(), "%.1f", pente)
            chiffre.color = ProfileRenderer.climbInk(pente)
            chiffre.textSize = 24f * k
            ajuster(chiffre, texteCase, r.width() - 6f * k)
            canvas.drawText(texteCase, r.centerX(), r.bottom - 13f * k, chiffre)
        }
        canvas.restore()

        if (suite != null) etiquette(canvas, zone.left + marge, zone.bottom - 40f * k, suite, k)
    }

    /**
     * La carte, dessinée comme sur les maquettes plutôt que comme la minicarte du tableau de
     * bord : un fond crème, les routes en blanc d'autant plus larges qu'elles comptent, les
     * chemins en beige, l'itinéraire en bleu et le coureur en pastille sombre. Ni surfaces, ni
     * rose des vents, ni échelle — il ne s'agit pas de lire la région mais de voir où va la
     * route, et le milieu de l'écran ne la montre que lorsque rien d'autre ne presse.
     */
    private fun carte(canvas: Canvas, zone: RectF, c: CentreGuidage.Carte, palette: Palette, k: Float) {
        val m = c.carte
        val cadre = RectF(zone.left + 8f * k, zone.top, zone.right - 8f * k, zone.bottom - 4f * k)
        canvas.drawRoundRect(cadre, 8f * k, 8f * k, plein(CARTE_FOND))
        val position = m.position
        if (position == null) {
            m.emptyMessage?.let { message(canvas, cadre, it, k) }
            return
        }
        canvas.save()
        canvas.clipRect(cadre)
        // Le coureur fixe aux quatre cinquièmes de la hauteur, la portée devant lui.
        val cx = cadre.centerX()
        val cy = cadre.top + cadre.height() * 0.8f
        val echelle = (cy - cadre.top) / m.rangeMeters.toFloat()
        val cap = m.heading ?: 0.0
        val limite = m.rangeMeters * 1.6
        fun trace(points: List<GeoPoint>): Path? {
            var chemin: Path? = null
            for (p in points) {
                val plan = Geo.toTrackUpPlane(position, cap, p)
                val x = cx + plan.x.toFloat() * echelle
                val y = cy - plan.y.toFloat() * echelle
                if (chemin == null) chemin = Path().apply { moveTo(x, y) } else chemin.lineTo(x, y)
            }
            return chemin
        }
        fun proche(points: List<GeoPoint>) = points.any { Geo.distance(position, it) < limite }

        // Les petites voies d'abord, les grandes par-dessus, comme sur une carte routière.
        val voies = m.roads.filter { !it.kind.isArea }.sortedByDescending { it.kind.code }
        for (voie in voies) {
            val points = voie.latitudes.indices.map { GeoPoint(voie.latitudes[it] / 1e6, voie.longitudes[it] / 1e6) }
            if (!proche(points)) continue
            val (couleur, epaisseur) = when {
                voie.kind == RoadKind.STREAM -> CARTE_EAU to 5f
                voie.kind.isTrail -> CARTE_CHEMIN to 8f
                voie.kind.code <= RoadKind.TERTIARY.code -> CARTE_GRANDE_VOIE to 18f
                else -> CARTE_PETITE_VOIE to 11f
            }
            trace(points)?.let { canvas.drawPath(it, trait(couleur, epaisseur * k).apply { strokeJoin = Paint.Join.ROUND }) }
        }

        trace(m.path)?.let { canvas.drawPath(it, trait(ROUTE, 16f * k).apply { strokeJoin = Paint.Join.ROUND }) }
        if (m.rejoinPath.size >= 2) {
            trace(m.rejoinPath)?.let { canvas.drawPath(it, trait(FieldPalette.REJOIN, 12f * k).apply { strokeJoin = Paint.Join.ROUND }) }
        }
        for (poi in m.pois) {
            val plan = Geo.toTrackUpPlane(position, cap, poi.position)
            val x = cx + plan.x.toFloat() * echelle
            val y = cy - plan.y.toFloat() * echelle
            canvas.drawCircle(x, y, 10f * k, plein(FieldPalette.POI))
            canvas.drawCircle(x, y, 10f * k, trait(BLANC, 3f * k))
        }

        // Le coureur : une pastille sombre et une pointe blanche vers le haut — le cap.
        canvas.drawCircle(cx, cy, 18f * k, plein(FOND))
        canvas.drawPath(
            Path().apply {
                moveTo(cx, cy - 14f * k)
                lineTo(cx - 10f * k, cy + 6f * k)
                lineTo(cx + 10f * k, cy + 6f * k)
                close()
            },
            plein(BLANC),
        )
        canvas.restore()

        if (m.roads.isEmpty()) m.roadsMessage?.let { message(canvas, RectF(cadre.left, cadre.bottom - 50f * k, cadre.right, cadre.bottom), it, k) }
        c.suite?.let { etiquette(canvas, cadre.left + 10f * k, cadre.top + 8f * k, it, k) }
    }

    private fun message(canvas: Canvas, zone: RectF, texte: String, k: Float) {
        val corps = texte(18f * k, 0xFF4B5563.toInt(), Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        ajuster(corps, texte, zone.width() - 24f * k)
        canvas.drawText(texte, zone.centerX(), zone.centerY() + corps.textSize * 0.35f, corps)
    }

    /** « ↱ dans 2,3 km », dans un cartouche sombre qui se lit sur la carte comme sur le noir. */
    private fun etiquette(canvas: Canvas, x: Float, y: Float, suite: Suite, k: Float) {
        val corps = texte(22f * k, BLANC, Typeface.DEFAULT_BOLD)
        val hauteur = 36f * k
        val largeur = 44f * k + corps.measureText(suite.texte) + 14f * k
        val r = RectF(x, y, x + largeur, y + hauteur)
        canvas.drawRoundRect(r, 10f * k, 10f * k, plein(0xE6111315.toInt()))
        fleche(canvas, x + 22f * k, r.centerY(), hauteur * 0.66f, suite.virage, ROUTE, k)
        canvas.drawText(suite.texte, x + 44f * k, r.centerY() + corps.textSize * 0.36f, corps)
    }

    private fun bandeau(canvas: Canvas, zone: RectF, b: BandeauGuidage, k: Float) {
        b.cote?.let {
            chiffresDeCote(canvas, zone, it, k)
            return
        }
        val points = b.fenetre.points
        // Assez haut pour que la pastille du coureur reste entière quand il est au point bas.
        val bas = zone.bottom - 14f * k
        // De la place au-dessus de la crête pour la pastille du compteur et celles des POI.
        val haut = zone.top + 74f * k
        // Ce qui est écrit en haut du bandeau, que les noms de POI ne doivent pas recouvrir.
        val occupes = mutableListOf<RectF>()
        b.texte?.let {
            val corps = texte(22f * k, BLANC, Typeface.DEFAULT_BOLD).apply {
                textAlign = Paint.Align.RIGHT
                setShadowLayer(4f * k, 0f, 0f, FOND)
            }
            canvas.drawText(it, zone.right - 20f * k, zone.top + 30f * k, corps)
            occupes += RectF(zone.right - 24f * k - corps.measureText(it), zone.top + 8f * k, zone.right, zone.top + 36f * k)
        }
        if (points.size < 2) return
        val debut = b.fenetre.start
        val etendue = (b.fenetre.end - debut).coerceAtLeast(1.0)
        val altitudes = (b.fenetre.maxElevation - b.fenetre.minElevation).coerceAtLeast(1.0)
        fun x(d: Double) = zone.left + ((d - debut) / etendue * zone.width()).toFloat()
        fun y(e: Double) = bas - ((e - b.fenetre.minElevation) / altitudes * (bas - haut)).toFloat()
        // Le coureur, un cinquième de la portée après le bord gauche — au départ, la fenêtre
        // ne peut pas reculer et il y revient.
        val ici = b.position ?: points.first().distance
        val px = x(ici).coerceAtLeast(zone.left + 10f * k)
        val py = y(altitudeA(points, ici))

        // Comme sur le tableau de bord : derrière le coureur, la seule crête, sans aplat — ce
        // qui est roulé n'a plus à peser ; devant, l'aplat jaune de ce qui vient.
        val ligne = Path()
        points.forEachIndexed { i, p -> if (i == 0) ligne.moveTo(x(p.distance), y(p.elevation)) else ligne.lineTo(x(p.distance), y(p.elevation)) }
        val aplat = Path(ligne).apply {
            lineTo(x(points.last().distance), zone.bottom)
            lineTo(x(points.first().distance), zone.bottom)
            close()
        }
        canvas.save()
        canvas.clipRect(px, zone.top, zone.right, zone.bottom)
        canvas.drawPath(aplat, plein(SILHOUETTE))
        canvas.restore()
        canvas.drawPath(ligne, trait(BLANC, 3f * k).apply { strokeJoin = Paint.Join.ROUND })

        // Le kilométrage au compteur, toujours en haut du bandeau à l'aplomb du coureur, relié
        // à lui par un fil jaune : il ne monte et ne descend plus avec le relief.
        b.compteur?.let { km ->
            val corps = texte(20f * k, FOND, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
            val l = corps.measureText(km) + 16f * k
            val cx = px.coerceAtLeast(zone.left + 6f * k + l / 2)
            val r = RectF(cx - l / 2, zone.top + 8f * k, cx + l / 2, zone.top + 36f * k)
            canvas.drawLine(px, r.bottom, px, py, trait(POSITION, 3f * k).apply { strokeCap = Paint.Cap.BUTT })
            canvas.drawRoundRect(r, 8f * k, 8f * k, plein(POSITION))
            canvas.drawText(km, cx, r.bottom - 7f * k, corps)
            occupes += RectF(r.left - 4f * k, r.top, r.right + 4f * k, r.bottom)
        }

        // Les points d'intérêt devant soi, posés sur la crête comme sur le profil de côte. Un
        // nom qui toucherait un autre monte d'un cran ; s'il touche encore, il se tait et seule
        // sa pastille reste.
        val poiCorps = texte(14f * k, BLANC, Typeface.DEFAULT_BOLD).apply {
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f * k, 0f, 0f, FOND)
        }
        for ((d, nom) in b.pois.sortedBy { it.first }) {
            if (d < ici || d > b.fenetre.end) continue
            val qx = x(d)
            val qy = y(altitudeA(points, d))
            canvas.drawLine(qx, qy, qx, qy - 20f * k, trait(FieldPalette.POI, 3f * k))
            canvas.drawCircle(qx, qy - 24f * k, 7f * k, plein(FieldPalette.POI))
            canvas.drawCircle(qx, qy - 24f * k, 7f * k, trait(BLANC, 2.5f * k))
            val court = if (nom.length > 12) nom.take(11) + "…" else nom
            val demi = poiCorps.measureText(court) / 2 + 4f * k
            val cx = qx.coerceIn(zone.left + demi, zone.right - demi)
            for (cran in 0..1) {
                val base = qy - 37f * k - cran * 17f * k
                val place = RectF(cx - demi, base - 14f * k, cx + demi, base + 3f * k)
                if (occupes.none { RectF.intersects(it, place) }) {
                    occupes += place
                    canvas.drawText(court, cx, base, poiCorps)
                    break
                }
            }
        }

        canvas.drawCircle(px, py, 8f * k, plein(FOND))
        canvas.drawCircle(px, py, 8f * k, trait(BLANC, 3f * k))
    }

    /** L'altitude à [d] sur une suite de points de profil, interpolée. */
    private fun altitudeA(points: List<io.github.jmallus.guidage.core.ProfilePoint>, d: Double): Double {
        val i = points.indexOfFirst { it.distance >= d }
        if (i < 0) return points.last().elevation
        if (i == 0) return points.first().elevation
        val a = points[i - 1]
        val b = points[i]
        val t = if (b.distance > a.distance) (d - a.distance) / (b.distance - a.distance) else 0.0
        return a.elevation + (b.elevation - a.elevation) * t
    }

    /** Les chiffres de la côte, deux par rang, sous un filet qui les sépare du profil. */
    private fun chiffresDeCote(canvas: Canvas, zone: RectF, donnees: List<DonneeGuidage>, k: Float) {
        canvas.drawLine(zone.left + 20f * k, zone.top + 4f * k, zone.right - 20f * k, zone.top + 4f * k, trait(PISTE, 2f * k))
        val colonnes = 2
        val rangs = (donnees.size + colonnes - 1) / colonnes
        val largeur = zone.width() / colonnes
        val hauteur = (zone.height() - 8f * k) / rangs
        val libelle = texte(15f * k, LIBELLE, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        donnees.forEachIndexed { i, d ->
            val x = zone.left + largeur * (i % colonnes + 0.5f)
            val haut = zone.top + 8f * k + hauteur * (i / colonnes)
            canvas.drawText(d.libelle, x, haut + 22f * k, libelle)
            val valeur = texte(42f * k, d.couleur ?: BLANC, Typeface.DEFAULT_BOLD).apply {
                textAlign = Paint.Align.CENTER
                ajuster(this, d.valeur, largeur - 16f * k)
            }
            canvas.drawText(d.valeur, x, haut + 22f * k + valeur.textSize * 1.05f, valeur)
        }
    }

    /**
     * Une flèche de direction : une hampe qui monte, puis part à [angle] avec sa pointe.
     * C'est la même que celle du schéma de carrefour, en petit.
     */
    private fun fleche(canvas: Canvas, cx: Float, cy: Float, taille: Float, angle: Double, couleur: Int, k: Float) {
        val epaisseur = max(4f * k, taille * 0.16f)
        val bas = cy + taille / 2
        val coude = cy + taille * 0.08f
        val longueur = taille * 0.42f
        val fx = cx + dx(angle) * longueur
        val fy = coude + dy(angle) * longueur
        val p = Path().apply {
            moveTo(cx, bas)
            lineTo(cx, coude)
            lineTo(fx, fy)
        }
        canvas.drawPath(p, trait(couleur, epaisseur).apply { strokeJoin = Paint.Join.ROUND })
        pointe(canvas, fx, fy, angle, epaisseur * 2.4f, couleur)
    }

    /** La pointe d'une flèche dont le bout est en ([x], [y]) et qui file à [angle]. */
    private fun pointe(canvas: Canvas, x: Float, y: Float, angle: Double, taille: Float, couleur: Int) {
        val ux = dx(angle)
        val uy = dy(angle)
        // Perpendiculaire à la direction, pour les deux ailes.
        val px = -uy
        val py = ux
        val p = Path().apply {
            moveTo(x + ux * taille * 0.6f, y + uy * taille * 0.6f)
            lineTo(x - ux * taille * 0.4f + px * taille * 0.6f, y - uy * taille * 0.4f + py * taille * 0.6f)
            lineTo(x - ux * taille * 0.4f - px * taille * 0.6f, y - uy * taille * 0.4f - py * taille * 0.6f)
            close()
        }
        canvas.drawPath(p, plein(couleur))
    }

    /** Une direction à l'écran : 0° vers le haut, positif vers la droite. */
    private fun dx(angle: Double): Float = sin(Math.toRadians(angle)).toFloat()
    private fun dy(angle: Double): Float = -cos(Math.toRadians(angle)).toFloat()

    /** Rapetisse [paint] jusqu'à ce que [texte] tienne dans [largeur]. */
    private fun ajuster(paint: Paint, texte: String, largeur: Float) {
        val mesure = paint.measureText(texte)
        if (mesure > largeur && mesure > 0f) paint.textSize *= largeur / mesure
    }

    private fun texte(taille: Float, couleur: Int, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = taille
        color = couleur
        typeface = face
    }

    private fun plein(couleur: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur
        style = Paint.Style.FILL
    }

    private fun trait(couleur: Int, epaisseur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur
        style = Paint.Style.STROKE
        strokeWidth = epaisseur
        strokeCap = Paint.Cap.ROUND
    }
}
