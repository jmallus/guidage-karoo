package io.github.jmallus.guidage.core

/**
 * Niveaux de zoom parcourus par appui sur le champ, comme sur la carte native du Karoo.
 */

/**
 * Portée du bandeau de profil, parcourue par appui sur son bas.
 *
 * Quatre crans, tous à échelle régulière : cinq kilomètres pour la côte qui vient, dix pour
 * l'heure qui vient, vingt pour la demi-journée, cinquante pour la journée. Le parcours
 * entier n'en est plus un — c'est ce que montre le champ « Profil à venir », à l'échelle
 * comprimée qui est faite pour ça, et un bandeau qui l'affichait aussi écrasait les côtes
 * contre le fond de la fenêtre sans qu'on s'en rende compte.
 */
enum class GraphZoom(val lookaheadMeters: Double) {
    AHEAD_5KM(5_000.0),
    AHEAD_10KM(10_000.0),
    AHEAD_20KM(20_000.0),
    AHEAD_50KM(50_000.0),
    ;

    fun next(): GraphZoom = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Ce que montre le bandeau tant qu'on n'y a pas touché. */
        val DEFAULT = AHEAD_10KM

        fun fromOrdinal(ordinal: Int): GraphZoom = entries.getOrElse(ordinal) { DEFAULT }
    }
}

/**
 * Distance visible devant le coureur sur la minicarte, parcourue par appui sur le champ.
 *
 * Quatre crans, relevés en roulant : cent cinquante mètres pour la place où l'on hésite,
 * trois cents pour le carrefour qui vient, cinq cents pour la sortie du village, un kilomètre
 * pour savoir où l'on va. Au-delà, la carte native du Karoo fait mieux.
 *
 * Le cran le plus court a longtemps été à deux cents mètres, puis à trois cents : à trente à
 * l'heure, deux cents passent en vingt-quatre secondes, et le temps de lire la carte ce qu'on
 * y avait vu était derrière. Cent cinquante est revenu par le bas pour une autre raison — non
 * pour anticiper mais pour se situer, là où les rues se ressemblent et où l'on veut voir
 * laquelle on prend. On n'y roule pas vite, et la seconde perdue à lire n'est pas la même.
 *
 * À chaque cran sa longueur de chevrons. Ils ne courent pas sur tout ce qui reste : sur un
 * parcours qui repasse par son départ, la branche du retour est là, à quelques mètres, et en
 * plein dans le couloir du fond — ses chevrons désigneraient une direction qui n'est pas celle
 * du moment. Bornés à ce qu'on atteindra dans la minute ou deux, ils ne montrent qu'un chemin
 * à la fois.
 */
enum class MapZoom(val rangeMeters: Double, val chevronMeters: Double) {
    CLOSE(150.0, 250.0),
    NEAR(300.0, 450.0),
    MIDDLE(500.0, 800.0),
    FAR(1_000.0, 1_300.0),
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
