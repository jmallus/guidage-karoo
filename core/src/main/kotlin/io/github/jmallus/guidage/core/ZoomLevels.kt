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
 * Distance visible devant le coureur sur la minicarte.
 *
 * Une seule portée, relevée en roulant : à 150 m, le prochain carrefour se lit sans
 * hésitation et les épaisseurs de chaussée gardent un sens. Les portées plus larges,
 * parcourues par appui sur le champ, n'ont servi qu'à égarer — la carte native du Karoo
 * est là pour la vue d'ensemble.
 */
const val MAP_RANGE_METERS = 150.0

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
