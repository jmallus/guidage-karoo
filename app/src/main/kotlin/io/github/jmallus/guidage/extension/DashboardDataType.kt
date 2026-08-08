package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.DashboardModel
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.GraphPoi
import io.github.jmallus.guidage.ui.GuidanceZone
import io.github.jmallus.guidage.ui.MapModel
import io.github.jmallus.guidage.ui.MapPoi
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.RouteGraphModel
import io.github.jmallus.guidage.ui.Tile
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Champ plein écran : minicarte du parcours orientée cap en haut (ou profil altimétrique
 * en portrait, au choix) sur la hauteur de deux champs, et six valeurs chiffrées dessous.
 *
 * Un appui sur le champ fait défiler les échelles.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class DashboardDataType(
    private val guidanceProvider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                guidanceProvider.snapshot,
                rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings ->
                buildModel(context, snapshot, rideData, settings, config)
            }
                .distinctUntilChanged()
                .collect { model ->
                    val (width, height) = FieldSize.of(config)
                    val bitmap = DashboardRenderer.render(width, height, model)
                    val composed = glance.compose(context, DpSize.Unspecified) {
                        Dashboard(bitmap, clickable = !config.preview)
                    }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    @Composable
    private fun Dashboard(bitmap: android.graphics.Bitmap, clickable: Boolean) {
        var modifier = GlanceModifier.fillMaxSize()
        if (clickable) {
            modifier = modifier.clickable(onClick = actionRunCallback<ChangeGuidanceZoomAction>())
        }
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }

    private fun buildModel(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        settings: GuidageSettings,
        config: ViewConfig,
    ): DashboardModel {
        val preview = config.preview && !snapshot.state.navigating
        val state = if (preview) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }

        return DashboardModel(
            guidance = when (settings.guidanceZone) {
                GuidanceZoneType.MAP -> GuidanceZone.Map(mapModel(context, snapshot, state, settings, preview))
                GuidanceZoneType.PROFILE -> GuidanceZone.Profile(profileModel(context, state, settings))
            },
            tiles = tiles(context, snapshot, rideData),
        )
    }

    private fun mapModel(
        context: Context,
        snapshot: GuidanceSnapshot,
        state: GuidanceState,
        settings: GuidageSettings,
        preview: Boolean,
    ): MapModel {
        val route = state.route
        val location = if (preview) PreviewData.location else snapshot.location
        return MapModel(
            path = route?.path.orEmpty(),
            position = location?.position,
            heading = location?.heading,
            pois = route?.pois.orEmpty().mapNotNull { poi ->
                poi.position?.let { MapPoi(it, PoiLabels.label(context, poi)) }
            },
            rangeMeters = settings.mapRange.meters,
            emptyMessage = context.getString(
                if (route == null) R.string.field_no_route else R.string.field_waiting_for_position,
            ),
        )
    }

    private fun profileModel(
        context: Context,
        state: GuidanceState,
        settings: GuidageSettings,
    ): RouteGraphModel {
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return RouteGraphModel(
                window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
                position = 0.0,
                emptyMessage = context.getString(R.string.field_no_route),
                colorByGrade = settings.colorByGrade,
            )
        }

        val zoom = settings.graphZoom
        val window = Guidance.routeGraphWindow(route, along, zoom.lookaheadMeters)
        return RouteGraphModel(
            window = window,
            position = along,
            climbs = route.climbs,
            pois = route.pois.map { GraphPoi(it.distanceAlongRoute, PoiLabels.label(context, it)) },
            zoomLabel = zoomLabel(context, settings),
            colorByGrade = settings.colorByGrade,
        )
    }

    private fun zoomLabel(context: Context, settings: GuidageSettings): String {
        val lookahead = settings.graphZoom.lookaheadMeters
            ?: return context.getString(R.string.dashboard_zoom_whole_route)
        return "${(lookahead / 1_000).toInt()} km"
    }

    /**
     * Les six valeurs, dans l'ordre de lecture : les quatre mesures de l'effort d'abord,
     * puis ce qui reste à parcourir.
     */
    private fun tiles(context: Context, snapshot: GuidanceSnapshot, rideData: RideData): List<Tile> {
        val units = snapshot.units
        return listOf(
            Tile(
                label = context.getString(R.string.tile_speed),
                value = rideData.speed?.let { Format.speed(it, units) } ?: PLACEHOLDER,
                unit = Format.speedUnit(units),
            ),
            Tile(
                label = context.getString(R.string.tile_power),
                value = rideData.power?.toInt()?.toString() ?: PLACEHOLDER,
                unit = context.getString(R.string.unit_watt),
            ),
            Tile(
                label = context.getString(R.string.tile_heart_rate),
                value = rideData.heartRate?.toInt()?.toString() ?: PLACEHOLDER,
                unit = context.getString(R.string.unit_bpm),
            ),
            Tile(
                label = context.getString(R.string.tile_cadence),
                value = rideData.cadence?.toInt()?.toString() ?: PLACEHOLDER,
                unit = context.getString(R.string.unit_rpm),
            ),
            Tile(
                label = context.getString(R.string.tile_distance_remaining),
                value = rideData.distanceRemaining?.let { remainingValue(it, units) } ?: PLACEHOLDER,
                unit = remainingUnit(units),
            ),
            Tile(
                label = context.getString(R.string.tile_arrival),
                value = rideData.arrivalTime?.let { Format.clock(it) } ?: PLACEHOLDER,
            ),
        )
    }

    private fun remainingValue(meters: Double, units: Units): String =
        Format.longDistance(meters, units).substringBefore(' ')

    private fun remainingUnit(units: Units): String = when (units) {
        Units.METRIC -> "km"
        Units.IMPERIAL -> "mi"
    }

    companion object {
        const val TYPE_ID = "tableau"
        private const val PLACEHOLDER = "--"
    }
}
