package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.settings.SettingsRepository

/**
 * Appui sur le **haut** du tableau de bord : la portée de la zone de guidage.
 *
 * L'appui agit sur ce qui est affiché — les portées de la minicarte, ou celles du graphe de
 * parcours — plutôt que sur les deux à la fois : changer la portée de ce qu'on ne voit pas ne
 * s'apprend qu'en basculant l'affichage, ce qui est le contraire d'une commande.
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

/**
 * Appui sur le **bas** du tableau de bord : la portée du bandeau de profil.
 *
 * Le champ n'avait qu'une commande pour deux affichages. La carte la prenait toute, et la
 * portée du bandeau, quoique réglable, était inatteignable pour qui roule avec la carte en
 * haut — c'est-à-dire pour à peu près tout le monde. Le doigt agit maintenant sur ce qu'il
 * désigne, ce qui est la seule règle qu'une commande tactile ait à respecter.
 *
 * La frontière entre les deux zones est celle du dessin, et elle est lue au même endroit par
 * les deux — voir `DashboardRenderer.bandTop`. Deux frontières qui dériveraient l'une de
 * l'autre feraient un champ où le doigt agit sur le voisin, panne d'autant plus pénible
 * qu'elle ne se voit pas : le geste marche, il change simplement autre chose.
 */
class ChangeProfileZoomAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = SettingsRepository(context)
        val settings = repository.read()
        repository.write(settings.copy(graphZoom = settings.graphZoom.next()))
    }
}
