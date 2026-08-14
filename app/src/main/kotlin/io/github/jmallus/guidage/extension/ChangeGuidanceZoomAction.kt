package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.settings.SettingsRepository

/**
 * Appui sur le tableau de bord : fait défiler les portées de la zone de guidage.
 *
 * L'appui agit sur ce qui est affiché — les portées de la minicarte, ou celles du profil
 * altimétrique — plutôt que sur les deux à la fois : changer la portée de ce qu'on ne voit
 * pas ne s'apprend qu'en basculant l'affichage, ce qui est le contraire d'une commande.
 */
class ChangeGuidanceZoomAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = SettingsRepository(context)
        val settings = repository.read()
        repository.write(
            when (settings.guidanceZone) {
                GuidanceZoneType.MAP -> settings.copy(mapZoom = settings.mapZoom.next())
                GuidanceZoneType.PROFILE -> settings.copy(graphZoom = settings.graphZoom.next())
            },
        )
    }
}
