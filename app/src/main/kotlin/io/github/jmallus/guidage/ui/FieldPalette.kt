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

object FieldPalette {

    private val LIGHT = Palette(
        textPrimary = 0xFF11181C.toInt(),
        textSecondary = 0xFF5F6A6F.toInt(),
        outline = 0xFF37474F.toInt(),
        track = 0xFFE0E4E6.toInt(),
        position = 0xFF1565C0.toInt(),
        routeLine = 0xFFE6E24C.toInt(),
        routeOutline = 0xFF1A1A1A.toInt(),
        iconTint = ICON_TINT,
    )

    private val DARK = Palette(
        textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFFB0BEC5.toInt(),
        outline = 0xFFECEFF1.toInt(),
        track = 0xFF37474F.toInt(),
        position = 0xFF64B5F6.toInt(),
        routeLine = 0xFFE6E24C.toInt(),
        routeOutline = 0xFF1A1A1A.toInt(),
        iconTint = ICON_TINT,
    )

    /**
     * Vert des icônes de champ, repris de Barberfish.
     *
     * Il reste le même en thème clair comme en sombre : assez soutenu pour tenir sur du
     * blanc, assez lumineux pour tenir sur du noir.
     */
    private const val ICON_TINT = 0xFF31E09A.toInt()

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
     * Bleu des montées du Karoo, porté par le filet du profil.
     *
     * C'est la teinte que l'appareil emploie partout où il est question de grimper : la
     * reprendre évite d'avoir à réapprendre un code de couleur pour un seul écran.
     */
    const val CLIMB_LINE = 0xFF2086D8.toInt()

    /** Rouge du Karoo quand il faut rejoindre l'itinéraire. */
    const val REJOIN = 0xFFFC292B.toInt()

    /** Violet du Karoo pour la destination. */
    const val DESTINATION = 0xFFDDACFA.toInt()

    /** Version translucide d'une couleur, pour les aplats de fond. */
    fun translucent(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Couleur neutre utilisée quand la coloration par pente est désactivée. */
    const val NEUTRAL = 0xFF78909C.toInt()
}
