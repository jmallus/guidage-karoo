package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.ContentScale
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.EdgeRenderer
import io.github.jmallus.guidage.ui.FieldPalette
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ plein écran « Tableau Edge » : voir [EdgeRenderer].
 *
 * Il a d'abord existé en deux champs, un clair et un sombre, choisis page par page. Le
 * coureur a préféré qu'il suive le thème réglé sur le Karoo, comme le reste de l'appareil :
 * clair de jour, sombre de nuit, sans avoir à changer de champ.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class EdgeDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    private val roadMapRepository: RoadMapRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    /**
     * Le fond de carte installé, pour la case du prochain virage.
     *
     * Pas de mention quand il ne donne rien : le champ n'a pas de carte où l'écrire, et la
     * case reste simplement sur « -- ».
     */
    private val roadSource = object : RoadSource {
        override fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment> =
            position?.let { roadMapRepository.roadsAround(it, radiusMeters) }.orEmpty()

        override fun notice(context: Context, position: GeoPoint?): String? = null
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                provider.snapshot,
                if (config.preview) apercu() else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings -> Triple(snapshot, rideData, settings) }
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (snapshot, rideData, settings) ->
                    EdgeModels.build(
                        context, snapshot, rideData, settings,
                        preview = config.preview && !snapshot.state.navigating,
                        roadSource = roadSource,
                    )
                }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    // Le thème est relu à chaque image : un passage du Karoo en mode nuit
                    // prend effet à la valeur suivante, sans relancer le champ.
                    EdgeRenderer.render(context, width, height, model, FieldPalette.estSombre(context))
                }
                .collect { bitmap ->
                    // FillBounds et non Fit : le champ clair pose son fond, et Fit laissait des
                    // bandes sur les côtés dès que la place réelle différait de celle annoncée.
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap, ContentScale.FillBounds) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Valeurs qui défilent dans le sélecteur de champs, comme sur le tableau de bord : un
     * champ figé sur « -- » ne dit rien de ce qu'il donnera en roulant.
     */
    private fun apercu(): Flow<RideData> = flow {
        val releves = PreviewData.edgeSamples(System.currentTimeMillis())
        var index = 0
        while (true) {
            emit(releves[index % releves.size])
            index++
            delay(APERCU_MS)
        }
    }

    companion object {
        const val TYPE_ID = "edge"
        private const val APERCU_MS = 2_000L
    }
}
