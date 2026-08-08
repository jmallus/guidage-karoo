package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ClimbStatus
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.ClimbFieldModel
import io.github.jmallus.guidage.ui.ClimbRenderer
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ graphique « Prochaine côte » :
 *  - avant la côte : distance jusqu'à son pied, longueur, pente moyenne et dénivelé
 *  - dans la côte : distance et dénivelé restants jusqu'au sommet, avec barre de progression
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ClimbDataType(
    private val provider: GuidanceProvider,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le flux numérique expose la distance restante jusqu'à la côte (ou jusqu'au sommet
     * si elle est commencée), ce qui permet d'utiliser la donnée ailleurs sur le Karoo.
     */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            provider.snapshot
                .map { snapshot -> climbStatus(snapshot.state) }
                .map { status ->
                    if (status == null) {
                        StreamState.NotAvailable
                    } else {
                        val distance = if (status.onClimb) status.distanceToTop else status.distanceToStart
                        StreamState.Streaming(
                            DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to distance)),
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
            // Le champ dessine son propre en-tête (« Côte 2/5 »), celui du système est masqué.
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            provider.snapshot
                .map { snapshot -> buildModel(context, snapshot, config) }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    ClimbRenderer.render(width, height, model, config.alignment, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun climbStatus(state: GuidanceState): ClimbStatus? {
        val route = state.route ?: return null
        val along = state.distanceAlongRoute ?: return null
        return Guidance.climbStatus(route, along)
    }

    private fun buildModel(context: Context, snapshot: GuidanceSnapshot, config: ViewConfig): ClimbFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val status = climbStatus(state)
            ?: return ClimbFieldModel(
                primary = "—",
                caption = context.getString(
                    if (state.navigating) R.string.field_no_climb_ahead else R.string.field_no_route,
                ),
            )

        val units = snapshot.units
        return if (status.onClimb) {
            ClimbFieldModel(
                header = context.getString(R.string.field_climb_in_progress),
                primary = Format.distance(status.distanceToTop, units),
                secondaryTop = Format.grade(status.climb.grade),
                secondaryBottom = "+${Format.elevation(status.elevationToTop, units)}",
                progress = status.progress.toFloat(),
                accentGrade = status.climb.grade,
            )
        } else {
            ClimbFieldModel(
                header = context.getString(R.string.field_climb_number, status.number, status.totalClimbs),
                primary = Format.distance(status.distanceToStart, units),
                secondaryTop = Format.grade(status.climb.grade),
                secondaryBottom = climbSizeLabel(status, units),
                accentGrade = status.climb.grade,
            )
        }
    }

    private fun climbSizeLabel(status: ClimbStatus, units: Units): String =
        "${Format.distance(status.climb.length, units)} · +${Format.elevation(status.climb.totalElevation, units)}"

    companion object {
        const val TYPE_ID = "cote"
    }
}
