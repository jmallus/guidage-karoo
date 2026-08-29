package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.ProfileRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ graphique « Profil à venir » : tout ce qui reste de l'itinéraire, coloré selon la
 * pente, avec les côtes surlignées.
 *
 * Tout ce qui reste, et non une portée choisie : l'échelle horizontale est comprimée au loin
 * (voir `FisheyeScale`), de sorte que la rampe dans trois cents mètres et le col de la
 * quatrième heure tiennent dans la même bande.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ProfileDataType(
    private val provider: GuidanceProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, settingsRepository.settings) { snapshot, settings ->
                FieldModels.profile(context, snapshot, settings, config.preview)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    ProfileRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "profil"
    }
}
