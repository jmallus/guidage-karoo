package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.SurfaceClass
import io.github.jmallus.guidage.core.SurfaceRun
import io.github.jmallus.guidage.core.Surfaces
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.RoadStyle
import io.github.jmallus.guidage.ui.SurfaceBand
import io.github.jmallus.guidage.ui.SurfaceFieldModel
import kotlin.math.abs

/**
 * Ce qu'affiche le champ « Revêtement ».
 *
 * Les voies arrivent par une fonction plutôt que par le dépôt de cartes : sur l'appareil elles
 * viennent du fond hors ligne, au banc d'essai du décor engendré. Le champ, lui, ignore d'où.
 * La classe porte l'appariement en cache et se garde donc d'une image à l'autre.
 */
class SurfaceModels(private val roads: (GeoPoint, Double) -> List<RoadSegment>) {

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
        val segments = roads(position, LOOKAHEAD_METERS)

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


    fun nextChange(state: GuidanceState, centre: GeoPoint?): Double? {
        val along = state.distanceAlongRoute ?: return null
        val runs = runs(state, centre)
        if (runs.size < 2) return null
        val current = runs.firstOrNull { along < it.toDistance } ?: return null
        if (current === runs.last()) return null
        return (current.toDistance - along).coerceAtLeast(0.0)
    }

    fun build(context: Context, snapshot: GuidanceSnapshot, preview: Boolean): SurfaceFieldModel {
        val state = if (preview && !snapshot.state.navigating) {
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

    private companion object {
        /** Portée du champ (m). */
        private const val LOOKAHEAD_METERS = 5_000.0

        /** Avance à partir de laquelle l'appariement est refait (m). */
        private const val REFRESH_STEP_METERS = 100.0
    }
}
