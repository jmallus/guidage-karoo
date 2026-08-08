package io.github.jmallus.guidage.ui

import android.graphics.Color

/**
 * Palette commune aux champs de données dessinés (profil et côte).
 *
 * Les champs Karoo sont affichés sur fond clair : le texte est sombre et les aplats
 * de pente reprennent le code couleur habituel des profils cyclistes.
 */
object FieldPalette {

    const val TEXT_PRIMARY = 0xFF11181C.toInt()
    const val TEXT_SECONDARY = 0xFF5F6A6F.toInt()
    const val OUTLINE = 0xFF37474F.toInt()
    const val TRACK = 0xFFE0E4E6.toInt()
    const val POSITION = 0xFF1565C0.toInt()

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
