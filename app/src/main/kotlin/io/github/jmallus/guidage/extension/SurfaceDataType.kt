package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.SurfaceClass
import io.github.jmallus.guidage.core.SurfaceRun
import io.github.jmallus.guidage.core.Surfaces
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.RoadStyle
import io.github.jmallus.guidage.ui.SurfaceBand
import io.github.jmallus.guidage.ui.SurfaceFieldModel
import io.github.jmallus.guidage.ui.SurfaceRenderer
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
import kotlin.math.abs

/**
 * Champ graphique « Revêtement » : ce que l'itinéraire réserve comme sol.
 *
 * Le fond de carte embarqué distingue déjà chemins, sentiers et voies vertes ; il ne
 * manquait que de poser le tracé dessus. Pour le gravel, c'est l'information qui manque
 * partout : non pas où l'on est, mais quand on quitte le bitume et pour combien de temps.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class SurfaceDataType(
    private val provider: GuidanceProvider,
    private val roadMapRepository: RoadMapRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le dernier appariement, et la position à laquelle il a été fait.
     *
     * L'appariement coûte cher : quelques milliers de points rangés dans une grille, puis
     * autant de recherches. Le refaire à chaque seconde n'apprendrait rien — à trente à
     * l'heure, cent mètres passent en douze secondes, et rien ne change avant.
     */
    private var cachedAt: Double? = null
    private var cachedKey: String? = null
    private var cachedRuns: List<SurfaceRun> = emptyList()

    @Synchronized
    private fun runs(state: GuidanceState, centre: GeoPoint?): List<SurfaceRun> {
        val route = state.route ?: return emptyList()
        val along = state.distanceAlongRoute ?: return emptyList()
        val key = "${route.name}|${route.totalDistance.toInt()}"
        val previous = cachedAt
        if (key == cachedKey && previous != null && abs(along - previous) < REFRESH_STEP_METERS) {
            return cachedRuns
        }

        // Le corridor est centré sur le coureur, non sur le départ : c'est devant lui qu'on
        // regarde, et le fond de carte se lit par fenêtres.
        val position = centre ?: pointAlong(route.path, along) ?: return emptyList()
        val segments = roadMapRepository.roadsAround(position, LOOKAHEAD_METERS)

        cachedKey = key
        cachedAt = along
        cachedRuns = Surfaces.ahead(
            path = route.path,
            segments = segments,
            distanceAlongRoute = along,
            lookahead = LOOKAHEAD_METERS,
        )
        return cachedRuns
    }

    /** Le point du tracé à une distance donnée du départ. */
    private fun pointAlong(path: List<GeoPoint>, distance: Double): GeoPoint? {
        if (path.isEmpty()) return null
        if (distance <= 0.0) return path.first()
        var travelled = 0.0
        for (index in 1 until path.size) {
            val step = Geo.distance(path[index - 1], path[index])
            if (travelled + step >= distance) return path[index]
            travelled += step
        }
        return path.last()
    }

    /** Le flux numérique donne la distance à la prochaine bascule de revêtement. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            provider.snapshot
                .map { snapshot -> nextChange(snapshot.state, snapshot.location?.position) }
                .map { distance ->
                    if (distance == null) {
                        StreamState.NotAvailable
                    } else {
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
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            provider.snapshot
                .map { snapshot -> buildModel(context, snapshot, config) }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    SurfaceRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    /** Distance à la fin de la portion courante, c'est-à-dire au prochain changement. */
    private fun nextChange(state: GuidanceState, centre: GeoPoint?): Double? {
        val along = state.distanceAlongRoute ?: return null
        val runs = runs(state, centre)
        if (runs.size < 2) return null
        val current = runs.firstOrNull { along < it.toDistance } ?: return null
        if (current === runs.last()) return null
        return (current.toDistance - along).coerceAtLeast(0.0)
    }

    private fun buildModel(context: Context, snapshot: GuidanceSnapshot, config: ViewConfig): SurfaceFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val units = snapshot.units
        val label = context.getString(R.string.field_surface_label)
        val runs = runs(state, snapshot.location?.position)
        if (runs.isEmpty()) {
            return SurfaceFieldModel(
                label = label,
                emptyMessage = context.getString(
                    if (state.navigating) R.string.field_surface_unknown else R.string.field_no_route,
                ),
            )
        }

        val along = state.distanceAlongRoute ?: 0.0
        val total = runs.sumOf { it.length }.takeIf { it > 0.0 } ?: return SurfaceFieldModel(label = label)
        val next = runs.getOrNull(1)

        return SurfaceFieldModel(
            label = if (next == null) label else context.getString(nextLabel(next.surface)),
            value = next?.let { Format.distance((it.fromDistance - along).coerceAtLeast(0.0), units) },
            caption = next?.let {
                context.getString(R.string.field_surface_then, Format.longDistance(it.length, units))
            },
            bands = runs.map { run ->
                SurfaceBand(
                    share = (run.length / total).toFloat(),
                    color = color(run.surface),
                    hatched = run.surface == SurfaceClass.TRAIL,
                    label = Format.longDistance(run.length, units),
                )
            },
        )
    }

    private fun nextLabel(surface: SurfaceClass): Int = when (surface) {
        SurfaceClass.TRAIL -> R.string.field_surface_next_trail
        SurfaceClass.CYCLEWAY -> R.string.field_surface_next_cycleway
        SurfaceClass.ROAD -> R.string.field_surface_next_road
        SurfaceClass.UNKNOWN -> R.string.field_surface_label
    }

    /** Les teintes du fond de carte, pour que les deux se lisent dans la même langue. */
    private fun color(surface: SurfaceClass): Int = when (surface) {
        SurfaceClass.ROAD -> RoadStyle.MINOR_ROAD
        SurfaceClass.TRAIL -> RoadStyle.TRAIL_ROAD
        SurfaceClass.CYCLEWAY -> RoadStyle.CYCLEWAY_ROAD
        SurfaceClass.UNKNOWN -> FieldPalette.NEUTRAL
    }

    companion object {
        const val TYPE_ID = "revetement"

        /** Portée du champ (m). */
        private const val LOOKAHEAD_METERS = 5_000.0

        /** Avance à partir de laquelle l'appariement est refait (m). */
        private const val REFRESH_STEP_METERS = 100.0
    }
}
