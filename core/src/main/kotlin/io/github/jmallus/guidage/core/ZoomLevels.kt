package io.github.jmallus.guidage.core

/**
 * Niveaux de zoom parcourus par appui sur le champ, comme sur la carte native du Karoo.
 */

/** Portée du profil altimétrique en portrait. */
enum class GraphZoom(val lookaheadMeters: Double?) {
    /** Le parcours entier, du départ à l'arrivée. */
    WHOLE_ROUTE(null),
    AHEAD_2KM(2_000.0),
    AHEAD_20KM(20_000.0),
    AHEAD_50KM(50_000.0),
    AHEAD_100KM(100_000.0),
    ;

    fun next(): GraphZoom = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(ordinal: Int): GraphZoom = entries.getOrElse(ordinal) { AHEAD_20KM }
    }
}

/**
 * Distance visible devant le coureur sur la minicarte, parcourue par appui sur le champ.
 *
 * Trois crans seulement, relevés en roulant : deux cents mètres pour le carrefour qui
 * vient, cinq cents pour la sortie du village, un kilomètre pour savoir où l'on va. Au-delà,
 * la carte native du Karoo fait mieux, et en deçà on ne voit plus assez loin pour anticiper.
 *
 * Chaque cran portait aussi une longueur de chevrons, ceux-ci étant semés le long de ce qui
 * restait à faire et devant s'arrêter avant qu'une boucle ne ramène l'itinéraire à côté de
 * lui-même. Il ne reste qu'une marque, posée sur le coureur : il n'y a plus de longueur à
 * borner.
 */
enum class MapZoom(val rangeMeters: Double) {
    NEAR(200.0),
    MIDDLE(500.0),
    FAR(1_000.0),
    ;

    fun next(): MapZoom = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(ordinal: Int): MapZoom = entries.getOrElse(ordinal) { NEAR }
    }
}

/** Ce que le tableau de bord affiche dans sa zone de guidage. */
enum class GuidanceZoneType {
    /** Minicarte orientée cap en haut. */
    MAP,

    /** Profil altimétrique en portrait. */
    PROFILE,
    ;

    companion object {
        fun fromName(name: String?): GuidanceZoneType =
            entries.firstOrNull { it.name == name } ?: MAP
    }
}
