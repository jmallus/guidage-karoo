package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Bends
import io.github.jmallus.guidage.core.ContextInputs
import io.github.jmallus.guidage.core.ContextSelector
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.RideContext
import io.github.jmallus.guidage.core.RideContexts
import io.github.jmallus.guidage.core.Resupply
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteBend
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BendMark
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.ContextBanner
import io.github.jmallus.guidage.ui.ContextFieldModel
import io.github.jmallus.guidage.ui.ContextRenderer
import io.github.jmallus.guidage.ui.ContextStat
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.KarooColors
import io.github.jmallus.guidage.ui.PreviewData
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
    private val selector = ContextSelector()
    private var lastUpdateMillis: Long? = null

    private var cachedKey: String? = null
    private var cachedBends: List<RouteBend> = emptyList()

    @Synchronized
    private fun bends(route: Route): List<RouteBend> {
        val key = "${route.name}|${route.totalDistance.toInt()}|${route.path.size}"
        if (key != cachedKey) {
            cachedKey = key
            cachedBends = Bends.of(route.path)
        }
        return cachedBends
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, rideDataProvider.data) { snapshot, rideData ->
                buildModel(context, snapshot, rideData, config)
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

    private fun buildModel(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        config: ViewConfig,
    ): ContextFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val units = snapshot.units
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return ContextFieldModel(emptyMessage = context.getString(R.string.field_no_route))
        }

        val nextResupply = Resupply.status(route, along, ResupplyTypes.ALL).next?.distance
        val nextBend = Bends
            .ahead(bends(route), along, RideContexts.BEND_METERS)
            .filter { it.bend.radius < SHARP_RADIUS_METERS }
            .minByOrNull { it.distance }

        val chosen = selector.update(
            deltaSeconds = elapsedSeconds(),
            candidate = RideContexts.candidate(
                ContextInputs(
                    onClimb = rideData.climb.onClimb,
                    gradePercent = rideData.grade,
                    resupplyDistance = nextResupply,
                    sharpBendDistance = nextBend?.distance,
                ),
            ),
        )

        val top = topRow(context, units, rideData)
        val lower = when (chosen) {
            RideContext.CLIMB -> climbZone(context, units, route, along, rideData)
            RideContext.DESCENT -> bendZone(context, units, route, along)
            RideContext.RESUPPLY -> resupplyZone(context, units, route, along)
            RideContext.CRUISE -> cruiseZone(context, units, rideData)
        }

        return lower.copy(
            topLeft = top.first,
            topRight = top.second,
            stateIndex = RideContext.entries.indexOf(chosen),
            stateCount = RideContext.entries.size,
        )
    }

    /** Le temps écoulé depuis le dernier relevé, borné : une reprise n'est pas une durée. */
    private fun elapsedSeconds(): Double {
        val now = clock()
        val previous = lastUpdateMillis
        lastUpdateMillis = now
        if (previous == null) return 0.0
        return ((now - previous) / 1_000.0).coerceIn(0.0, MAX_STEP_SECONDS)
    }

    private fun topRow(context: Context, units: Units, rideData: RideData): Pair<ContextStat?, ContextStat?> {
        val distance = rideData.distance?.let {
            ContextStat(context.getString(R.string.dashboard_label_distance), Format.longDistance(it, units))
        }
        val arrival = rideData.arrivalTime?.let {
            ContextStat(context.getString(R.string.dashboard_label_arrival), Format.clock(it))
        }
        return distance to arrival
    }

    private fun climbZone(
        context: Context,
        units: Units,
        route: Route,
        along: Double,
        rideData: RideData,
    ): ContextFieldModel {
        val status = Guidance.climbStatus(route, along)
        val profile = route.profile
        val bars = if (status != null && profile != null) {
            val from = maxOf(status.climb.startDistance, along)
            val to = status.climb.endDistance
            gradeBars(profile.slice(from, to))
        } else {
            emptyList()
        }
        return ContextFieldModel(
            stats = listOfNotNull(
                status?.let {
                    ContextStat(
                        context.getString(R.string.field_context_to_summit),
                        Format.distance(it.distanceToTop, units),
                    )
                },
                rideData.grade?.let {
                    ContextStat(
                        context.getString(R.string.dashboard_label_grade),
                        Format.shortGrade(it),
                    )
                },
            ),
            gradeBars = bars,
            progress = status?.progress?.toFloat(),
            caption = status?.let {
                context.getString(R.string.field_climb_number, it.number, it.totalClimbs)
            },
        )
    }

    /** Une pente par tranche du profil, pour la silhouette. */
    private fun gradeBars(points: List<ProfilePoint>): List<Double> {
        if (points.size < 2) return emptyList()
        return (1 until points.size).mapNotNull { index ->
            val run = points[index].distance - points[index - 1].distance
            if (run <= 0.0) null else (points[index].elevation - points[index - 1].elevation) / run * 100.0
        }
    }

    private fun bendZone(context: Context, units: Units, route: Route, along: Double): ContextFieldModel {
        val devant = Bends.ahead(bends(route), along, BEND_ZONE_METERS)
        val sharpest = Bends.sharpest(devant)
        return ContextFieldModel(
            banner = sharpest?.let {
                ContextBanner(
                    title = context.getString(
                        if (it.bend.radius < 15.0) R.string.field_bends_hairpin else R.string.field_bends_tight,
                    ),
                    value = Format.distance(it.distance, units),
                    color = FieldPalette.bendColor(it.bend.radius),
                )
            },
            bends = devant.map { status ->
                BendMark(
                    position = (status.distance / BEND_ZONE_METERS).toFloat(),
                    extent = (MIN_RADIUS_METERS / status.bend.radius.coerceAtLeast(MIN_RADIUS_METERS)).toFloat(),
                    direction = status.bend.direction,
                    color = FieldPalette.bendColor(status.bend.radius),
                    highlighted = status === sharpest,
                )
            },
        )
    }

    private fun resupplyZone(context: Context, units: Units, route: Route, along: Double): ContextFieldModel {
        val status = Resupply.status(route, along, ResupplyTypes.ALL)
        val next = status.next ?: return ContextFieldModel()
        return ContextFieldModel(
            // Le bleu que le Karoo réserve aux côtes est aussi le sien pour ce qui guide :
            // l'aplat dit « arrête-toi là » sans crier comme le ferait le rouge d'erreur.
            banner = ContextBanner(
                title = PoiLabels.label(context, next.poi),
                value = Format.distance(next.distance, units),
                color = KarooColors.HIGH_VIS_BLUE,
            ),
            stats = listOf(
                ContextStat(
                    context.getString(R.string.field_context_since_last),
                    status.sinceLast?.let { Format.longDistance(it, units) } ?: PLACEHOLDER,
                ),
            ),
        )
    }

    private fun cruiseZone(context: Context, units: Units, rideData: RideData): ContextFieldModel =
        ContextFieldModel(
            stats = listOfNotNull(
                rideData.speed?.let {
                    ContextStat(
                        context.getString(R.string.dashboard_label_speed),
                        "${Format.speed(it, units)} ${Format.speedUnit(units)}",
                    )
                },
                rideData.distanceRemaining?.let {
                    ContextStat(
                        context.getString(R.string.field_context_remaining),
                        Format.longDistance(it, units),
                    )
                },
            ),
        )

    companion object {
        const val TYPE_ID = "contexte"

        private const val PLACEHOLDER = "—"

        /** Rayon en deçà duquel un virage justifie de basculer en descente (m). */
        private const val SHARP_RADIUS_METERS = 60.0

        /** Portée de la bande de virages dans ce champ (m). */
        private const val BEND_ZONE_METERS = 1_500.0

        /** Rayon en deçà duquel la barre est déjà à sa longueur maximale (m). */
        private const val MIN_RADIUS_METERS = 12.0

        /** Pas de temps maximal retenu entre deux relevés (s). */
        private const val MAX_STEP_SECONDS = 5.0
    }
}
