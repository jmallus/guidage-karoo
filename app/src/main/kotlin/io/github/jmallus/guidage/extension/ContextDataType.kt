package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.ContextRenderer
import io.github.jmallus.guidage.ui.FieldPalette
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
 * Champ graphique « Suivant la sortie » : un champ dont la moitié basse change de contenu
 * selon ce que la sortie est en train de faire.
 *
 * C'est l'inverse du réglage. Au lieu de choisir une fois pour toutes ce qu'on veut voir, le
 * champ suit : en montée le restant au sommet et la silhouette de ce qui monte, en descente
 * les virages, à l'approche d'un ravitaillement sa distance, et le reste du temps la vitesse
 * et ce qui reste.
 *
 * Deux garde-fous le rendent supportable en roulant. La moitié haute ne bouge jamais, pour
 * que l'œil retrouve distance et arrivée au même endroit. Et le rail de pastilles dit dans
 * quel état on est : une bascule qu'on n'a pas vue venir est une bascule qu'on subit.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ContextDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()
    private val models = ContextModels(clock)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                models.build(context, snapshot, rideData, config.preview)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    ContextRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "contexte"
    }
}
