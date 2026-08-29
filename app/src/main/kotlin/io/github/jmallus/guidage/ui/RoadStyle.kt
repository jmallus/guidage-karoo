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
 * Le noir a été essayé — voies blanches, rang lu à la seule épaisseur. Le contraste y était
 * maximal, mais on y perdait deux choses : la lumière du jour, que l'écran ne sait pas rendre
 * sur du noir, et la classe des voies, qu'une épaisseur de un ou deux pixels d'écart ne dit
 * pas à cinquante à l'heure. Les teintes sont donc revenues.
 *
 * Les voies restent sourdes malgré tout : le fond n'est pas là pour être regardé mais pour
 * situer le tracé, qui doit rester la chose la plus visible de l'écran.
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
     * Couleur d'une voie.
     *
     * Les classes ont chacune leur teinte, comme sur une carte routière : le rouge et
     * l'orange pour ce qui roule vite, le gris pour la desserte, le brun pour ce qui n'est
     * pas revêtu, le vert pour les voies cyclables. Sur un écran de six centimètres, la
     * couleur distingue les classes bien plus vite que l'épaisseur seule.
     *
     * Trottoirs et escaliers sont volontairement à peine détachés du fond : ils forment
     * le tiers du fichier et n'intéressent ni le gravel ni le VTT, mais les effacer
     * complètement reviendrait à prétendre qu'il n'y a rien là où il y a quelque chose.
     */
    fun color(kind: RoadKind, surface: RoadSurface): Int = when (kind) {
        RoadKind.MOTORWAY, RoadKind.TRUNK -> EXPRESSWAY
        RoadKind.PRIMARY -> PRIMARY
        RoadKind.SECONDARY, RoadKind.TERTIARY -> SECONDARY
        RoadKind.UNCLASSIFIED, RoadKind.RESIDENTIAL -> MINOR
        RoadKind.SERVICE -> SERVICE
        RoadKind.FOOTWAY, RoadKind.STEPS -> FAINT
        RoadKind.CYCLEWAY -> CYCLEWAY
        RoadKind.STREAM -> WATER_LINE
        RoadKind.WATER -> WATER_FILL
        RoadKind.FOREST -> FOREST_FILL
        RoadKind.BUILT_UP -> BUILT_UP_FILL
        RoadKind.FARMLAND -> FARMLAND_FILL
        // Chemins et sentiers : ce que le coureur cherche, donc le brun des cartes de
        // randonnée, qui ne se confond avec aucune route.
        RoadKind.TRACK, RoadKind.PATH, RoadKind.BRIDLEWAY ->
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

    private const val EXPRESSWAY = 0xFFD4573C.toInt()
    private const val PRIMARY = 0xFFE8944A.toInt()
    private const val SECONDARY = 0xFFD8B23F.toInt()
    /**
     * Les trois teintes que le champ « Revêtement » reprend au fond de carte.
     *
     * Elles sont exposées plutôt que recopiées : la bande du champ et les voies de la
     * minicarte doivent parler la même langue, et une seconde écriture de la même couleur
     * finit toujours par diverger de la première.
     */
    const val MINOR_ROAD = 0xFF7C8489.toInt()
    const val TRAIL_ROAD = 0xFF9A6B33.toInt()
    const val CYCLEWAY_ROAD = 0xFF2E7D6B.toInt()

    private const val MINOR = MINOR_ROAD
    private const val SERVICE = 0xFFA9AFB4.toInt()
    private const val TRAIL = TRAIL_ROAD
    private const val CYCLEWAY = CYCLEWAY_ROAD

    /**
     * Surfaces : des teintes franches mais pâles.
     *
     * Elles occupent de grandes plages derrière tout le reste ; saturées, elles
     * emporteraient le regard que le tracé doit garder. Ce sont les couleurs des cartes de
     * randonnée, où l'on reconnaît l'eau et le bois sans avoir à y penser.
     */
    private const val WATER_FILL = 0xFFB4D4E7.toInt()
    private const val WATER_LINE = 0xFF6FA8CC.toInt()
    // Teinte choisie sur l'appareil même : les essais plus pâles disparaissaient
    // sur l'écran transflectif du Karoo, qui délave tout ce qui manque de saturation.
    private const val FOREST_FILL = 0xFF9AD770.toInt()
    private const val BUILT_UP_FILL = 0xFFE6DFD5.toInt()

    /**
     * Cultures et prairies : un vert de paille, plus jaune et plus clair que le bois.
     *
     * Les deux verts doivent se distinguer sans se disputer : la campagne couvre presque
     * tout l'écran en Normandie, elle ne peut être qu'à peine teintée, tandis que le bois,
     * plus rare et plus franc, se détache dessus. C'est la relation qu'ont ces deux verts
     * sur les cartes au 25 000e, où l'on reconnaît la lisière sans avoir à la chercher.
     *
     * Les premiers essais, calqués sur le papier, étaient si proches du fond que les
     * champs semblaient absents : l'écran transflectif délave les teintes pâles bien
     * plus que l'impression. Celles-ci ont été arrêtées sur l'appareil même.
     */
    private const val FARMLAND_FILL = 0xFFCDD770.toInt()

    /** Trottoirs et escaliers : présents, mais sans prendre la lumière. */
    private const val FAINT = 0xFFCFCAC0.toInt()
}
