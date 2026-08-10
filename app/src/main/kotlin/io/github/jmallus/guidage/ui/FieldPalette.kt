package io.github.jmallus.guidage.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

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

    private const val DOWNHILL = 0xFF4FA3D1.toInt()
    private const val FLAT = 0xFF9AA7AD.toInt()
    private const val EASY = 0xFF7CB342.toInt()
    private const val MODERATE = 0xFFF9A825.toInt()
    private const val HARD = 0xFFEF6C00.toInt()
    private const val VERY_HARD = 0xFFD32F2F.toInt()
    private const val EXTREME = 0xFF7B1FA2.toInt()

    /** Couleur associée à une pente en %. */
    fun gradeColor(grade: Double): Int = when {
        grade <= -1.0 -> DOWNHILL
        grade < 1.0 -> FLAT
        grade < 3.0 -> EASY
        grade < 6.0 -> MODERATE
        grade < 9.0 -> HARD
        grade < 12.0 -> VERY_HARD
        else -> EXTREME
    }

    /** Version translucide d'une couleur, pour les aplats de fond. */
    fun translucent(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Couleur neutre utilisée quand la coloration par pente est désactivée. */
    const val NEUTRAL = 0xFF78909C.toInt()
}
