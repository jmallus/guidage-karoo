package io.github.jmallus.guidage.extension

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.karoo.RoadMapState
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.CentreGuidage
import io.github.jmallus.guidage.ui.GuidageModel
import io.github.jmallus.guidage.ui.GuidageRenderer
import io.github.jmallus.guidage.ui.PreviewData
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ plein écran « Tableau de guidage » : l'écran qui suit la route.
 *
 * Le second tableau de bord, à côté du premier et non à sa place : celui-ci donne la priorité
 * au guidage. Voir [GuidageModels] pour ce qui occupe le milieu de l'écran, et
 * [GuidageRenderer] pour son dessin.
 *
 * Un appui sur le haut de l'écran change l'échelle de la carte, un appui sur le bas la portée
 * du profil, comme sur le tableau de bord.
 *
 * À l'approche d'un carrefour où il faut tourner, le champ bipe deux fois : à
 * [BIP_LOIN] mètres, puis à [BIP_PRES]. Les yeux sont sur la route à ce moment-là, et c'est
 * l'oreille qui dit de regarder l'écran.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class GuidageDataType(
    private val guidanceProvider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    private val roadMapRepository: RoadMapRepository,
    /** Remet un effet au Karoo — les bips d'approche. */
    private val dispatch: (KarooEffect) -> Unit,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            val bips = Bips()
            combine(
                guidanceProvider.snapshot,
                if (config.preview) previewRide() else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings -> Triple(snapshot, rideData, settings) }
                // Au plus une fois par seconde, avant de bâtir le modèle : voir le tableau de bord.
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (snapshot, rideData, settings) ->
                    GuidageModels.build(
                        context = context,
                        snapshot = snapshot,
                        rideData = rideData,
                        settings = settings,
                        preview = config.preview && !snapshot.state.navigating,
                        roadSource = InstalledRoadMap(),
                    )
                }
                .distinctUntilChanged()
                .collect { model ->
                    if (!config.preview) bips.annoncer(model)
                    val (width, height) = FieldSize.of(config)
                    val bitmap = GuidageRenderer.render(width, height, model)
                    // Dessiné d'un seul tenant puis coupé en deux, comme le tableau de bord : le
                    // haut change l'échelle de la carte, le bas la portée du profil.
                    val coupure = GuidageRenderer.bandeauHaut(height).toInt().coerceIn(1, height - 1)
                    val densite = context.resources.displayMetrics.density
                    val composed = glance.compose(context, DpSize.Unspecified) {
                        Column(modifier = GlanceModifier.fillMaxSize()) {
                            Moitie(
                                Bitmap.createBitmap(bitmap, 0, 0, width, coupure),
                                (coupure / densite).dp,
                                if (config.preview) null else actionRunCallback<ChangeMapZoomAction>(),
                            )
                            Moitie(
                                Bitmap.createBitmap(bitmap, 0, coupure, width, height - coupure),
                                ((height - coupure) / densite).dp,
                                if (config.preview) null else actionRunCallback<ChangeProfileZoomAction>(),
                            )
                        }
                    }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    @Composable
    private fun Moitie(bitmap: Bitmap, hauteur: Dp, onClick: Action?) {
        var modifier = GlanceModifier.fillMaxWidth().height(hauteur)
        if (onClick != null) modifier = modifier.clickable(onClick = onClick)
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }

    /**
     * Les bips d'approche, une fois chacun par carrefour.
     *
     * Le carrefour se reconnaît à sa place sur le tracé, arrondie : d'une recherche à l'autre
     * elle bouge de quelques mètres, et un carrefour qui changerait d'identité rebiperait.
     */
    private inner class Bips {
        private var carrefour: Long? = null
        private var loin = false
        private var pres = false

        fun annoncer(model: GuidageModel) {
            val c = model.centre as? CentreGuidage.Carrefour ?: return
            val repere = (c.repere / IDENTITE_METRES).toLong()
            if (repere != carrefour) {
                carrefour = repere
                loin = false
                pres = false
            }
            if (!pres && c.metres <= BIP_PRES) {
                pres = true
                loin = true
                dispatch(PlayBeepPattern(listOf(TON, SILENCE, TON, SILENCE, TON)))
            } else if (!loin && c.metres <= BIP_LOIN) {
                loin = true
                dispatch(PlayBeepPattern(listOf(TON, SILENCE, TON)))
            }
        }
    }

    private fun previewRide(): Flow<RideData> = flow {
        val samples = PreviewData.rideSamples(System.currentTimeMillis())
        var index = 0
        while (true) {
            emit(samples[index % samples.size])
            index++
            delay(PREVIEW_INTERVAL_MS)
        }
    }

    /** Le fond de carte installé sur l'appareil, comme pour le tableau de bord. */
    private inner class InstalledRoadMap : RoadSource {

        override fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment> =
            position?.let { roadMapRepository.roadsAround(it, radiusMeters) }.orEmpty()

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
        const val TYPE_ID = "route"

        /** Distances des deux bips d'approche (m). */
        private const val BIP_LOIN = 150.0
        private const val BIP_PRES = 40.0

        /** Pas sur lequel s'arrondit la place d'un carrefour pour le reconnaître (m). */
        private const val IDENTITE_METRES = 50.0

        private val TON = PlayBeepPattern.Tone(frequency = 3_000, durationMs = 150)
        private val SILENCE = PlayBeepPattern.Tone(frequency = null, durationMs = 100)

        private const val PREVIEW_INTERVAL_MS = 2_000L
    }
}
