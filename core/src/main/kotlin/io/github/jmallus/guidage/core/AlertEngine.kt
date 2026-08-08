package io.github.jmallus.guidage.core

/** Réglages des alertes de guidage. */
data class AlertSettings(
    val climbEnabled: Boolean = true,
    /** Distance d'annonce avant le pied de la côte (m). */
    val climbDistance: Double = 300.0,
    val summitEnabled: Boolean = true,
    /** Distance d'annonce avant le sommet (m). */
    val summitDistance: Double = 200.0,
    val poiEnabled: Boolean = true,
    /** Distance d'annonce avant un point d'intérêt (m). */
    val poiDistance: Double = 500.0,
    /** Longueur minimale d'une côte pour déclencher une annonce (m). */
    val minimumClimbLength: Double = 200.0,
)

/** Type d'alerte à présenter au coureur. */
enum class AlertKind { CLIMB_APPROACH, CLIMB_TOP, POI_APPROACH }

/** Alerte prête à être affichée. Le texte est construit côté Android (ressources traduites). */
data class GuidanceAlert(
    val key: String,
    val kind: AlertKind,
    val climb: ClimbStatus? = null,
    val poi: PoiStatus? = null,
)

/**
 * Décide quelles alertes déclencher au fil des mises à jour de position.
 *
 * L'instance retient les alertes déjà émises pour ne pas les répéter, et se réinitialise
 * quand l'itinéraire change (ou quand la navigation s'arrête).
 */
class AlertEngine(private var settings: AlertSettings = AlertSettings()) {

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

        Guidance.climbStatus(route, along)?.let { climb ->
            if (climb.climb.length >= settings.minimumClimbLength) {
                if (settings.climbEnabled &&
                    !climb.onClimb &&
                    climb.distanceToStart <= settings.climbDistance
                ) {
                    add(alerts, GuidanceAlert("climb-${climb.number}", AlertKind.CLIMB_APPROACH, climb = climb))
                }
                if (settings.summitEnabled &&
                    climb.onClimb &&
                    climb.distanceToTop <= settings.summitDistance
                ) {
                    add(alerts, GuidanceAlert("top-${climb.number}", AlertKind.CLIMB_TOP, climb = climb))
                }
            }
        }

        if (settings.poiEnabled) {
            Guidance.nextPoi(route, along)?.let { poi ->
                if (poi.distance <= settings.poiDistance) {
                    val poiKey = "poi-${poi.poi.id}-${poi.poi.distanceAlongRoute.toInt()}"
                    add(alerts, GuidanceAlert(poiKey, AlertKind.POI_APPROACH, poi = poi))
                }
            }
        }

        return alerts
    }

    private fun add(alerts: MutableList<GuidanceAlert>, alert: GuidanceAlert) {
        if (fired.add(alert.key)) alerts.add(alert)
    }
}
