package io.github.jmallus.guidage.core

import kotlin.math.abs
import kotlin.math.pow

/**
 * Contraste perceptuel APCA-W3 (SAPC-0.98G-4g).
 *
 * Sert à décider si une valeur posée sur un aplat de couleur doit s'écrire en blanc ou
 * en noir. Le rapport de contraste WCAG 2 ne convient pas ici : il juge quasi identiques
 * un jaune vif et un orange soutenu, alors que l'un appelle du texte noir et l'autre du
 * blanc. APCA sépare correctement les deux.
 *
 * Le résultat est un « Lc » : positif quand le texte est plus sombre que le fond, négatif
 * dans l'autre sens. |Lc| ≥ 45 est le minimum pour du gros texte.
 *
 * Algorithme repris de https://github.com/Myndex/SAPC-APCA (v0.1.7), via l'implémentation
 * Kotlin de Barberfish (jpweytjens/barberfish, Apache 2.0).
 */
object Contrast {

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** Contraste perceptuel entre une couleur de texte et une couleur de fond. */
    fun apca(text: Int, background: Int): Double {
        val textLuminance = softClamp(luminance(text))
        val backgroundLuminance = softClamp(luminance(background))
        if (abs(backgroundLuminance - textLuminance) < DIFFERENCE_FLOOR) return 0.0

        val sapc = if (textLuminance < backgroundLuminance) {
            // Polarité normale : texte sombre sur fond clair.
            (backgroundLuminance.pow(0.56) - textLuminance.pow(0.57)) * SCALE
        } else {
            // Polarité inverse : texte clair sur fond sombre.
            (backgroundLuminance.pow(0.65) - textLuminance.pow(0.62)) * SCALE
        }

        return when {
            abs(sapc) < CLIP_THRESHOLD -> 0.0
            sapc > 0 -> (sapc - OFFSET) * 100
            else -> (sapc + OFFSET) * 100
        }
    }

    /**
     * Du blanc ou du noir, la couleur de texte la plus lisible sur ce fond.
     *
     * C'est ce qui permet d'écrire en noir sur le jaune de la zone 3 sans avoir à
     * inventorier les cas à la main.
     */
    fun bestTextColor(background: Int): Int =
        if (abs(apca(WHITE, background)) >= abs(apca(BLACK, background))) WHITE else BLACK

    private fun luminance(color: Int): Double =
        channel(color, 16).pow(EXPONENT) * 0.2126729 +
            channel(color, 8).pow(EXPONENT) * 0.7151522 +
            channel(color, 0).pow(EXPONENT) * 0.0721750

    private fun channel(color: Int, shift: Int): Double = ((color shr shift) and 0xFF) / 255.0

    /** Adoucit les niveaux proches du noir, où la formule diverge. */
    private fun softClamp(y: Double): Double =
        if (y < BLACK_THRESHOLD) y + (BLACK_THRESHOLD - y).pow(1.414) else y

    private const val EXPONENT = 2.4
    private const val BLACK_THRESHOLD = 0.022
    private const val DIFFERENCE_FLOOR = 0.0005
    private const val CLIP_THRESHOLD = 0.1
    private const val OFFSET = 0.027
    private const val SCALE = 1.14
}
