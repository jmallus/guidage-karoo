package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.settings.SettingsRepository

/**
 * Appui sur le tableau de bord : fait défiler les portées du profil altimétrique.
 *
 * La minicarte, elle, ne bouge plus : elle est figée sur la seule portée qui se soit
 * révélée utile en roulant, cent cinquante mètres.
 */
class ChangeGuidanceZoomAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = SettingsRepository(context)
        val settings = repository.read()
        if (settings.guidanceZone != GuidanceZoneType.PROFILE) return
        repository.write(settings.copy(graphZoom = settings.graphZoom.next()))
    }
}
