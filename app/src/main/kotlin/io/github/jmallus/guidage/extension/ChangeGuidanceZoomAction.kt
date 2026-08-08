package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.settings.SettingsRepository

/**
 * Appui sur le tableau de bord : fait défiler les échelles de la zone de guidage,
 * comme la carte native du Karoo.
 */
class ChangeGuidanceZoomAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = SettingsRepository(context)
        val settings = repository.read()
        repository.write(
            when (settings.guidanceZone) {
                GuidanceZoneType.MAP -> settings.copy(mapRange = settings.mapRange.next())
                GuidanceZoneType.PROFILE -> settings.copy(graphZoom = settings.graphZoom.next())
            },
        )
    }
}
