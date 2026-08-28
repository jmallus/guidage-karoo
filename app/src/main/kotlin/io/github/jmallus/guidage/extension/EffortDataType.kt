package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.EffortBudget
import io.github.jmallus.guidage.core.EffortEstimate
import io.github.jmallus.guidage.core.EffortItem
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Pacing
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.EffortFieldModel
import io.github.jmallus.guidage.ui.EffortRenderer
import io.github.jmallus.guidage.ui.EffortSlice
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
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

    /** Le flux numérique donne le total restant, utilisable ailleurs sur le Karoo. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                estimate(snapshot.state, rideData)
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
                buildModel(context, snapshot.state, rideData, config)
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

    private fun estimate(state: GuidanceState, rideData: RideData): EffortEstimate? {
        val route = state.route ?: return null
        val along = state.distanceAlongRoute ?: return null
        val remaining = rideData.distanceRemaining ?: state.distanceRemaining ?: return null
        return EffortBudget.estimate(
            pace = rideData.pace,
            terrain = Pacing.terrain(route.profile, along, remaining),
            climbs = EffortBudget.remainingClimbs(route, along),
        )
    }

    private fun buildModel(
        context: Context,
        rawState: GuidanceState,
        rideData: RideData,
        config: ViewConfig,
    ): EffortFieldModel {
        val state = if (config.preview && !rawState.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            rawState
        }
        val data = if (config.preview && !rawState.navigating) PreviewData.effortSample else rideData

        val estimate = estimate(state, data)
            ?: return EffortFieldModel(
                label = context.getString(R.string.field_effort_label),
                emptyMessage = context.getString(
                    if (state.navigating) R.string.field_effort_waiting else R.string.field_no_route,
                ),
            )

        val total = estimate.kilojoules
        return EffortFieldModel(
            total = total.roundToInt().toString(),
            unit = context.getString(R.string.unit_kilojoule),
            label = context.getString(R.string.field_effort_label),
            slices = estimate.items.map { item -> slice(context, item, total) },
        )
    }

    /**
     * Une tranche de la barre, et sa ligne de détail quand elle en mérite une.
     *
     * Le roulant et les faux plats n'en portent pas : ce sont des restes, pas des rendez-vous.
     * Les côtes, si — c'est en bosses que le coureur pense son parcours, pas en mètres de
     * dénivelé cumulés.
     */
    private fun slice(context: Context, item: EffortItem, total: Double): EffortSlice {
        val share = if (total > 0.0) (item.kilojoules / total).toFloat() else 0f
        val number = item.climbNumber
        val grade = item.grade
        return if (number != null && grade != null) {
            EffortSlice(
                share = share,
                color = FieldPalette.gradeColor(grade),
                label = context.getString(R.string.field_effort_climb, number, Format.grade(grade)),
                value = context.getString(R.string.field_effort_kilojoules, item.kilojoules.roundToInt()),
            )
        } else {
            EffortSlice(share = share, color = FieldPalette.NEUTRAL)
        }
    }

    companion object {
        const val TYPE_ID = "effort"
    }
}
