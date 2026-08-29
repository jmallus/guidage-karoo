package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.AutonomyFieldModel
import io.github.jmallus.guidage.ui.AutonomyRenderer
import io.github.jmallus.guidage.ui.BitmapField
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

/**
 * Champ graphique « Autonomie » : l'eau et le sucre sur la même page.
 *
 * Les deux champs existent séparément et continuent d'exister : celui qui n'a pas de capteur
 * de puissance ne veut que la réserve, et une page entière est cher payée pour deux moitiés
 * dont une reste vide. Ce champ-ci est pour l'autre cas, le plus fréquent en longue distance,
 * où les deux questions se posent au même moment et se répondent l'une l'autre.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class AutonomyDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le flux numérique donne les kilojoules restants.
     *
     * Des deux moitiés, c'est la seule qui se résume à un nombre : la réserve est une
     * répartition, et « la longueur de la prochaine traversée » est déjà le flux du champ
     * « Réserve ». Publier ici la même valeur sous un autre nom n'apprendrait rien.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                EffortModels.estimate(snapshot.state, rideData)
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

            combine(
                provider.snapshot,
                rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings ->
                AutonomyFieldModel(
                    resupply = FieldModels.resupply(
                        context,
                        snapshot,
                        config.preview,
                        ResupplyTypes.of(settings.resupplyWaterOnly),
                    ),
                    effort = EffortModels.build(context, snapshot.state, rideData, config.preview),
                )
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    AutonomyRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "autonomie"
    }
}
