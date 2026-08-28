package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Bends
import io.github.jmallus.guidage.core.ContextInputs
import io.github.jmallus.guidage.core.ContextSelector
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Resupply
import io.github.jmallus.guidage.core.RideContext
import io.github.jmallus.guidage.core.RideContexts
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteBend
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.ui.BendMark
import io.github.jmallus.guidage.ui.ContextBanner
import io.github.jmallus.guidage.ui.ContextFieldModel
import io.github.jmallus.guidage.ui.ContextStat
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.KarooColors
import io.github.jmallus.guidage.ui.PreviewData

/**
 * Ce qu'affiche le champ « Suivant la sortie ».
 *
 * Extrait du champ pour que le banc d'essai montre ce que montrera l'appareil. La classe porte
 * l'état qui fait tenir la bascule — l'hystérésis du sélecteur et l'instant du dernier relevé —
 * et se garde donc d'une image à l'autre. L'horloge est passée pour que ce banc puisse la
 * remplacer par la sienne.
 */
class ContextModels(private val clock: () -> Long = System::currentTimeMillis) {

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

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        preview: Boolean,
        /** Ce qui compte comme ravitaillement : c'est un réglage, non une constante. */
        resupplyTypes: Set<String> = ResupplyTypes.ALL,
    ): ContextFieldModel {
        val state = if (preview && !snapshot.state.navigating) {
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

        val nextResupply = Resupply.status(route, along, resupplyTypes).next?.distance
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
            RideContext.RESUPPLY -> resupplyZone(context, units, route, along, resupplyTypes)
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

    private fun resupplyZone(
        context: Context,
        units: Units,
        route: Route,
        along: Double,
        resupplyTypes: Set<String>,
    ): ContextFieldModel {
        val status = Resupply.status(route, along, resupplyTypes)
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

    private companion object {
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
