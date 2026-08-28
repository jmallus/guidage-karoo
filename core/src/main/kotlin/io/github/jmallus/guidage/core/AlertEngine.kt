package io.github.jmallus.guidage.core

/** Réglages des alertes de guidage. */
data class AlertSettings(
    val poiEnabled: Boolean = true,
    /** Distance d'annonce avant un point d'intérêt (m). */
    val poiDistance: Double = 500.0,
)

/**
 * Type d'alerte à présenter au coureur.
 *
 * Les côtes n'en déclenchent plus. Elles en avaient deux — une au pied, une avant le sommet —
 * qui couvraient l'écran au moment précis où l'on regarde le bandeau de profil pour savoir ce
 * qui reste à monter. La bande, elle, est là en permanence et porte le rang de la côte et la
 * distance au sommet : l'annonce ne disait rien de plus, elle le disait par-dessus. Le bouton
 * « prochaine côte » reste, lui, pour la demander quand on la veut.
 */
enum class AlertKind {
    POI_APPROACH,

    /**
     * Dernier ravitaillement avant une longue traversée.
     *
     * C'est l'annonce du point d'intérêt retournée : elle ne dit pas ce qui vient, elle dit
     * ce qui ne viendra plus. Elle se déclenche sur le même point que l'autre et à la même
     * distance — les deux arrivent donc ensemble, ce qui est voulu : la première nomme la
     * fontaine, la seconde dit qu'il faut s'y arrêter.
     */
    RESUPPLY_LAST,
}

/** Alerte prête à être affichée. Le texte est construit côté Android (ressources traduites). */
data class GuidanceAlert(
    val key: String,
    val kind: AlertKind,
    val poi: PoiStatus? = null,
    /** Traversée annoncée, pour [AlertKind.RESUPPLY_LAST]. */
    val crossing: Crossing? = null,
)

/**
 * Décide quelles alertes déclencher au fil des mises à jour de position.
 *
 * L'instance retient les alertes déjà émises pour ne pas les répéter, et se réinitialise
 * quand l'itinéraire change (ou quand la navigation s'arrête).
 */
class AlertEngine(
    private var settings: AlertSettings = AlertSettings(),
    /**
     * Types de points d'intérêt qui comptent comme un ravitaillement.
     *
     * Vides, l'annonce de traversée ne se déclenche jamais : c'est l'appareil qui nomme ses
     * types, et ce module ne les connaît pas.
     */
    private val resupplyTypes: Set<String> = emptySet(),
    private val crossingMeters: Double = Resupply.DEFAULT_CROSSING_METERS,
) {

    private val fired = mutableSetOf<String>()
    private var routeKey: String? = null

    fun updateSettings(settings: AlertSettings) {
        this.settings = settings
    }

    fun reset() {
        fired.clear()
        routeKey = null
    }

    /**
     * Retourne les alertes à déclencher pour [state]. Chaque alerte n'est retournée qu'une fois
     * par itinéraire.
     */
    fun evaluate(state: GuidanceState): List<GuidanceAlert> {
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            reset()
            return emptyList()
        }

        val key = "${route.name}|${route.totalDistance.toInt()}|${route.climbs.size}"
        if (key != routeKey) {
            fired.clear()
            routeKey = key
        }

        val alerts = mutableListOf<GuidanceAlert>()

        if (settings.poiEnabled) {
            Guidance.nextPoi(route, along)?.let { poi ->
                if (poi.distance <= settings.poiDistance) {
                    val poiKey = "poi-${poi.poi.id}-${poi.poi.distanceAlongRoute.toInt()}"
                    add(alerts, GuidanceAlert(poiKey, AlertKind.POI_APPROACH, poi = poi))
                }
            }

            // Le dernier point avant une longue traversée mérite son annonce à lui : c'est
            // le seul endroit où l'on peut encore décider de s'arrêter.
            val crossing = Resupply
                .status(route, along, resupplyTypes, crossingMeters)
                .crossing
            crossing?.lastPoi?.let { last ->
                if (last.distance <= settings.poiDistance) {
                    val key = "traversee-${last.poi.id}-${crossing.length.toInt()}"
                    add(
                        alerts,
                        GuidanceAlert(key, AlertKind.RESUPPLY_LAST, poi = last, crossing = crossing),
                    )
                }
            }
        }

        return alerts
    }

    private fun add(alerts: MutableList<GuidanceAlert>, alert: GuidanceAlert) {
        if (fired.add(alert.key)) alerts.add(alert)
    }
}
