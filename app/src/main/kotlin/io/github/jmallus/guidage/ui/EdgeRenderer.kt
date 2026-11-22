package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min

/** Une case du Tableau Edge : libellé en haut à gauche, valeur, unité collée à droite. */
data class EdgeCase(
    val libelle: String,
    val valeur: String,
    val unite: String? = null,
    /** Aplat de zone ou de pente ; null, la case reste sur le fond du tableau. */
    val fond: Int? = null,
    /** « Z3 », en haut à droite. */
    val badge: String? = null,
    /** Valeur calée à droite, comme la pente en bout de rangée sur l'Edge. */
    val aDroite: Boolean = false,
    /** Angle du virage à venir (0 tout droit, positif à droite) : une flèche avant la valeur. */
    val virage: Double? = null,
)

data class EdgeModel(
    val vitesse: String,
    val vitesseMoyenne: String,
    val uniteVitesse: String,
    val puissance: EdgeCase,
    val puissanceMoyenne: String,
    val coeur: EdgeCase,
    val cadence: EdgeCase,
    /** Distance, D+ restant, pente ; restant, prochain virage, arrivée. */
    val bas: List<EdgeCase>,
    /** Non nul dans une côte : le profil de montée prend la place des six cases du bas. */
    val profilCote: ProfileFieldModel?,
)

/**
 * Le Tableau Edge : le champ « Écran Edge » que le coureur s'est fait sur son Garmin, repris
 * pour le Karoo.
 *
 * La grille est celle de l'Edge — cellules espacées, un filet court au milieu de chaque
 * espace, sans jonction — et ses proportions aussi : vitesse 5, bloc central 8, deux rangées
 * de 4. La barre d'état de l'Edge (chrono, heure, batterie) n'est pas reprise : le Karoo
 * affiche déjà la sienne au-dessus des champs. Les chiffres sont en Saira Condensed
 * ExtraBold, la police embarquée par le champ Garmin ; les couleurs de zone sont celles du
 * reste de l'extension.
 *
 * Deux styles : clair, le fond blanc cassé de l'Edge, et sombre, le fond du Karoo. Le clair
 * pose son propre fond, puisque ses encres en dépendent. Le sombre n'en pose aucun et laisse
 * le Karoo mettre le sien, comme tous les autres champs : peint ici, son gris ne tombait pas
 * sur le noir de l'appareil, et le champ se détachait de l'écran comme une carte posée dessus.
 */
object EdgeRenderer {

    /** [fond] null : le champ reste transparent, le Karoo pose le sien. */
    private class Style(val fond: Int?, val encre: Int, val libelle: Int, val filet: Int, val muet: Int)

    private val CLAIR = Style(
        fond = 0xFFE8E8E0.toInt(),
        encre = 0xFF000000.toInt(),
        libelle = 0xFF000000.toInt(),
        filet = 0xFF000000.toInt(),
        muet = 0xFF606060.toInt(),
    )

    private val SOMBRE = Style(
        fond = null,
        encre = 0xFFFFFFFF.toInt(),
        libelle = KarooColors.POWDER_BLUE,
        filet = 0xFF4A5459.toInt(),
        muet = 0xFF808080.toInt(),
    )

    fun render(context: Context, width: Int, height: Int, model: EdgeModel, sombre: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), width.toFloat(), height.toFloat(), model, sombre)
        return bitmap
    }

    fun draw(context: Context, canvas: Canvas, w: Float, h: Float, model: EdgeModel, sombre: Boolean) {
        val style = if (sombre) SOMBRE else CLAIR
        val d = Dessin(canvas, style, chiffres(context), min(w / LARGEUR_REF, h / HAUTEUR_REF))
        style.fond?.let { canvas.drawColor(it) }

        val k = d.k
        val pad = PAD * k
        val gap = GAP * k
        val gauche = pad
        val droite = w - pad
        val largeur = droite - gauche

        // Hauteurs relatives de l'Edge : vitesse 5, bloc central 8, deux rangées basses de 4.
        val dispo = h - 2 * pad - 3 * gap
        val hVitesse = dispo * 5 / 21
        val hCentre = dispo * 8 / 21
        val hBas = dispo * 4 / 21
        var y = pad

        // Vitesse vedette, et à sa droite la colonne « VITESSE · MOY · km/h ».
        val largeurCote = largeur * 38 / 100
        val largeurVitesse = largeur - largeurCote - gap
        d.valeur(
            model.vitesse, RectF(gauche + INSET_X * k, y, gauche + largeurVitesse - INSET_X * k, y + hVitesse),
            156f * k, style.encre, aDroite = false,
        )
        d.filetV(gauche + largeurVitesse, y, y + hVitesse)
        val xCote = gauche + largeurVitesse + gap + INSET_X * k
        d.libelle(context.getString(R.string.edge_label_speed), xCote, y + hVitesse * 0.26f, style.libelle)
        d.moyenne(model.vitesseMoyenne, null, xCote, y + hVitesse * 0.70f, 62f * k, style.encre, droite - INSET_X * k)
        d.libelle(model.uniteVitesse, xCote, y + hVitesse * 0.90f, style.encre)
        y += hVitesse
        d.filetH(y, gauche, droite)
        y += gap

        // Bloc central : la puissance 3 s et sa moyenne, la FC au-dessus de la cadence.
        val largeurPuissance = largeur * 59 / 100
        val puissance = RectF(gauche, y, gauche + largeurPuissance, y + hCentre)
        d.case(model.puissance, puissance, 170f * k, reserveBas = 48f * k)
        val encrePuissance = model.puissance.fond?.let { Contrast.bestTextColor(it) } ?: style.encre
        d.moyenne(model.puissanceMoyenne, "W", puissance.left + INSET_X * k, puissance.bottom - 12f * k, 40f * k, encrePuissance, puissance.right - INSET_X * k)
        d.filetV(puissance.right, y, y + hCentre)
        val xColonne = puissance.right + gap
        val haut = (hCentre - gap) / 2
        d.case(model.coeur, RectF(xColonne, y, droite, y + haut), 74f * k)
        d.filetH(y + haut, xColonne, droite)
        d.case(model.cadence, RectF(xColonne, y + haut + gap, droite, y + hCentre), 74f * k)
        y += hCentre
        d.filetH(y, gauche, droite)
        y += gap

        val profil = model.profilCote
        if (profil != null) {
            // Le profil de montée de l'extension, tel quel, sur toute la largeur : c'est le
            // même dessin que le champ « Profil de montée » et le bandeau du tableau de bord.
            ProfileRenderer.draw(canvas, RectF(0f, y, w, h - pad), profil, FieldPalette.of(sombre))
            return
        }
        val rangees = model.bas.chunked(3)
        rangees.forEachIndexed { i, rangee ->
            val bas = if (i == rangees.lastIndex) h - pad else y + hBas
            val largeurCase = (largeur - gap * (rangee.size - 1)) / rangee.size
            rangee.forEachIndexed { j, case ->
                val x = gauche + j * (largeurCase + gap)
                d.case(case, RectF(x, y, if (j == rangee.lastIndex) droite else x + largeurCase, bas), 62f * k)
                if (j < rangee.lastIndex) d.filetV(x + largeurCase, y, bas)
            }
            if (i < rangees.lastIndex) {
                d.filetH(bas, gauche, droite)
                y = bas + gap
            }
        }
    }

    private class Dessin(val canvas: Canvas, val style: Style, val police: Typeface, val k: Float) {

        private val texte = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trait = Paint().apply { color = this@Dessin.style.filet }
        private val bornes = Rect()

        /** Filet vertical au milieu de l'espace qui commence en [x]. */
        fun filetV(x: Float, haut: Float, bas: Float) {
            val e = max(FILET * k, 1f)
            val cx = x + GAP * k / 2
            canvas.drawRect(cx - e / 2, haut, cx + e / 2, bas, trait)
        }

        /** Filet horizontal au milieu de l'espace qui commence en [y]. */
        fun filetH(y: Float, gauche: Float, droite: Float) {
            val e = max(FILET * k, 1f)
            val cy = y + GAP * k / 2
            canvas.drawRect(gauche, cy - e / 2, droite, cy + e / 2, trait)
        }

        fun libelle(t: String, x: Float, ligne: Float, couleur: Int, aDroite: Boolean = false) {
            texte.typeface = LIBELLE
            texte.textSize = TAILLE_LIBELLE * k
            texte.color = couleur
            texte.textAlign = if (aDroite) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(t, x, ligne, texte)
            texte.textAlign = Paint.Align.LEFT
        }

        /** « MOY 27,9 » : le mot en petit, le nombre en chiffres Saira, l'unité éventuelle. */
        fun moyenne(valeur: String, unite: String?, x: Float, ligne: Float, taille: Float, couleur: Int, limite: Float) {
            libelle(MOY, x, ligne, couleur)
            texte.typeface = LIBELLE
            texte.textSize = TAILLE_LIBELLE * k
            var cx = x + texte.measureText("$MOY ")
            val largeurUnite = unite?.let { texte.measureText(it) + UNIT_GAP * k } ?: 0f
            texte.typeface = police
            texte.textSize = taille
            while (texte.textSize > 8f && cx + texte.measureText(valeur) + largeurUnite > limite) texte.textSize -= 1f
            texte.color = couleur
            canvas.drawText(valeur, cx, ligne, texte)
            cx += texte.measureText(valeur) + UNIT_GAP * k
            if (unite != null) libelle(unite, cx, ligne, couleur)
        }

        /**
         * La valeur la plus grande qui tienne dans [zone], centrée en hauteur, sans dépasser
         * [tailleMax] : une case haute ne grossit pas ses chiffres au-delà de la planche.
         */
        fun valeur(
            t: String, zone: RectF, tailleMax: Float, couleur: Int, aDroite: Boolean,
            unite: String? = null,
        ): Float {
            texte.typeface = LIBELLE
            texte.textSize = TAILLE_LIBELLE * k
            val largeurUnite = unite?.let { texte.measureText(it) + UNIT_GAP * k } ?: 0f
            texte.typeface = police
            texte.textSize = 100f
            // L'encre va du haut des chiffres au bas de la virgule, qui descend sous la ligne.
            // Mesurée sur les chiffres seuls, une valeur qui remplit sa case — la vitesse, sur
            // l'appareil — posait sa virgule sur le filet du dessous. La descente est réservée
            // même sans virgule, pour que « 40 » et « 40,1 » restent à la même hauteur.
            texte.getTextBounds(GABARIT, 0, GABARIT.length, bornes)
            val hautParPoint = -bornes.top / 100f
            val encreParPoint = (bornes.bottom - bornes.top) / 100f
            texte.textSize = min(tailleMax, zone.height() / encreParPoint)
            while (texte.textSize > 8f && texte.measureText(t) + largeurUnite > zone.width()) texte.textSize -= 1f
            val ligne = zone.centerY() - texte.textSize * encreParPoint / 2 + texte.textSize * hautParPoint
            texte.color = if (t == VIDE) style.muet else couleur
            val largeurValeur = texte.measureText(t)
            val x = if (aDroite) zone.right - largeurUnite - largeurValeur else zone.left
            canvas.drawText(t, x, ligne, texte)
            if (unite != null) libelle(unite, x + largeurValeur + UNIT_GAP * k, ligne, couleur)
            return ligne
        }

        /** Une case entière : aplat, libellé et badge, valeur et unité. */
        fun case(c: EdgeCase, r: RectF, tailleMax: Float, reserveBas: Float = 0f) {
            val fond = c.fond
            if (fond != null) canvas.drawRect(r, Paint().apply { color = fond })
            val encre = fond?.let { Contrast.bestTextColor(it) } ?: style.encre
            val encreLibelle = fond?.let { encre } ?: style.libelle
            val gauche = r.left + INSET_X * k
            val droite = r.right - INSET_X * k
            val ligneLibelle = r.top + LIGNE_LIBELLE * k
            libelle(c.libelle, gauche, ligneLibelle, encreLibelle)
            c.badge?.let { libelle(it, droite, ligneLibelle, encreLibelle, aDroite = true) }
            var zone = RectF(gauche, ligneLibelle + 8f * k, droite, r.bottom - INSET_Y * k - reserveBas)
            c.virage?.let { angle ->
                // La flèche du tableau de guidage, à la hauteur des chiffres, puis la distance
                // dans ce qui reste de la case.
                val cote = min(zone.height(), zone.width() * FLECHE_PART)
                GuidageRenderer.fleche(canvas, zone.left + cote / 2, zone.centerY(), cote * 0.9f, angle, encre, k)
                zone = RectF(zone.left + cote + UNIT_GAP * k, zone.top, zone.right, zone.bottom)
            }
            valeur(c.valeur, zone, tailleMax, encre, c.aDroite, c.unite)
        }
    }

    /** La police des chiffres, chargée une fois : celle du champ Garmin. */
    private var saira: Typeface? = null

    private fun chiffres(context: Context): Typeface =
        saira ?: (runCatching { context.resources.getFont(R.font.saira_condensed_extrabold) }.getOrNull()
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)).also { saira = it }

    private val LIBELLE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** La planche de référence : le champ plein écran du Karoo 3. */
    private const val LARGEUR_REF = 478f
    private const val HAUTEUR_REF = 642f

    private const val PAD = 4f
    private const val GAP = 16f
    private const val FILET = 2f
    private const val INSET_X = 12f
    private const val INSET_Y = 6f
    private const val UNIT_GAP = 4f

    /** Part de la largeur d'une case que la flèche de virage peut prendre, au plus. */
    private const val FLECHE_PART = 0.4f
    private const val TAILLE_LIBELLE = 20f
    private const val LIGNE_LIBELLE = 26f
    private const val MOY = "MOY"
    private const val VIDE = "--"

    /** De quoi mesurer l'encre d'une valeur : la hauteur d'un chiffre, la descente d'une virgule. */
    private const val GABARIT = "0,"
}
