package io.github.jmallus.guidage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.core.ProfileWindow
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

/** Le bandeau du bas : la silhouette de ce qui vient, et ce qui reste. */
data class BandeauGuidage(val fenetre: ProfileWindow, val texte: String?)

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
    const val SILHOUETTE = 0xFF3F8F5F.toInt()
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

    private fun carte(canvas: Canvas, zone: RectF, c: CentreGuidage.Carte, palette: Palette, k: Float) {
        MapRenderer.draw(canvas, zone, c.carte, palette)
        c.suite?.let { etiquette(canvas, zone.left + 14f * k, zone.top + 14f * k, it, k) }
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
            canvas.drawCircle(x(points.first().distance) + 8f * k, y(points.first().elevation), 7f * k, plein(BLANC))
        }
        b.texte?.let {
            val corps = texte(22f * k, BLANC, Typeface.DEFAULT_BOLD).apply { setShadowLayer(4f * k, 0f, 0f, FOND) }
            canvas.drawText(it, zone.left + 20f * k, zone.top + 32f * k, corps)
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
