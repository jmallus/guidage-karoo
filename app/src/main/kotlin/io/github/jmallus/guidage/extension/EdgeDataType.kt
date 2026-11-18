package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.EdgeRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ plein écran « Tableau Edge », en version claire ou sombre : voir [EdgeRenderer].
 *
 * Deux champs plutôt qu'un qui suivrait le thème du Karoo : le coureur choisit son style
 * page par page, comme il l'avait sur l'Edge.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class EdgeDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
    private val sombre: Boolean,
) : DataTypeImpl(extension, if (sombre) TYPE_ID_SOMBRE else TYPE_ID_CLAIR) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(if (sombre) TYPE_ID_SOMBRE else TYPE_ID_CLAIR, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                provider.snapshot,
                if (config.preview) flowOf(RideData()) else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings -> Triple(snapshot, rideData, settings) }
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (snapshot, rideData, settings) ->
                    EdgeModels.build(
                        context, snapshot, rideData, settings,
                        preview = config.preview && !snapshot.state.navigating,
                    )
                }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    EdgeRenderer.render(context, width, height, model, sombre)
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID_CLAIR = "edge-clair"
        const val TYPE_ID_SOMBRE = "edge-sombre"
    }
}
