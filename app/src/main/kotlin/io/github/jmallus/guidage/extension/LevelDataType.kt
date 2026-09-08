package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.LevelRenderer
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
 * Les six cases de bilan, servies par un seul champ paramétré.
 *
 * Six fichiers auraient été six fois la même chose : mêmes flux, même rendu, même cycle de
 * vie, seule la [variante] changeant. Ce qui diverge est dans [LevelModels] ; ce qui ne diverge
 * pas est ici, écrit une fois. Les six restent bien six champs distincts pour Karoo OS — un
 * `typeId` chacun, déclaré dans `extension_info.xml` — et se posent séparément sur une page.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LevelDataType(
    private val variante: Bilan,
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
) : DataTypeImpl(extension, variante.typeId) {

    private val glance = GlanceRemoteViews()

    /**
     * Le flux numérique publie le chiffre principal de la case.
     *
     * C'est ce qui permet de la poser aussi comme champ de texte ordinaire, ou de la faire
     * enregistrer. La répartition par zone n'est pas un nombre : elle publie le rang de la
     * zone dominante, faute de mieux, et rien tant qu'aucune zone n'est mesurée.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                LevelModels.value(variante, snapshot, rideData, System.currentTimeMillis())
            }
                .map { valeur ->
                    if (valeur == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(
                            DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to valeur)),
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
            FieldReportStore(context).record(dataTypeId, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                LevelModels.build(context, variante, snapshot, rideData, config.preview)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    LevelRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}
