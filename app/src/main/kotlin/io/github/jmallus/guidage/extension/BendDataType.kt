package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.BendStatus
import io.github.jmallus.guidage.core.Bends
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteBend
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.ui.BendFieldModel
import io.github.jmallus.guidage.ui.BendMark
import io.github.jmallus.guidage.ui.BendRenderer
import io.github.jmallus.guidage.ui.BitmapField
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
 * Champ graphique « Virages » : ce que la descente réserve, sur la route redressée.
 *
 * En descente, le profil altimétrique ne dit rien : la pente est connue, elle est négative,
 * et ce n'est pas elle qui décide de la vitesse. Ce qui décide, c'est la géométrie — et elle
 * est déjà à bord, dans la polyligne de l'itinéraire.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class BendDataType(
    private val provider: GuidanceProvider,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Les virages de l'itinéraire courant, calculés une fois.
     *
     * Le tracé compte des dizaines de milliers de points ; le relire à chaque rafraîchissement
     * coûterait plus que tout le reste du champ réuni. Il ne change qu'avec l'itinéraire.
     */
    private var cachedKey: String? = null
    private var cached: List<RouteBend> = emptyList()

    @Synchronized
    private fun bends(route: Route): List<RouteBend> {
        val key = "${route.name}|${route.totalDistance.toInt()}|${route.path.size}"
        if (key != cachedKey) {
            cachedKey = key
            cached = Bends.of(route.path)
        }
        return cached
    }

    /** Le flux numérique donne la distance au virage le plus serré devant. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            provider.snapshot
                .map { snapshot -> Bends.sharpest(ahead(snapshot.state)) }
                .map { sharpest ->
                    if (sharpest == null) {
                        StreamState.NotAvailable
                    } else {
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to sharpest.distance),
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

            provider.snapshot
                .map { snapshot -> buildModel(context, snapshot, config) }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    BendRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun ahead(state: GuidanceState): List<BendStatus> {
        val route = state.route ?: return emptyList()
        val along = state.distanceAlongRoute ?: return emptyList()
        return Bends.ahead(bends(route), along, LOOKAHEAD_METERS)
    }

    private fun buildModel(context: Context, snapshot: GuidanceSnapshot, config: ViewConfig): BendFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val units = snapshot.units
        val label = context.getString(R.string.field_bends_label)
        val devant = ahead(state)
        if (devant.isEmpty()) {
            return BendFieldModel(
                label = label,
                emptyMessage = context.getString(
                    if (state.navigating) R.string.field_bends_none else R.string.field_no_route,
                ),
            )
        }

        val sharpest = Bends.sharpest(devant)
        return BendFieldModel(
            label = label,
            summary = context.getString(
                R.string.field_bends_summary,
                devant.size,
                Format.longDistance(LOOKAHEAD_METERS, units),
            ),
            marks = devant.map { status -> mark(status, sharpest) },
            ticks = ticks(units),
            callout = sharpest?.let { context.getString(calloutTitle(it.bend.radius)) },
            calloutValue = sharpest?.let { Format.distance(it.distance, units) },
            calloutColor = sharpest?.let { FieldPalette.bendColor(it.bend.radius) },
        )
    }

    /**
     * Une barre : sa place sur la bande, et sa longueur.
     *
     * La longueur suit l'inverse du rayon plutôt que le rayon lui-même — c'est ainsi qu'on
     * ralentit. Entre une épingle de douze mètres et une autre de quinze, l'œil doit voir la
     * différence ; entre cent et cent vingt, elle n'intéresse personne.
     */
    private fun mark(status: BendStatus, sharpest: BendStatus?): BendMark {
        val radius = status.bend.radius.coerceAtLeast(MIN_RADIUS_METERS)
        return BendMark(
            position = (status.distance / LOOKAHEAD_METERS).toFloat(),
            extent = (MIN_RADIUS_METERS / radius).toFloat(),
            direction = status.bend.direction,
            color = FieldPalette.bendColor(status.bend.radius),
            highlighted = status === sharpest,
        )
    }

    private fun ticks(units: Units): List<Pair<Float, String>> =
        listOf(1_000.0, 2_000.0, 3_000.0)
            .filter { it < LOOKAHEAD_METERS }
            .map { meters ->
                (meters / LOOKAHEAD_METERS).toFloat() to Format.longDistance(meters, units)
            }

    private fun calloutTitle(radius: Double): Int = when {
        radius < 15.0 -> R.string.field_bends_hairpin
        radius < 30.0 -> R.string.field_bends_tight
        else -> R.string.field_bends_bend
    }

    companion object {
        const val TYPE_ID = "virages"

        /** Portée de la bande (m) : ce qu'on parcourt en quelques minutes de descente. */
        private const val LOOKAHEAD_METERS = 3_000.0

        /** Rayon en deçà duquel la barre est déjà à sa longueur maximale (m). */
        private const val MIN_RADIUS_METERS = 12.0
    }
}
