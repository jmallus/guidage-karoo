package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.EffortRenderer
import io.github.jmallus.guidage.ui.FieldPalette
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Champ graphique « Budget d'effort » : ce que coûte encore le parcours, en kilojoules.
 *
 * Savoir qu'il reste vingt-cinq kilomètres ne dit pas s'il faut se garder. Savoir qu'il
 * reste six cents kilojoules, dont quatre cents dans deux côtes, le dit.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class EffortDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    private val models = EffortModels

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                models.estimate(snapshot.state, rideData)
            }
                .map { estimate ->
                    if (estimate == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to estimate.kilojoules),
                            ),
                        )
                    }
                }
                .distinctUntilChanged()
                .collect { emitter.onNext(it) }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                models.build(context, snapshot.state, rideData, config.preview)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    EffortRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "effort"
    }
}
