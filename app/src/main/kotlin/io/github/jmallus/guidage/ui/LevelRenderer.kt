package io.github.jmallus.guidage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.github.jmallus.guidage.core.Contrast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Une part de la barre des zones : sa couleur et ce qu'elle occupe, de 0 à 1. */
data class LevelSlice(val share: Float, val color: Int)

/**
 * Ce qu'affiche une case de bilan.
 *
 * Un grand chiffre, un petit à côté, et de quoi les nommer. Le petit n'est pas une décoration :
 * c'est lui qui donne son sens au grand — une fréquence moyenne ne dit rien tant qu'on n'a pas
 * le maximum de la sortie à côté d'elle, et une puissance moyenne ne dit pas si l'heure fut
 * lisse ou hachée tant que la normalisée ne la borde pas.
 */
data class LevelFieldModel(
    /** Le libellé de la case, écrit en capitales. */
    val label: String,
    /** Le grand chiffre, ou « -- » quand la mesure manque. */
    val value: String,
    /** Son unité, écrite plus petit sur la même ligne de base. */
    val unit: String? = null,
    /** Le petit chiffre, et ce qui le nomme. */
    val referenceLabel: String? = null,
    val referenceValue: String? = null,
    /** Aplat de fond, quand il y a un verdict à porter. */
    val background: Int? = null,
    /** La ligne du bas, quand la case a un mot à dire plutôt qu'un chiffre. */
    val caption: String? = null,
    /**
     * L'encre de ce mot, quand il porte lui-même un verdict.
     *
     * Ignorée sur un aplat : le contraste y est mesuré contre le fond, et poser une teinte
     * saturée sur une autre défait précisément ce que cette mesure garantit.
     */
    val captionColor: Int? = null,
    /** La barre empilée, pour la répartition par zone. */
    val slices: List<LevelSlice> = emptyList(),
    /** Message qui remplace tout quand rien n'est mesurable. */
    val emptyMessage: String? = null,
    /** L'icône posée à droite du libellé, à son encre. */
    @DrawableRes val icon: Int? = null,
)

/**
 * Dessine une case de bilan : ce que la sortie vaut depuis le départ.
 *
 * Les six cases partagent une seule grammaire, et c'est délibéré : posées côte à côte sur une
 * page, elles doivent se lire comme une famille et non comme six bricolages. Le libellé en
 * haut, le grand chiffre au milieu, la référence à sa droite, et le bas laissé au mot ou à la
 * barre quand il y en a un.
 *
 * Le Karoo publie déjà chacun de ces nombres, un par champ. Ce que cette case ajoute n'est pas
 * la mesure mais le **voisinage** : deux emplacements de page réunis en un, et la soustraction
 * faite pour le coureur plutôt que de tête.
 */
object LevelRenderer {

    fun render(context: Context, width: Int, height: Int, model: LevelFieldModel, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), model, palette)
        return bitmap
    }

    fun draw(context: Context, canvas: Canvas, area: RectF, model: LevelFieldModel, palette: Palette) {
        val width = area.width()
        val height = area.height()
        if (width <= 0f || height <= 0f) return

        val padding = (min(width, height) * 0.06f).coerceIn(3f, 10f)
        val left = area.left + padding
        val right = area.right - padding

        // L'aplat porte le verdict et décide des encres : sur un fond saturé, le gris des
        // libellés disparaît. Le contraste est mesuré, jamais supposé.
        val encre = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textPrimary
        val encreDouce = model.background?.let { Contrast.bestTextColor(it) } ?: palette.textSecondary
        model.background?.let { canvas.drawRect(area, Paint().apply { color = it }) }

        model.emptyMessage?.let { message ->
            val paint = paint(min(height * 0.20f, Lisibilite.corpsPourCapitale() * 1.6f), encreDouce, Lisibilite.LIBELLE)
            fit(paint, message, right - left)
            canvas.drawText(message, left, area.centerY() - (paint.descent() + paint.ascent()) / 2f, paint)
            return
        }

        // Le libellé ne descend jamais sous le plancher de lisibilité : à ce corps-là il est
        // écrit, ou il ne l'est pas du tout.
        //
        // Il plafonne aussi : sur une case haute, il grossissait avec elle jusqu'à égaler le
        // chiffre qu'il nomme, et l'œil lisait « ARRIVÉE » avant l'heure.
        val corpsLibelle = (height * LABEL_FRACTION).coerceIn(Lisibilite.corpsPourCapitale(), CORPS_TITRE_MAX)
        val libellePaint = paint(corpsLibelle, encreDouce, Lisibilite.LIBELLE)
        val hauteurLibelle = libellePaint.descent() - libellePaint.ascent()
        val libelleVisible = hauteurLibelle < height * 0.34f
        if (libelleVisible) {
            // L'icône suit le titre, à la manière des cases du tableau de bord. Quand les deux
            // ne tiennent pas ensemble au plancher, c'est elle qui part : le titre nomme la
            // case, l'icône ne fait que la faire reconnaître.
            // Un titre long rétrécit d'abord pour lui faire place, sans passer sous le plancher.
            val largeurA = { corps: Float ->
                paint(corps, 0, Lisibilite.LIBELLE).measureText(model.label) + corps * (ICON_RATIO + ICON_GAP_RATIO)
            }
            val corpsAvecIcone = min(libellePaint.textSize, libellePaint.textSize * (right - left) / largeurA(libellePaint.textSize))
            val icone = model.icon?.takeIf { corpsAvecIcone >= Lisibilite.corpsPourCapitale() }
                ?.let { ContextCompat.getDrawable(context, it) }
            if (icone != null) libellePaint.textSize = corpsAvecIcone
            val tailleIcone = libellePaint.textSize * ICON_RATIO
            val placeIcone = tailleIcone + libellePaint.textSize * ICON_GAP_RATIO
            fit(libellePaint, model.label, right - left - if (icone != null) placeIcone else 0f)
            // Centré, quand le chiffre en dessous est calé à gauche : le titre nomme la case,
            // il n'est pas une ligne du bloc de chiffres, et six titres centrés font une
            // page qui se lit comme celles du Karoo.
            val largeurTitre = libellePaint.measureText(model.label) + if (icone != null) placeIcone else 0f
            val centre = left + (right - left - largeurTitre) / 2f
            val ligneTitre = area.top + padding - libellePaint.ascent()
            canvas.drawText(model.label, centre, ligneTitre, libellePaint)
            icone?.let { dessin ->
                val x = centre + largeurTitre - tailleIcone
                // Centrée sur la hauteur des capitales, non sur la ligne : c'est à elles que
                // l'œil la compare.
                val milieu = ligneTitre - libellePaint.textSize * Lisibilite.CAPITALE / 2f
                dessin.setTint(encreDouce)
                dessin.setBounds(
                    x.roundToInt(),
                    (milieu - tailleIcone / 2f).roundToInt(),
                    (x + tailleIcone).roundToInt(),
                    (milieu + tailleIcone / 2f).roundToInt(),
                )
                dessin.draw(canvas)
            }
        }

        // Ce que le bas occupe, retiré avant de dimensionner le chiffre : le calculer après
        // reviendrait à dessiner un chiffre qui déborde, puis à s'en apercevoir à l'écran.
        val basHauteur = when {
            model.slices.isNotEmpty() -> hauteurBarre(height) + (height * BAR_GAP_FRACTION)
            model.caption != null -> corpsLigneDuBas(height) * 1.5f
            else -> 0f
        }
        val hautChiffre = area.top + padding + if (libelleVisible) hauteurLibelle else 0f
        val basChiffre = area.bottom - padding - basHauteur
        val placeChiffre = basChiffre - hautChiffre
        if (placeChiffre <= 0f) return

        // Deux dispositions, et la case prend celle qui donne le plus gros chiffre.
        //
        // Côte à côte, la référence borde le grand chiffre : c'est la bonne sur une case large
        // ou basse. Sur une case haute et étroite — six ou quatre par page —, c'est la largeur
        // qui bride le chiffre, et la hauteur gagnée restait vide au milieu de la case. La
        // référence passe alors dessous, sur une ligne à elle, et le chiffre prend la largeur.
        val largeur = right - left
        val plancher = Lisibilite.corpsPourCapitale()
        val coteACote = disposerCoteACote(model, largeur, placeChiffre, plancher)
        val empile = model.referenceValue?.let { disposerEmpile(model, largeur, placeChiffre, plancher) }
        if (empile != null && empile.corpsValeur > coteACote.corpsValeur * PREFERENCE_EMPILE) {
            // Le bloc empilé se centre dans sa place : calé en bas, il laissait sous le titre
            // d'une grande case un vide qui le détachait de ce qu'il nomme.
            val hauteurBloc = empile.corpsValeur * CAPITALE_CHIFFRES + empile.corpsReference * STACKED_LINE_RATIO
            val marge = ((placeChiffre - hauteurBloc) / 2f).coerceAtLeast(0f)
            dessinerEmpile(canvas, model, empile, left, basChiffre - marge, encre, encreDouce)
        } else {
            dessinerCoteACote(canvas, model, coteACote, left, basChiffre - placeChiffre * BASELINE_ABOVE_BOTTOM, encre, encreDouce)
        }

        model.caption?.let { texte ->
            val encreDuMot = model.captionColor?.takeIf { model.background == null } ?: encreDouce
            val paint = paint(corpsLigneDuBas(height), encreDuMot, Lisibilite.LIBELLE)
            fit(paint, texte, right - left)
            canvas.drawText(texte, left, area.bottom - padding - paint.descent(), paint)
        }

        if (model.slices.isNotEmpty()) {
            drawBar(canvas, model.slices, left, area.bottom - padding - hauteurBarre(height), right, area.bottom - padding)
        }
    }

    /** Ce que la case écrit à côté du grand chiffre, et à quels corps. */
    private class CoteACote(val corpsValeur: Float, val corpsReference: Float, val corpsReferenceLibelle: Float?)

    /** Ce que la case écrit sous le grand chiffre, et à quels corps. */
    private class Empile(val corpsValeur: Float, val corpsReference: Float, val corpsReferenceLibelle: Float?)

    /**
     * Le bloc côte à côte, au plus gros corps qui tient dans la largeur.
     *
     * Le libellé de la référence ne descend pas sous le plancher : écrit à six dixièmes de
     * millimètre, « NORMALISÉE » était invisible en roulant, et le chiffre qu'il nomme devenait
     * une énigme. Tenu au plancher, il pèse sur la largeur du bloc, d'où les quelques passes :
     * chaque réduction du chiffre laisse le libellé à la même taille.
     */
    private fun disposerCoteACote(model: LevelFieldModel, largeur: Float, place: Float, plancher: Float): CoteACote {
        var corps = place * VALUE_FRACTION
        var mesure = CoteACote(corps, corps * REFERENCE_RATIO, null)
        repeat(4) {
            val reference = max(corps * REFERENCE_RATIO, plancher)
            val libelle = model.referenceLabel?.let { max(reference * REFERENCE_LABEL_RATIO, plancher) }
            mesure = CoteACote(corps, reference, libelle)
            val bloc = largeurCoteACote(model, mesure)
            if (bloc <= largeur || bloc <= 0f) return mesure
            corps *= largeur / bloc
        }
        return mesure
    }

    private fun largeurCoteACote(model: LevelFieldModel, m: CoteACote): Float {
        val valeur = largeurValeur(model, m.corpsValeur)
        val reference = model.referenceValue ?: return valeur
        val colonne = max(
            paint(m.corpsReference, 0, VALUE_TYPEFACE).measureText(reference),
            model.referenceLabel?.let { t -> m.corpsReferenceLibelle?.let { paint(it, 0, Lisibilite.LIBELLE).measureText(t) } } ?: 0f,
        )
        return valeur + m.corpsValeur * GAP_RATIO + colonne
    }

    /**
     * Le grand chiffre sur toute la largeur, et sa référence sur une ligne dessous —
     * « MAX 180 » —, au corps moyen que la hauteur permet sans jamais passer sous le plancher.
     * Null quand la ligne de référence ne tient pas, même sans son libellé.
     */
    private fun disposerEmpile(model: LevelFieldModel, largeur: Float, place: Float, plancher: Float): Empile? {
        val reference = model.referenceValue ?: return null
        var corpsReference = (place * STACKED_REFERENCE_FRACTION).coerceIn(plancher, CORPS_REFERENCE_MAX)
        var corpsLibelle: Float? = model.referenceLabel?.let { max(corpsReference * STACKED_LABEL_RATIO, plancher) }
        fun ligne(): Float {
            val valeur = paint(corpsReference, 0, VALUE_TYPEFACE).measureText(reference)
            val titre = model.referenceLabel?.let { t -> corpsLibelle?.let { paint(it, 0, Lisibilite.LIBELLE).measureText("$t ") } } ?: 0f
            return valeur + titre
        }
        // La ligne rétrécit tout entière jusqu'au plancher avant de rien sacrifier : un
        // « 215 » sans « NORMALISÉE » devant ne dit plus ce qu'il mesure.
        repeat(4) {
            if (ligne() > largeur && corpsReference > plancher) {
                corpsReference = max(corpsReference * largeur / ligne(), plancher)
                corpsLibelle = corpsLibelle?.let { max(corpsReference * STACKED_LABEL_RATIO, plancher) }
            }
        }
        // Un libellé qui ne tient pas au plancher part ; le chiffre reste, s'il tient seul.
        if (ligne() > largeur) corpsLibelle = null
        if (ligne() > largeur) return null

        val hauteurReference = corpsReference * STACKED_LINE_RATIO
        val corpsValeur = min(
            (place - hauteurReference) * STACKED_VALUE_FRACTION,
            largeurValeur(model, 100f).takeIf { it > 0f }?.let { 100f * largeur / it } ?: Float.MAX_VALUE,
        )
        if (corpsValeur <= 0f) return null
        return Empile(corpsValeur, corpsReference, corpsLibelle)
    }

    private fun largeurValeur(model: LevelFieldModel, corps: Float): Float =
        paint(corps, 0, VALUE_TYPEFACE).measureText(model.value) +
            (model.unit?.let { paint(corps * UNIT_RATIO, 0, VALUE_TYPEFACE).measureText(it) } ?: 0f)

    private fun dessinerValeur(canvas: Canvas, model: LevelFieldModel, corps: Float, x0: Float, ligne: Float, encre: Int): Float {
        var x = x0
        val valeur = paint(corps, encre, VALUE_TYPEFACE)
        canvas.drawText(model.value, x, ligne, valeur)
        x += valeur.measureText(model.value)
        model.unit?.let {
            val unite = paint(corps * UNIT_RATIO, encre, VALUE_TYPEFACE)
            canvas.drawText(it, x, ligne, unite)
            x += unite.measureText(it)
        }
        return x
    }

    private fun dessinerCoteACote(
        canvas: Canvas,
        model: LevelFieldModel,
        m: CoteACote,
        left: Float,
        ligne: Float,
        encre: Int,
        encreDouce: Int,
    ) {
        val x = dessinerValeur(canvas, model, m.corpsValeur, left, ligne, encre)
        val reference = model.referenceValue ?: return
        val depart = x + m.corpsValeur * GAP_RATIO
        val referencePaint = paint(m.corpsReference, encre, VALUE_TYPEFACE)
        // La référence s'écrit sous son libellé, tous deux calés à gauche du même bord :
        // c'est ce qui la donne à lire comme une glose du grand chiffre, non comme une
        // seconde valeur de même rang.
        val titre = model.referenceLabel
        val corpsTitre = m.corpsReferenceLibelle
        if (titre != null && corpsTitre != null) {
            canvas.drawText(titre, depart, ligne - m.corpsReference * 1.05f, paint(corpsTitre, encreDouce, Lisibilite.LIBELLE))
        }
        canvas.drawText(reference, depart, ligne, referencePaint)
    }

    private fun dessinerEmpile(
        canvas: Canvas,
        model: LevelFieldModel,
        m: Empile,
        left: Float,
        bas: Float,
        encre: Int,
        encreDouce: Int,
    ) {
        val referencePaint = paint(m.corpsReference, encre, VALUE_TYPEFACE)
        val ligneReference = bas - referencePaint.descent()
        val ligneValeur = ligneReference - m.corpsReference * STACKED_LINE_RATIO
        dessinerValeur(canvas, model, m.corpsValeur, left, ligneValeur, encre)

        var x = left
        val titre = model.referenceLabel
        val corpsTitre = m.corpsReferenceLibelle
        if (titre != null && corpsTitre != null) {
            val titrePaint = paint(corpsTitre, encreDouce, Lisibilite.LIBELLE)
            canvas.drawText(titre, x, ligneReference, titrePaint)
            x += titrePaint.measureText("$titre ")
        }
        canvas.drawText(model.referenceValue ?: return, x, ligneReference, referencePaint)
    }

    /** La ligne du bas : jamais sous le plancher, jamais plus grosse que le titre. */
    private fun corpsLigneDuBas(height: Float): Float =
        (height * CAPTION_FRACTION).coerceIn(Lisibilite.corpsPourCapitale(), CORPS_TITRE_MAX)

    /** La barre des zones, épaisse assez pour se lire, sans devenir un aplat sur une grande case. */
    private fun hauteurBarre(height: Float): Float = min(height * BAR_FRACTION, BARRE_MAX)

    /**
     * La barre des zones : une part par zone, dans l'ordre des zones et non de leur poids.
     *
     * L'ordre est ce qui la rend lisible d'un coup d'œil — le vert à gauche, le rouge à
     * droite — et c'est la **place** du gros de la barre qui dit le niveau de la sortie, plus
     * vite qu'aucun chiffre. Une part trop mince pour se voir n'est pas dessinée plutôt que
     * réduite à un pixel qui mentirait sur sa taille.
     */
    private fun drawBar(canvas: Canvas, slices: List<LevelSlice>, left: Float, top: Float, right: Float, bottom: Float) {
        val largeur = right - left
        if (largeur <= 0f || bottom <= top) return
        val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        var x = left
        slices.forEach { part ->
            val bout = x + largeur * part.share.coerceIn(0f, 1f)
            if (bout - x >= MIN_SLICE_PIXELS) {
                fond.color = part.color
                canvas.drawRect(x, top, bout, bottom, fond)
            }
            x = bout
        }
    }

    private fun paint(size: Float, color: Int, typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = typeface
    }

    private fun fit(paint: Paint, text: String, maxWidth: Float) {
        val mesure = paint.measureText(text)
        if (mesure > maxWidth && maxWidth > 0f) {
            paint.textSize = (paint.textSize * maxWidth / mesure).coerceAtLeast(8f)
        }
    }

    private val VALUE_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Part de la hauteur donnée au libellé du haut. */
    private const val LABEL_FRACTION = 0.16f

    /** Le titre et la ligne du bas plafonnent à 1,3 mm de capitale : ils nomment, ils ne se lisent pas de loin. */
    private val CORPS_TITRE_MAX = Lisibilite.corpsPourCapitale(1.3f)

    /** La référence empilée plafonne à 2 mm : c'est une glose, elle ne doit pas rivaliser avec le chiffre. */
    private val CORPS_REFERENCE_MAX = Lisibilite.corpsPourCapitale(2.0f)

    /** Le libellé de la référence côte à côte, en part de son corps. */
    private const val REFERENCE_LABEL_RATIO = 0.62f

    /**
     * L'empilé : part de la hauteur donnée à la ligne de référence, libellé de celle-ci en part
     * de son corps, hauteur de ligne en corps, et part du reste donnée au grand chiffre.
     */
    private const val STACKED_REFERENCE_FRACTION = 0.2f
    private const val STACKED_LABEL_RATIO = 0.8f
    private const val STACKED_LINE_RATIO = 1.35f
    private const val STACKED_VALUE_FRACTION = 0.95f

    /** Hauteur des chiffres, en part du corps : ils n'ont ni jambage ni accent. */
    private const val CAPITALE_CHIFFRES = 0.72f

    /**
     * L'empilé ne l'emporte que s'il grossit franchement le chiffre : à gain égal, la
     * référence à côté se lit d'un coup d'œil avec lui.
     */
    private const val PREFERENCE_EMPILE = 1.15f

    /** Épaisseur maximale de la barre des zones (px). */
    private const val BARRE_MAX = 30f

    /** Part de la hauteur restante donnée au grand chiffre, et où pose sa ligne de base. */
    private const val VALUE_FRACTION = 0.72f
    private const val BASELINE_ABOVE_BOTTOM = 0.14f

    /** Tailles de l'unité et de la référence, en part de celle du grand chiffre. */
    private const val UNIT_RATIO = 0.46f
    private const val REFERENCE_RATIO = 0.44f

    /** Blanc entre le grand chiffre et sa référence, en part du corps du premier. */
    private const val GAP_RATIO = 0.34f

    /** Part de la hauteur donnée à la ligne du bas, et à la barre des zones. */
    private const val CAPTION_FRACTION = 0.13f
    private const val BAR_FRACTION = 0.16f
    private const val BAR_GAP_FRACTION = 0.06f

    /** L'icône du titre, en part de son corps, et le blanc qui l'en sépare. */
    private const val ICON_RATIO = 1.0f
    private const val ICON_GAP_RATIO = 0.3f

    /** En deçà, une part de barre n'est pas dessinée : elle mentirait sur sa taille. */
    private const val MIN_SLICE_PIXELS = 2f
}
