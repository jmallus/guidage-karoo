package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.NightRenderer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ graphique « Avant la nuit » : arrivera-t-on avant le coucher du soleil ?
 *
 * La question se pose sur toute sortie qui finit tard, et la réponse demande de rapprocher
 * deux heures que rien ne rapproche à l'écran : l'arrivée estimée, avec sa fourchette, et le
 * coucher, que le coureur ne connaît qu'à peu près. Ce champ fait le rapprochement, et le dit
 * en un mot.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class NightDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le flux numérique donne l'avance sur le coucher, en minutes, négative quand on arrive
     * après. C'est le chiffre à poser dans un champ ordinaire, ou à faire lire par une alerte.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, rideDataProvider.data, ticks()) { snapshot, rideData, _ ->
                NightModels.assessment(snapshot, rideData, System.currentTimeMillis())
            }
                .map { assessment ->
                    if (assessment == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to assessment.marginSeconds / 60.0),
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

            combine(provider.snapshot, rideDataProvider.data, ticks()) { snapshot, rideData, _ ->
                NightModels.build(context, snapshot, rideData, config.preview)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    NightRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Une impulsion par minute, pour que le champ vieillisse même quand rien ne bouge.
     *
     * Les autres champs se redessinent quand une donnée change ; celui-ci dépend aussi de
     * l'heure qu'il est, et à l'arrêt au bord de la route la marge fond sans qu'aucun flux
     * ne le signale.
     */
    private fun ticks() = flow {
        var n = 0L
        while (true) {
            emit(n++)
            delay(TICK_MS)
        }
    }

    companion object {
        const val TYPE_ID = "nuit"
        private const val TICK_MS = 60_000L
    }
}
