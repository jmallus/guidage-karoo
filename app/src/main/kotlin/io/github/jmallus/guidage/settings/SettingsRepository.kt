package io.github.jmallus.guidage.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.jmallus.guidage.core.AlertSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Réglages de l'extension, modifiables depuis l'application. */
data class GuidageSettings(
    /** Distance affichée devant le coureur dans le champ « profil » (m). */
    val lookaheadMeters: Double = 5_000.0,
    /** Colorer le profil selon la pente. */
    val colorByGrade: Boolean = true,
    val alerts: AlertSettings = AlertSettings(),
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
            lookaheadMeters = prefs.getFloat(KEY_LOOKAHEAD, defaults.lookaheadMeters.toFloat()).toDouble(),
            colorByGrade = prefs.getBoolean(KEY_COLOR_BY_GRADE, defaults.colorByGrade),
            alerts = AlertSettings(
                climbEnabled = prefs.getBoolean(KEY_CLIMB_ENABLED, defaults.alerts.climbEnabled),
                climbDistance = prefs.getFloat(KEY_CLIMB_DISTANCE, defaults.alerts.climbDistance.toFloat()).toDouble(),
                summitEnabled = prefs.getBoolean(KEY_SUMMIT_ENABLED, defaults.alerts.summitEnabled),
                summitDistance = prefs.getFloat(KEY_SUMMIT_DISTANCE, defaults.alerts.summitDistance.toFloat()).toDouble(),
                poiEnabled = prefs.getBoolean(KEY_POI_ENABLED, defaults.alerts.poiEnabled),
                poiDistance = prefs.getFloat(KEY_POI_DISTANCE, defaults.alerts.poiDistance.toFloat()).toDouble(),
                minimumClimbLength = defaults.alerts.minimumClimbLength,
            ),
        )
    }

    fun write(settings: GuidageSettings) {
        prefs.edit()
            .putFloat(KEY_LOOKAHEAD, settings.lookaheadMeters.toFloat())
            .putBoolean(KEY_COLOR_BY_GRADE, settings.colorByGrade)
            .putBoolean(KEY_CLIMB_ENABLED, settings.alerts.climbEnabled)
            .putFloat(KEY_CLIMB_DISTANCE, settings.alerts.climbDistance.toFloat())
            .putBoolean(KEY_SUMMIT_ENABLED, settings.alerts.summitEnabled)
            .putFloat(KEY_SUMMIT_DISTANCE, settings.alerts.summitDistance.toFloat())
            .putBoolean(KEY_POI_ENABLED, settings.alerts.poiEnabled)
            .putFloat(KEY_POI_DISTANCE, settings.alerts.poiDistance.toFloat())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "guidage-settings"
        const val KEY_LOOKAHEAD = "lookahead_meters"
        const val KEY_COLOR_BY_GRADE = "color_by_grade"
        const val KEY_CLIMB_ENABLED = "climb_alerts"
        const val KEY_CLIMB_DISTANCE = "climb_alert_distance"
        const val KEY_SUMMIT_ENABLED = "summit_alerts"
        const val KEY_SUMMIT_DISTANCE = "summit_alert_distance"
        const val KEY_POI_ENABLED = "poi_alerts"
        const val KEY_POI_DISTANCE = "poi_alert_distance"
    }
}
