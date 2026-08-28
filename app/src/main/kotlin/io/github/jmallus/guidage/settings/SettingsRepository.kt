package io.github.jmallus.guidage.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.jmallus.guidage.core.AlertSettings
import io.github.jmallus.guidage.core.GraphZoom
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.MapZoom
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Réglages de l'extension, modifiables depuis l'application. */
data class GuidageSettings(
    /** Colorer le profil selon la pente. */
    val colorByGrade: Boolean = true,
    val alerts: AlertSettings = AlertSettings(),
    /** Ce que le tableau de bord montre en haut : carte ou profil. */
    val guidanceZone: GuidanceZoneType = GuidanceZoneType.MAP,
    /** Portée du profil en portrait, changée par appui sur le champ. */
    val graphZoom: GraphZoom = GraphZoom.AHEAD_20KM,
    /** Portée de la minicarte, changée par appui sur le champ. */
    val mapZoom: MapZoom = MapZoom.NEAR,
    /**
     * Ce qui compte comme ravitaillement pour les champs « Réserve » et « Autonomie ».
     *
     * Faux par défaut — tout ce où l'on peut remplir un bidon ou refaire des poches. Vrai
     * pour qui roule en autonomie complète et ne s'arrête que pour l'eau : lui annoncer un
     * café comme un point utile lui fait croire à un secours qu'il ne prendra pas.
     */
    val resupplyWaterOnly: Boolean = false,
)

/**
 * Persistance des réglages dans les SharedPreferences, exposée en Flow pour que
 * l'extension applique les changements sans redémarrage.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val settings: Flow<GuidageSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        trySend(read())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun read(): GuidageSettings {
        val defaults = GuidageSettings()
        return GuidageSettings(
            colorByGrade = prefs.getBoolean(KEY_COLOR_BY_GRADE, defaults.colorByGrade),
            guidanceZone = GuidanceZoneType.fromName(prefs.getString(KEY_GUIDANCE_ZONE, null)),
            graphZoom = GraphZoom.fromOrdinal(prefs.getInt(KEY_GRAPH_ZOOM, defaults.graphZoom.ordinal)),
            mapZoom = MapZoom.fromOrdinal(prefs.getInt(KEY_MAP_ZOOM, defaults.mapZoom.ordinal)),
            resupplyWaterOnly = prefs.getBoolean(KEY_RESUPPLY_WATER_ONLY, defaults.resupplyWaterOnly),
            alerts = AlertSettings(
                poiEnabled = prefs.getBoolean(KEY_POI_ENABLED, defaults.alerts.poiEnabled),
                poiDistance = prefs.getFloat(KEY_POI_DISTANCE, defaults.alerts.poiDistance.toFloat()).toDouble(),
            ),
        )
    }

    fun write(settings: GuidageSettings) {
        prefs.edit()
            .putBoolean(KEY_COLOR_BY_GRADE, settings.colorByGrade)
            .putString(KEY_GUIDANCE_ZONE, settings.guidanceZone.name)
            .putInt(KEY_GRAPH_ZOOM, settings.graphZoom.ordinal)
            .putInt(KEY_MAP_ZOOM, settings.mapZoom.ordinal)
            .putBoolean(KEY_RESUPPLY_WATER_ONLY, settings.resupplyWaterOnly)
            .putBoolean(KEY_POI_ENABLED, settings.alerts.poiEnabled)
            .putFloat(KEY_POI_DISTANCE, settings.alerts.poiDistance.toFloat())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "guidage-settings"
        const val KEY_COLOR_BY_GRADE = "color_by_grade"
        const val KEY_GUIDANCE_ZONE = "guidance_zone"
        const val KEY_GRAPH_ZOOM = "graph_zoom"
        const val KEY_MAP_ZOOM = "map_zoom"
        const val KEY_RESUPPLY_WATER_ONLY = "resupply_water_only"
        // Les clés « climb_alerts », « climb_alert_distance », « summit_alerts » et
        // « summit_alert_distance » ont disparu avec les annonces de côte, « lookahead_meters »
        // avec la portée réglable du profil. Celles déjà écrites sur un appareil y restent,
        // inertes : les relire pour les effacer coûterait une migration là où quelques octets
        // oubliés ne gênent personne.
        const val KEY_POI_ENABLED = "poi_alerts"
        const val KEY_POI_DISTANCE = "poi_alert_distance"
    }
}
