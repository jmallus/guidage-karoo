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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Une donnée du rang du haut : son libellé, sa valeur, et la couleur de sa zone. */
data class DonneeGuidage(val libelle: String, val valeur: String, val couleur: Int? = null)

/** Un virage de descente, sur sa carte. */
data class CarteVirage(
    /** −1 à gauche, +1 à droite. */
    val direction: Int,
    val distance: String,
    /** « épingle · R 9 m ». */
    val detail: String,
    /** « ≤ 22 km/h ». */
    val vitesse: String,
    val couleur: Int,
)

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

    /** Une descente à virages serrés : les trois prochains, chacun sur sa carte. */
    data class Descente(val titre: String, val cartes: List<CarteVirage>, val note: String) : CentreGuidage

    /** Une côte, montée ou toute proche : le bandeau de côte du tableau de bord, en grand. */
    data class Montee(val profil: ProfileFieldModel, val suite: Suite?) : CentreGuidage

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
 * **une seule** chose, choisie par urgence : le carrefour qui approche, sinon la descente qui
 * tourne, sinon la côte, sinon la carte. L'œil n'a jamais à chercher où regarder — le milieu
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
    const val CARTE_GRANDE_VOIE = 0xFFFFFFFF.toInt()
    const val CARTE_PETITE_VOIE = 0xFFFFFFFF.toInt()
    const val CARTE_CHEMIN = 0xFFD1C9B5.toInt()
    const val CARTE_EAU = 0xFFA9C8E8.toInt()
    const val BLANC = 0xFFFFFFFF.toInt()
    const val CARTE_ROUGE = 0xFF7F1D1D.toInt()
    const val CARTE_AMBRE = 0xFF713F12.toInt()
    const val CARTE_VERTE = 0xFF14532D.toInt()

    /** Part de la hauteur tenue par le rang des chiffres, et où commence le bandeau du bas. */
    private const val HAUT = 0.15f
    private const val BAS = 0.735f

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
            is CentreGuidage.Descente -> descente(canvas, centre, c, k)
            is CentreGuidage.Montee -> montee(canvas, centre, c, model.palette, k)
            is CentreGuidage.Carte -> carte(canvas, centre, c, model.palette, k)
        }
        bandeau(canvas, RectF(0f, h * BAS, w, h), model.bandeau, k)
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

    private fun descente(canvas: Canvas, zone: RectF, c: CentreGuidage.Descente, k: Float) {
        val marge = 20f * k
        canvas.drawText(c.titre, zone.left + marge, zone.top + 40f * k, texte(30f * k, BLANC, Typeface.DEFAULT_BOLD))

        val note = texte(15f * k, LIBELLE, Typeface.DEFAULT)
        val lignes = c.note.split('\n')
        val hautNote = zone.bottom - 10f * k - (lignes.size - 1) * 19f * k
        lignes.forEachIndexed { i, l -> canvas.drawText(l, zone.left + marge, hautNote + i * 19f * k, note) }

        val haut = zone.top + 56f * k
        val bas = hautNote - 24f * k
        val ecart = 10f * k
        val hauteur = ((bas - haut) - ecart * 2) / 3
        c.cartes.take(3).forEachIndexed { i, carte ->
            val r = RectF(zone.left + marge, haut + i * (hauteur + ecart), zone.right - marge, haut + i * (hauteur + ecart) + hauteur)
            canvas.drawRoundRect(r, 14f * k, 14f * k, plein(carte.couleur))
            val milieu = r.centerY()
            fleche(canvas, r.left + 34f * k, milieu, min(hauteur * 0.62f, 44f * k), carte.direction * 90.0, BLANC, k)
            val grand = texte(min(32f * k, hauteur * 0.42f), BLANC, Typeface.DEFAULT_BOLD)
            canvas.drawText(carte.distance, r.left + 68f * k, milieu + grand.textSize * 0.35f, grand)
            val vitesse = texte(min(30f * k, hauteur * 0.4f), BLANC, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText(carte.vitesse, r.right - 16f * k, milieu - 2f * k, vitesse)
            val detail = texte(min(17f * k, hauteur * 0.24f), 0xFFE5E7EB.toInt(), Typeface.DEFAULT).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText(carte.detail, r.right - 16f * k, milieu + detail.textSize + 4f * k, detail)
        }
    }

    private fun montee(canvas: Canvas, zone: RectF, c: CentreGuidage.Montee, palette: Palette, k: Float) {
        val suite = c.suite
        val bas = if (suite != null) zone.bottom - 46f * k else zone.bottom
        ProfileRenderer.draw(canvas, RectF(zone.left, zone.top + 6f * k, zone.right, bas), c.profil, palette)
        if (suite != null) etiquette(canvas, zone.left + 20f * k, zone.bottom - 40f * k, suite, k)
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
        val bas = zone.bottom - 6f * k
        val haut = zone.top + 46f * k
        if (points.size >= 2) {
            val debut = b.fenetre.start
            val etendue = (b.fenetre.end - debut).coerceAtLeast(1.0)
            val altitudes = (b.fenetre.maxElevation - b.fenetre.minElevation).coerceAtLeast(1.0)
            fun x(d: Double) = zone.left + ((d - debut) / etendue * zone.width()).toFloat()
            fun y(e: Double) = bas - ((e - b.fenetre.minElevation) / altitudes * (bas - haut)).toFloat()
            val ligne = Path()
            points.forEachIndexed { i, p -> if (i == 0) ligne.moveTo(x(p.distance), y(p.elevation)) else ligne.lineTo(x(p.distance), y(p.elevation)) }
            val aplat = Path(ligne).apply {
                lineTo(x(points.last().distance), zone.bottom)
                lineTo(x(points.first().distance), zone.bottom)
                close()
            }
            canvas.drawPath(aplat, plein(SILHOUETTE))
            canvas.drawPath(ligne, trait(BLANC, 3f * k).apply { strokeJoin = Paint.Join.ROUND })
            // Le coureur, au bord gauche : la fenêtre commence sous ses roues.
            val px = x(points.first().distance) + 8f * k
            val py = y(points.first().elevation)
            canvas.drawCircle(px, py, 8f * k, plein(FOND))
            canvas.drawCircle(px, py, 8f * k, trait(BLANC, 3f * k))
        }
        b.texte?.let {
            val corps = texte(22f * k, BLANC, Typeface.DEFAULT_BOLD).apply { setShadowLayer(4f * k, 0f, 0f, FOND) }
            canvas.drawText(it, zone.left + 20f * k, zone.top + 32f * k, corps)
        }
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
