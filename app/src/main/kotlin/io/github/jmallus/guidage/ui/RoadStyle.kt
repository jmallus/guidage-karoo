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
 * Le fond est noir et les voies blanches. C'est un renversement assumé : l'écran du Karoo
 * est transflectif, il réfléchit la lumière au lieu de s'éclairer, et un fond clair s'y lit
 * d'autant mieux qu'il fait jour — c'est pourquoi toutes les cartes de compteur en portent
 * un. Mais ce fond-ci n'est pas fait pour être lu comme une carte : il ne montre que le
 * voisinage immédiat de l'itinéraire, le reste s'éteignant vers le noir, et sur cette
 * poignée de voies le contraste maximal fait plus que la couleur.
 *
 * Les classes ne se distinguent donc plus à la teinte mais à la seule épaisseur, qui reste
 * exprimée en mètres de terrain. Les surfaces passent à des gris très sombres : elles ne
 * servent plus qu'à situer, sans disputer au tracé le peu de lumière disponible.
 */
object RoadStyle {

    /** Largeur de chaussée, en mètres. Sans objet pour les surfaces. */
    fun widthMeters(kind: RoadKind): Float = when (kind) {
        RoadKind.STREAM -> 4f
        RoadKind.WATER, RoadKind.FOREST, RoadKind.BUILT_UP, RoadKind.FARMLAND -> 0f
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
     * Couleur d'une voie : le blanc, pour toutes.
     *
     * Les classes portaient chacune sa teinte, comme sur une carte routière. Ce codage
     * supposait qu'on ait la carte entière sous les yeux et le temps de la lire ; ici le
     * fond ne montre plus qu'un couloir autour de l'itinéraire, où il n'y a jamais qu'une
     * poignée de voies. Leur rang se lit alors à l'épaisseur, et tout le contraste
     * disponible sert à les détacher du noir plutôt qu'à les distinguer entre elles.
     */
    fun color(kind: RoadKind, surface: RoadSurface): Int = when (kind) {
        // Trottoirs et escaliers restent sourds : ils forment le tiers du fichier et
        // n'intéressent ni le gravel ni le VTT, mais les effacer reviendrait à prétendre
        // qu'il n'y a rien là où il y a quelque chose.
        RoadKind.FOOTWAY, RoadKind.STEPS -> FAINT
        RoadKind.STREAM -> WATER_LINE
        RoadKind.WATER -> WATER_FILL
        RoadKind.FOREST -> FOREST_FILL
        RoadKind.BUILT_UP -> BUILT_UP_FILL
        RoadKind.FARMLAND -> FARMLAND_FILL
        else -> ROADWAY
    }

    /** Un trait discontinu pour ce qui n'est pas revêtu : c'est la convention des cartes. */
    fun isDashed(kind: RoadKind, surface: RoadSurface): Boolean = when (kind) {
        RoadKind.TRACK, RoadKind.PATH, RoadKind.BRIDLEWAY -> surface != RoadSurface.PAVED
        RoadKind.FOOTWAY, RoadKind.STEPS -> true
        else -> false
    }

    /** Fond de la carte : le noir, sur lequel les voies s'éteignent en s'écartant du tracé. */
    const val BACKGROUND = 0xFF000000.toInt()

    /** Encre des mentions portées sur la carte — échelle, messages — sur ce fond noir. */
    const val INK = 0xFFFFFFFF.toInt()

    /** Toutes les chaussées, du chemin à l'autoroute : seule l'épaisseur les sépare. */
    private const val ROADWAY = 0xFFFFFFFF.toInt()

    /**
     * Surfaces : des gris très sombres, à peine détachés du noir.
     *
     * Elles occupent de grandes plages derrière tout le reste. Éclaircies, elles feraient de
     * la carte un damier gris sur lequel les voies blanches perdraient leur détachement ;
     * c'est lui, et non la couleur, qui porte désormais toute la lecture.
     *
     * L'eau garde seule une pointe de bleu : c'est la seule surface qu'on cherche des yeux,
     * un pont ou un gué ne se devinant pas.
     */
    private const val WATER_FILL = 0xFF12222E.toInt()
    private const val WATER_LINE = 0xFF3E6B87.toInt()
    private const val FOREST_FILL = 0xFF14200F.toInt()
    private const val BUILT_UP_FILL = 0xFF1E1E1E.toInt()

    /** Cultures et prairies : la plus étendue des surfaces, donc la plus discrète. */
    private const val FARMLAND_FILL = 0xFF10160C.toInt()

    /** Trottoirs et escaliers : présents, mais sans prendre la lumière. */
    private const val FAINT = 0xFF585C60.toInt()
}
