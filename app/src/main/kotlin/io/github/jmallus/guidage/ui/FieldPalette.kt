package io.github.jmallus.guidage.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import io.github.jmallus.guidage.core.Zones

/**
 * Couleurs des champs dessinés.
 *
 * Le Karoo bascule entre thème clair et sombre ; un texte presque noir devient invisible
 * sur fond noir. Les teintes de texte et de trait sont donc choisies d'après le mode
 * courant, seules les couleurs de pente restant identiques dans les deux cas.
 */
data class Palette(
    val textPrimary: Int,
    val textSecondary: Int,
    val outline: Int,
    val track: Int,
    val position: Int,
    val routeLine: Int,
    val routeOutline: Int,
    /** Teinte des icônes de champ, sur fond neutre. */
    val iconTint: Int,
)

/**
 * Les couleurs du système visuel de Hammerhead, avec le sens qu'il leur donne.
 *
 * Ce ne sont pas des goûts mais un vocabulaire : sur le Karoo, le jaune veut dire itinéraire
 * et le vert vif donnée vive, dans l'extension comme dans les champs natifs. S'en écarter,
 * c'est dire autre chose que ce qu'on croit — un tracé bleu, dans cette langue, annonce une
 * courbe d'altitude ou une côte.
 *
 * Les valeurs sont relevées sur les planches exportées du fichier, à quelques unités près.
 * Elles sont rassemblées ici, nommées comme dans le fichier, pour que la correction d'un
 * relevé se fasse en un seul endroit.
 */
object KarooColors {

    /** Libellés **et icônes** de champ. Le fichier l'impose, sans alternative. */
    const val POWDER_BLUE = 0xFFA8BFCC.toInt()

    /** Courbes d'altitude, quand on veut les distinguer d'un autre tracé. */
    const val AEGEAN_BLUE = 0xFF2C4A5E.toInt()

    /** Itinéraire, passé comme à venir. */
    const val LEMON_YELLOW = 0xFFF2D600.toInt()

    /** État d'erreur. Le fichier l'impose. */
    const val UI_RED = 0xFFF2554E.toInt()

    /** Tours. */
    const val UI_PURPLE = 0xFF8E2FC4.toInt()

    /** Donnée vive : ce qui vient d'un capteur, à l'instant. */
    const val HIGH_VIS_GREEN = 0xFF00E515.toInt()

    /** Côtes. */
    const val HIGH_VIS_BLUE = 0xFF0092DC.toInt()

    /** Graphique jamais alimenté — capteur absent, par opposition à capteur muet. */
    const val NEVER_POPULATED = 0xFF808080.toInt()

    /** Métadonnées : le blanc, réservé à ce qui décrit la donnée sans être elle. */
    const val METADATA = 0xFFFFFFFF.toInt()
}

object FieldPalette {

    /**
     * Point d'intérêt : le magenta, seule teinte que rien d'autre ne revendique.
     *
     * Il a porté du bleu, puis le violet du Karoo, puis du blanc. Chacune de ces teintes
     * entrait en conflit avec un sens déjà pris — le bleu est celui du tracé, le violet celui
     * des tours dans le système de Hammerhead, le blanc celui de la position. Le magenta n'est
     * revendiqué par rien, et il se voit sur le crème de la carte comme sur les verts de la
     * campagne.
     *
     * Il vit ici, et non dans le rendu de la carte où il a été choisi, parce que le profil le
     * reprend : un point d'intérêt doit avoir la même couleur d'un champ à l'autre, sans quoi
     * rien ne dit au coureur que la pastille de la carte et le jalon du profil désignent la
     * même chose. Deux constantes finiraient par diverger.
     */
    const val POI = 0xFFE31C96.toInt()

    private val LIGHT = Palette(
        textPrimary = 0xFF11181C.toInt(),
        // Le Powder Blue est fait pour un fond noir ; sur du blanc il s'évanouit. Le thème
        // clair prend donc le même bleu assombri, qui garde la teinte en changeant de valeur.
        textSecondary = 0xFF43606F.toInt(),
        outline = 0xFF37474F.toInt(),
        track = 0xFFE0E4E6.toInt(),
        position = 0xFF1565C0.toInt(),
        routeLine = KarooColors.LEMON_YELLOW,
        routeOutline = 0xFF1A1A1A.toInt(),
        iconTint = 0xFF43606F.toInt(),
    )

    private val DARK = Palette(
        textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = KarooColors.POWDER_BLUE,
        outline = 0xFFECEFF1.toInt(),
        track = 0xFF37474F.toInt(),
        position = 0xFF64B5F6.toInt(),
        routeLine = KarooColors.LEMON_YELLOW,
        routeOutline = 0xFF1A1A1A.toInt(),
        iconTint = KarooColors.POWDER_BLUE,
    )

    /**
     * Palette adaptée au thème courant du Karoo.
     *
     * En cas de doute, le sombre est retenu : c'est le thème utilisé en sortie.
     */
    fun of(context: Context): Palette {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_NO) LIGHT else DARK
    }

    /**
     * Couleur associée à une pente en %, celle du Karoo.
     *
     * Une descente n'a pas de couleur dans cette palette : elle prend le gris neutre, une
     * teinte de plus n'apprenant rien sur une bande de trente pixels de haut.
     */
    fun gradeColor(grade: Double): Int = Zones.gradeColor(grade) ?: NEUTRAL

    /**
     * Rouge du Karoo quand il faut rejoindre l'itinéraire.
     *
     * C'est l'UI Red du système, que celui-ci réserve aux états d'erreur : être hors de
     * l'itinéraire en est un.
     */
    const val REJOIN = KarooColors.UI_RED

    /** Violet du Karoo pour la destination. */
    const val DESTINATION = 0xFFDDACFA.toInt()

    /** Version translucide d'une couleur, pour les aplats de fond. */
    fun translucent(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Couleur neutre utilisée quand la coloration par pente est désactivée. */
    const val NEUTRAL = 0xFF78909C.toInt()

    /**
     * Couleur d'un virage selon son rayon (m).
     *
     * Les seuils sont ceux qu'on prend au frein : en deçà de quinze mètres c'est une
     * épingle, en deçà de trente il faut ralentir franchement, en deçà de soixante on lève
     * du gaz. Au-delà, le virage se prend sans y penser et prend la teinte neutre plutôt
     * que le blanc — dans la langue du Karoo le blanc dit « métadonnée », et un virage
     * roulant n'a aucune raison d'être ce qui brille le plus à l'écran.
     */
    fun bendColor(radiusMeters: Double): Int = when {
        radiusMeters < 15.0 -> KarooColors.UI_RED
        radiusMeters < 30.0 -> Zones.POWER_COLORS[4]
        radiusMeters < 60.0 -> Zones.POWER_COLORS[2]
        else -> NEUTRAL
    }
}
