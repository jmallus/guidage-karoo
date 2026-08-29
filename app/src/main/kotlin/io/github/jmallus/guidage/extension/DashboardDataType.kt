package io.github.jmallus.guidage.extension

import android.content.Context
import android.util.Log
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
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.karoo.RoadMapState
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.PreviewData
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

    private val roadSource: RoadSource = InstalledRoadMap()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // La seule fenêtre sur les dimensions réelles du champ : le simulateur tourne hors de
        // l'appareil et ne peut que les recopier. Deux façons de les lire — le journal pour
        // qui a un câble et adb, l'écran de configuration pour tous les autres.
        //     adb logcat -s GuidageExtension:D | grep "champ ouvert"
        Log.d(
            TAG,
            "champ ouvert : ${config.viewSize.first} × ${config.viewSize.second} px, " +
                "grille ${config.gridSize.first} × ${config.gridSize.second} sur 60, " +
                "corps natif ${config.textSize} sp, aperçu ${config.preview}",
        )
        val job = CoroutineScope(Dispatchers.IO).launch {
            // Dans la coroutine, et non au-dessus : la première lecture des préférences touche
            // le disque, et startView s'exécute sur le fil qui sert le système.
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                guidanceProvider.snapshot,
                if (config.preview) previewRide() else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings ->
                DashboardModels.build(
                    context = context,
                    snapshot = snapshot,
                    rideData = rideData,
                    settings = settings,
                    preview = config.preview && !snapshot.state.navigating,
                    roadSource = roadSource,
                )
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

    /** Le fond de carte installé sur l'appareil, offert au constructeur de modèle. */
    private inner class InstalledRoadMap : RoadSource {

        override fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment> =
            position?.let { roadMapRepository.roadsAround(it, radiusMeters) }.orEmpty()

        /**
         * Pourquoi la carte ne montre rien.
         *
         * Sans cette mention, l'absence de fond ressemble à une panne quel qu'en soit le motif,
         * et le coureur qui sort de la région couverte cherche un défaut là où il n'y en a pas.
         */
        override fun notice(context: Context, position: GeoPoint?): String = context.getString(
            when (roadMapRepository.state()) {
                RoadMapState.MISSING -> R.string.basemap_missing
                RoadMapState.UNREADABLE -> R.string.basemap_unreadable
                RoadMapState.READY ->
                    if (position != null && roadMapRepository.covers(position)) {
                        R.string.basemap_empty
                    } else {
                        R.string.basemap_out_of_area
                    }
            },
        )
    }

    companion object {
        const val TYPE_ID = "tableau"

        /** Le même que celui de l'extension : une seule étiquette à filtrer dans logcat. */
        private const val TAG = "GuidageExtension"

        /**
         * Cadence de défilement de l'aperçu.
         *
         * Le système ne rafraîchit une vue qu'environ une fois par seconde : en deçà, des
         * relevés seraient produits pour rien, et l'œil n'aurait pas le temps de lire.
         */
        private const val PREVIEW_INTERVAL_MS = 2_000L
    }
}
