package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.core.Resupply
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.ResupplyRenderer
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

/**
 * Champ graphique « Réserve » : après quel point il n'y a plus rien.
 *
 * C'est une page entière, et il lui en faut une. Ce champ ne porte pas une valeur mais une
 * répartition — l'itinéraire tout entier, les points qui le jalonnent, et le vide qui suit le
 * dernier. Réduit à une bande, il ne montrerait plus que ses deux chiffres, c'est-à-dire ce
 * que l'annonce in-ride dit déjà mieux, au moment où il faut l'entendre.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ResupplyDataType(
    private val provider: GuidanceProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le flux numérique donne la longueur de la traversée qui vient.
     *
     * Et non la distance au prochain point : celle-là existe déjà dans « Prochain point
     * d'intérêt », et ce n'est pas la question que ce champ pose.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, settingsRepository.settings) { snapshot, settings ->
                val route = snapshot.state.route
                val along = snapshot.state.distanceAlongRoute
                if (route == null || along == null) {
                    null
                } else {
                    val types = ResupplyTypes.of(settings.resupplyWaterOnly)
                    Resupply.status(route, along, types).crossing?.length
                }
            }
                .distinctUntilChanged()
                .map { length ->
                    if (length == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(
                            DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to length)),
                        )
                    }
                }
                .collect { emitter.onNext(it) }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, settingsRepository.settings) { snapshot, settings ->
                FieldModels.resupply(
                    context,
                    snapshot,
                    config.preview,
                    ResupplyTypes.of(settings.resupplyWaterOnly),
                )
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    ResupplyRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "reserve"
    }
}
