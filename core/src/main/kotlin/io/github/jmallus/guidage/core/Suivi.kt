package io.github.jmallus.guidage.core

/** Où l'on montre le coureur : sa marque, le cap qui oriente la carte, son abscisse. */
data class PositionSuivie(
    val point: GeoPoint,
    /** Cap en degrés (0 = nord), null quand il est inconnu. */
    val heading: Double?,
    /** Distance le long de l'itinéraire (m), à l'échelle de sa longueur annoncée. */
    val distanceAlongRoute: Double?,
    /** Vrai quand la marque a été posée sur le tracé, faux quand elle suit le GPS seul. */
    val surLeTrace: Boolean,
)

/**
 * Rattrape le retard de la position rapportée par le Karoo, **le long du tracé**.
 *
 * Le point GPS arrive avec quelques secondes de retard, et le champ met encore un moment à
 * se redessiner. On le prolongeait en ligne droite, selon le dernier cap : juste sur une
 * ligne droite, faux dans un virage — la marque filait tout droit hors de la route, puis
 * revenait d'un coup au point suivant. Le défaut se voyait là précisément où l'on regarde
 * la carte.
 *
 * Quand le coureur est sur l'itinéraire, on sait où il va : la route. La marque est donc
 * posée sur le tracé et avancée **le long de lui**, virages compris, de la distance que la
 * vitesse fait parcourir pendant le retard. Le cap est celui du tracé juste devant, ce qui
 * fait tourner la carte avec la route au lieu d'attendre que le GPS ait tourné. L'abscisse
 * qui en sort sert aussi au profil, qui montre ainsi le coureur au même endroit que la carte.
 *
 * Hors de l'itinéraire, rien ne dit où va la route qu'il emprunte : on revient au
 * prolongement en ligne droite, borné au seul âge du point.
 */
object Suivi {

    /**
     * Écart au tracé au-delà duquel on ne pose plus la marque dessus (m).
     *
     * Plus large que l'imprécision d'un GPS en ville, plus court que l'écart d'une route
     * parallèle : au-delà, c'est qu'on a quitté l'itinéraire.
     */
    const val ECART_MAX_METERS = 35.0

    /** Longueur de tracé devant la marque d'où se tire le cap (m). */
    private const val VISEE_METERS = 15.0

    /**
     * Tolérance pour départager deux passages au même endroit, voir [Geo.anchorOnPath] (m).
     */
    private const val TOLERANCE_METERS = 30.0

    fun position(
        path: List<GeoPoint>,
        /** Longueur annoncée de l'itinéraire (m) ; le tracé décodé en diffère un peu. */
        totalDistance: Double?,
        position: GeoPoint,
        heading: Double?,
        /** Distance le long de l'itinéraire annoncée par le Karoo (m). */
        distanceAlongRoute: Double?,
        speedMetersPerSecond: Double?,
        /** Le retard à rattraper sur le tracé (s). */
        secondesSurTrace: Double,
        /** Le retard à rattraper hors du tracé, en ligne droite (s). */
        secondesHorsTrace: Double,
    ): PositionSuivie {
        val vitesse = speedMetersPerSecond?.takeIf { it > 0.0 } ?: 0.0
        val longueur = Geo.length(path)
        // Le tracé décodé et la longueur annoncée diffèrent de quelques mètres par
        // kilomètre : les abscisses passent de l'un à l'autre à proportion.
        val echelle = if (totalDistance != null && totalDistance > 0.0 && longueur > 0.0) {
            longueur / totalDistance
        } else {
            1.0
        }
        val ancre = Geo.anchorOnPath(path, position, distanceAlongRoute?.times(echelle), TOLERANCE_METERS)
            ?.takeIf { it.deviation <= ECART_MAX_METERS }

        if (ancre == null) {
            val point = if (heading != null && vitesse > 0.0) {
                Geo.advance(position, heading, vitesse * secondesHorsTrace)
            } else {
                position
            }
            return PositionSuivie(point, heading, distanceAlongRoute, surLeTrace = false)
        }

        val abscisse = (ancre.distanceAlongPath + vitesse * secondesSurTrace).coerceAtMost(longueur)
        val point = Geo.pointAt(path, abscisse) ?: ancre.point
        val devant = Geo.pointAt(path, abscisse + VISEE_METERS)
        // Au bout du tracé, il n'y a plus rien devant : le cap rapporté reprend la main.
        val cap = devant?.takeIf { Geo.distance(point, it) > 1.0 }?.let { Geo.bearing(point, it) } ?: heading
        return PositionSuivie(point, cap, abscisse / echelle, surLeTrace = true)
    }
}
