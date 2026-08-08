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

/** Distance visible devant le coureur sur la minicarte. */
enum class MapRange(val meters: Double) {
    AHEAD_500M(500.0),
    AHEAD_1KM(1_000.0),
    AHEAD_2KM(2_000.0),
    AHEAD_5KM(5_000.0),
    AHEAD_10KM(10_000.0),
    ;

    fun next(): MapRange = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(ordinal: Int): MapRange = entries.getOrElse(ordinal) { AHEAD_1KM }
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
