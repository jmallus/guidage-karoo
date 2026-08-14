package io.github.jmallus.guidage.core.map

/**
 * Nature d'un objet du fond de carte, telle qu'on la retient d'OpenStreetMap.
 *
 * La liste est volontairement courte : le fond ne sert qu'à situer le tracé, pas à décrire
 * le terrain. On y trouve les voies, les cours d'eau — des lignes — puis les surfaces :
 * l'eau, les bois et le bâti, qui donnent au coup d'œil ce que les voies seules ne disent
 * pas, à savoir si l'on longe une rivière ou si l'on traverse un village.
 *
 * Chaque valeur est écrite dans le fichier sous son [code], qui ne doit donc jamais changer
 * une fois publié.
 */
enum class RoadKind(val code: Int) {
    MOTORWAY(0),
    TRUNK(1),
    PRIMARY(2),
    SECONDARY(3),
    TERTIARY(4),
    UNCLASSIFIED(5),
    RESIDENTIAL(6),
    SERVICE(7),

    /** Chemin d'exploitation : l'ordinaire du gravel. */
    TRACK(8),

    /** Sentier : l'ordinaire du VTT. */
    PATH(9),
    CYCLEWAY(10),
    BRIDLEWAY(11),
    FOOTWAY(12),
    STEPS(13),

    /** Cours d'eau, dessiné en ligne : rivière, ruisseau, canal. */
    STREAM(14),

    /** Plan d'eau ou cours d'eau assez large pour avoir une rive : dessiné en surface. */
    WATER(15),

    /** Bois et forêts. */
    FOREST(16),

    /** Bâti : zones résidentielles, industrielles, villages. */
    BUILT_UP(17),
    ;

    /** true pour les voies qui intéressent le gravel et le VTT. */
    val isTrail: Boolean get() = code in TRACK.code..STEPS.code

    /** true pour ce qui se remplit plutôt que se trace : la polyligne est alors un contour. */
    val isArea: Boolean get() = code >= WATER.code

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun fromCode(code: Int): RoadKind? = BY_CODE[code]

        /**
         * Correspondance depuis les tags de surface, ou null si l'objet ne nous intéresse pas.
         *
         * Les trois familles retenues sont celles qui se voient depuis la selle et qui
         * aident à se situer. Le reste — champs, prairies, terrains de sport — couvrirait
         * l'écran sans rien apprendre.
         */
        fun fromAreaTags(natural: String?, landuse: String?, waterway: String?): RoadKind? = when {
            natural == "water" || natural == "wetland" -> WATER
            waterway == "riverbank" || waterway == "dock" -> WATER
            landuse == "reservoir" || landuse == "basin" -> WATER
            natural == "wood" || natural == "scrub" -> FOREST
            landuse == "forest" -> FOREST
            landuse in BUILT_UP_TAGS -> BUILT_UP
            else -> null
        }

        /** Correspondance depuis la valeur du tag `waterway`, pour les cours d'eau en ligne. */
        fun fromWaterwayTag(tag: String): RoadKind? = when (tag) {
            "river", "canal", "stream" -> STREAM
            else -> null
        }

        private val BUILT_UP_TAGS = setOf("residential", "industrial", "commercial", "retail")

        /** Correspondance depuis la valeur du tag `highway`, ou null si la voie ne nous intéresse pas. */
        fun fromHighwayTag(tag: String): RoadKind? = when (tag) {
            "motorway", "motorway_link" -> MOTORWAY
            "trunk", "trunk_link" -> TRUNK
            "primary", "primary_link" -> PRIMARY
            "secondary", "secondary_link" -> SECONDARY
            "tertiary", "tertiary_link" -> TERTIARY
            "unclassified" -> UNCLASSIFIED
            "residential", "living_street" -> RESIDENTIAL
            "service" -> SERVICE
            "track" -> TRACK
            "path" -> PATH
            "cycleway" -> CYCLEWAY
            "bridleway" -> BRIDLEWAY
            "footway", "pedestrian" -> FOOTWAY
            "steps" -> STEPS
            else -> null
        }
    }
}

/**
 * Revêtement, dans la mesure où OSM le renseigne.
 *
 * Trois valeurs suffisent à distinguer ce qui se roule en gravel : le détail des dizaines
 * de valeurs du tag `surface` n'apporterait rien à trois pixels de large.
 */
enum class RoadSurface(val code: Int) {
    UNKNOWN(0),
    PAVED(1),
    UNPAVED(2),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun fromCode(code: Int): RoadSurface = BY_CODE[code] ?: UNKNOWN

        private val PAVED_TAGS = setOf(
            "paved", "asphalt", "concrete", "concrete:plates", "concrete:lanes",
            "paving_stones", "sett", "cobblestone", "metal", "wood", "chipseal",
        )

        private val UNPAVED_TAGS = setOf(
            "unpaved", "compacted", "fine_gravel", "gravel", "pebblestone", "rock",
            "dirt", "earth", "soil", "ground", "grass", "grass_paver", "mud", "sand",
            "woodchips", "ice", "snow",
        )

        fun fromSurfaceTag(tag: String?): RoadSurface = when (tag) {
            null -> UNKNOWN
            in PAVED_TAGS -> PAVED
            in UNPAVED_TAGS -> UNPAVED
            else -> UNKNOWN
        }
    }
}

/**
 * Un tronçon de voie : une polyligne et ce qu'il faut pour la dessiner.
 *
 * Les coordonnées sont en micro-degrés entiers. À cette précision un pas vaut onze
 * centimètres, largement en deçà de ce qu'un écran de vélo peut montrer, et l'entier
 * évite de traîner des flottants dans l'index et le fichier.
 */
data class RoadSegment(
    val kind: RoadKind,
    val surface: RoadSurface,
    /** Latitudes en micro-degrés, de même longueur que [longitudes]. */
    val latitudes: IntArray,
    val longitudes: IntArray,
) {
    init {
        require(latitudes.size == longitudes.size) { "latitudes et longitudes de tailles différentes" }
        require(latitudes.size >= 2) { "un tronçon a au moins deux points" }
    }

    val size: Int get() = latitudes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoadSegment) return false
        return kind == other.kind &&
            surface == other.surface &&
            latitudes.contentEquals(other.latitudes) &&
            longitudes.contentEquals(other.longitudes)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + latitudes.contentHashCode()
        result = 31 * result + longitudes.contentHashCode()
        return result
    }
}

/** Rectangle géographique en micro-degrés. */
data class MapBounds(
    val minLatitude: Int,
    val minLongitude: Int,
    val maxLatitude: Int,
    val maxLongitude: Int,
) {
    fun intersects(other: MapBounds): Boolean =
        minLatitude <= other.maxLatitude &&
            maxLatitude >= other.minLatitude &&
            minLongitude <= other.maxLongitude &&
            maxLongitude >= other.minLongitude

    companion object {
        /** Rectangle englobant d'une suite de points, en micro-degrés. */
        fun of(latitudes: IntArray, longitudes: IntArray) = MapBounds(
            minLatitude = latitudes.min(),
            minLongitude = longitudes.min(),
            maxLatitude = latitudes.max(),
            maxLongitude = longitudes.max(),
        )
    }
}

/** Facteur de conversion entre degrés et micro-degrés. */
const val MICRO_DEGREES = 1_000_000.0

fun Double.toMicroDegrees(): Int = Math.round(this * MICRO_DEGREES).toInt()

fun Int.fromMicroDegrees(): Double = this / MICRO_DEGREES
