package io.github.jmallus.guidage.ui

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSurface

/**
 * Aspect des voies du fond de carte.
 *
 * La largeur est exprimée en mètres de terrain, pas en pixels : c'est ce qui fait qu'une
 * départementale reste une départementale à toutes les échelles, et qu'en dézoomant le
 * réseau s'affine au lieu de s'épaissir en bouillie.
 *
 * Le fond est clair, comme une carte papier, et non sombre. L'écran du Karoo est
 * transflectif : en plein soleil il ne s'éclaire pas davantage, il **réfléchit**. Un fond
 * noir n'y renvoie rien et l'écran devient un miroir ; un fond clair renvoie la lumière du
 * jour et se lit d'autant mieux qu'il y en a. C'est l'inverse de ce qu'on attend d'un écran
 * de téléphone, et c'est pour cela que toutes les cartes de compteur sont sur fond clair.
 *
 * Les voies restent sourdes malgré tout : le fond n'est pas là pour être regardé mais pour
 * situer le tracé, qui doit rester la chose la plus visible de l'écran.
 */
object RoadStyle {

    /** Largeur de chaussée, en mètres. */
    fun widthMeters(kind: RoadKind): Float = when (kind) {
        RoadKind.MOTORWAY -> 12f
        RoadKind.TRUNK -> 10f
        RoadKind.PRIMARY -> 8f
        RoadKind.SECONDARY -> 7f
        RoadKind.TERTIARY -> 6f
        RoadKind.UNCLASSIFIED -> 5f
        RoadKind.RESIDENTIAL -> 5f
        RoadKind.SERVICE -> 3.5f
        RoadKind.CYCLEWAY -> 2.5f
        RoadKind.TRACK -> 3f
        RoadKind.PATH -> 2f
        RoadKind.BRIDLEWAY -> 2f
        RoadKind.FOOTWAY -> 1.5f
        RoadKind.STEPS -> 1.5f
    }

    /**
     * Couleur d'une voie.
     *
     * Trottoirs et escaliers sont volontairement à peine détachés du fond : ils forment
     * le tiers du fichier et n'intéressent ni le gravel ni le VTT, mais les effacer
     * complètement reviendrait à prétendre qu'il n'y a rien là où il y a quelque chose.
     */
    fun color(kind: RoadKind, surface: RoadSurface): Int = when (kind) {
        RoadKind.MOTORWAY, RoadKind.TRUNK, RoadKind.PRIMARY, RoadKind.SECONDARY -> MAJOR
        RoadKind.TERTIARY, RoadKind.UNCLASSIFIED, RoadKind.RESIDENTIAL -> MINOR
        RoadKind.SERVICE -> SERVICE
        RoadKind.FOOTWAY, RoadKind.STEPS -> FAINT
        // Chemins et sentiers : ce que le coureur cherche, donc un ton un peu chaud qui
        // se distingue du gris des routes sans pour autant crier.
        RoadKind.TRACK, RoadKind.PATH, RoadKind.BRIDLEWAY, RoadKind.CYCLEWAY ->
            if (surface == RoadSurface.PAVED) MINOR else TRAIL
    }

    /** Un trait discontinu pour ce qui n'est pas revêtu : c'est la convention des cartes. */
    fun isDashed(kind: RoadKind, surface: RoadSurface): Boolean = when (kind) {
        RoadKind.TRACK, RoadKind.PATH, RoadKind.BRIDLEWAY -> surface != RoadSurface.PAVED
        RoadKind.FOOTWAY, RoadKind.STEPS -> true
        else -> false
    }

    /** Fond de la carte : le blanc cassé des cartes d'état-major, qui ne brûle pas les yeux. */
    const val BACKGROUND = 0xFFF2EFE8.toInt()

    /** Encre des mentions portées sur la carte — échelle, messages — sur ce fond clair. */
    const val INK = 0xFF2A2F33.toInt()

    private const val MAJOR = 0xFF5A6167.toInt()
    private const val MINOR = 0xFF868D93.toInt()
    private const val SERVICE = 0xFFAAB0B5.toInt()
    private const val TRAIL = 0xFF9A6B33.toInt()
    private const val FAINT = 0xFFD3CEC4.toInt()
}
