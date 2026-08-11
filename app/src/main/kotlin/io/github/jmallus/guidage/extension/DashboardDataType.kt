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
import io.github.jmallus.guidage.core.Zones
import kotlin.math.roundToInt
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.ClimbBandModel
import io.github.jmallus.guidage.ui.DashboardModel
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.FieldPalette
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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
    private val roadMapRepository: RoadMapRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                guidanceProvider.snapshot,
                if (config.preview) previewRide() else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings ->
                buildModel(context, snapshot, rideData, settings, config)
            }
                .distinctUntilChanged()
                .collect { model ->
                    val (width, height) = FieldSize.of(config)
                    val bitmap = DashboardRenderer.render(context, width, height, model)
                    val composed = glance.compose(context, DpSize.Unspecified) {
                        Dashboard(bitmap, clickable = !config.preview)
                    }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Valeurs qui défilent dans le sélecteur de champs.
     *
     * Un champ figé sur « -- » ne dit rien de ce qu'il donnera en roulant : en faisant
     * tourner quelques relevés plausibles, le coureur voit la mise en page réelle et la
     * coloration par zone avant de poser le champ sur une page.
     */
    private fun previewRide(): Flow<RideData> = flow {
        val samples = PreviewData.rideSamples(System.currentTimeMillis())
        var index = 0
        while (true) {
            emit(samples[index % samples.size])
            index++
            delay(PREVIEW_INTERVAL_MS)
        }
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

        val units = snapshot.units
        return DashboardModel(
            guidance = when (settings.guidanceZone) {
                GuidanceZoneType.MAP ->
                    GuidanceZone.Map(mapModel(context, snapshot, state, settings, preview))
                GuidanceZoneType.PROFILE -> GuidanceZone.Profile(profileModel(context, state, settings))
            },
            tiles = effortTiles(context, units, rideData),
            sideTile = gradeTile(context, rideData),
            footerTiles = footerTiles(context, units, rideData),
            climbBand = climbBand(state),
            palette = FieldPalette.of(context),
        )
    }

    /**
     * Profil de la côte en cours, ou de la prochaine quand elle approche.
     *
     * Les côtes sont celles que le Karoo a lui-même identifiées sur l'itinéraire ; on ne
     * cherche pas à en détecter d'autres. Au-delà de [CLIMB_BAND_LOOKAHEAD] le bandeau
     * disparaît : une côte à trente kilomètres n'a pas à prendre de la place à l'écran.
     */
    private fun climbBand(state: GuidanceState): ClimbBandModel? {
        val route = state.route ?: return null
        val along = state.distanceAlongRoute ?: return null
        val status = Guidance.climbStatus(route, along) ?: return null
        if (!status.onClimb && status.distanceToStart > CLIMB_BAND_LOOKAHEAD) return null

        val climb = status.climb
        val window = Guidance.profileWindow(route, climb.startDistance, lookahead = climb.length)
        if (window.isEmpty) return null

        return ClimbBandModel(
            window = window,
            position = along,
            positionElevation = route.profile?.elevationAt(along),
            label = "${status.number}/${status.totalClimbs}",
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
        val position = location?.position
        return MapModel(
            // On lit un peu au-delà du cadre : en cap en haut, la fenêtre tourne avec le
            // coureur et ses coins balaient plus loin que la portée annoncée.
            roads = position?.let {
                roadMapRepository.roadsAround(it, settings.mapRange.meters * ROADS_RADIUS_FACTOR)
            }.orEmpty(),
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
     * Colonne de gauche : les quatre mesures de l'effort, dans l'ordre demandé.
     *
     * Vitesse, puissance et fréquence cardiaque reçoivent un aplat de fond qui situe
     * l'effort d'un coup d'œil : vert ou rouge selon la moyenne pour la vitesse, couleur de
     * zone Karoo pour les deux autres. La cadence n'a pas de zones et reste sur fond noir.
     */
    private fun effortTiles(context: Context, units: Units, rideData: RideData): List<Tile> = listOf(
        Tile(
            label = context.getString(R.string.dashboard_label_speed),
            value = rideData.speed?.let { Format.speed(it, units) } ?: PLACEHOLDER,
            background = rideData.speed?.let { Zones.speedColor(it, rideData.averageSpeed) },
            icon = R.drawable.ic_speed,
        ).splitDecimal(),
        Tile(
            label = context.getString(R.string.dashboard_label_power),
            value = rideData.power?.toInt()?.toString() ?: PLACEHOLDER,
            background = rideData.power?.let { Zones.powerColor(it, rideData.powerZones) },
            icon = R.drawable.ic_power,
        ),
        Tile(
            label = context.getString(R.string.dashboard_label_heart_rate),
            value = rideData.heartRate?.toInt()?.toString() ?: PLACEHOLDER,
            background = rideData.heartRate?.let { Zones.heartRateColor(it, rideData.heartRateZones) },
            icon = R.drawable.ic_heart_rate,
        ),
        Tile(
            label = context.getString(R.string.dashboard_label_cadence),
            value = rideData.cadence?.toInt()?.toString() ?: PLACEHOLDER,
            icon = R.drawable.ic_cadence,
        ),
    )

    /** Case sous le guidage : la pente instantanée. */
    private fun gradeTile(context: Context, rideData: RideData): Tile = Tile(
        label = context.getString(R.string.dashboard_label_grade),
        value = rideData.grade?.roundToInt()?.toString() ?: PLACEHOLDER,
        suffix = "%",
        icon = R.drawable.ic_grade,
    )

    /** Ligne du bas : ce qu'il reste à parcourir. */
    private fun footerTiles(context: Context, units: Units, rideData: RideData): List<Tile> = listOf(
        Tile(
            label = context.getString(
                R.string.dashboard_label_remaining,
                remainingUnit(units).uppercase(),
            ),
            value = rideData.distanceRemaining?.let { remainingValue(it, units) } ?: PLACEHOLDER,
            icon = R.drawable.ic_distance_remaining,
        ).splitDecimal(),
        Tile(
            label = context.getString(R.string.dashboard_label_arrival),
            value = rideData.arrivalTime?.let { Format.clock(it) } ?: PLACEHOLDER,
            icon = R.drawable.ic_arrival,
        ),
    )

    /**
     * Renvoie la décimale au suffixe, écrit en plus petit.
     *
     * « 38,5 » devient « 38, » et « 5 » : les chiffres qui portent l'information gardent
     * leur pleine hauteur, la décimale suit sans manger la case.
     */
    private fun Tile.splitDecimal(): Tile {
        val separator = value.indexOfLast { it == '.' || it == ',' }
        if (separator < 0 || separator == value.lastIndex) return this
        return copy(value = value.substring(0, separator + 1), suffix = value.substring(separator + 1))
    }

    /**
     * Distance restante sans son unité. Au-delà de 100, la décimale est abandonnée :
     * « 123 » tient dans la case là où « 123,4 » obligerait à rapetisser les chiffres.
     */
    private fun remainingValue(meters: Double, units: Units): String {
        val value = when (units) {
            Units.METRIC -> meters / 1_000
            Units.IMPERIAL -> meters / METERS_PER_MILE
        }
        return if (value >= 100) {
            value.roundToInt().toString()
        } else {
            Format.longDistance(meters, units).substringBefore(' ')
        }
    }

    private fun remainingUnit(units: Units): String = when (units) {
        Units.METRIC -> "km"
        Units.IMPERIAL -> "mi"
    }

    companion object {
        const val TYPE_ID = "tableau"
        private const val PLACEHOLDER = "--"
        private const val METERS_PER_MILE = 1609.344

        /**
         * Cadence de défilement de l'aperçu.
         *
         * Le système ne rafraîchit une vue qu'environ une fois par seconde : en deçà, des
         * relevés seraient produits pour rien, et l'œil n'aurait pas le temps de lire.
         */
        private const val PREVIEW_INTERVAL_MS = 2_000L

        /** Distance au pied à partir de laquelle le bandeau de montée apparaît (m). */
        private const val CLIMB_BAND_LOOKAHEAD = 5_000.0

        /** Rayon de lecture du fond de carte, en multiples de la portée affichée. */
        private const val ROADS_RADIUS_FACTOR = 1.6
    }
}
