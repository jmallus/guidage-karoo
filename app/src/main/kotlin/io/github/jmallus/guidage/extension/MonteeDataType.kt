package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.ProfileRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ graphique « Profil de montée » : le bandeau du bas du tableau de bord, seul.
 *
 * Il ne répond pas à la question du « Profil à venir » — ce qui reste de la journée, à
 * l'échelle comprimée — mais à celle du bandeau : ce qui arrive, sur la portée réglée, et
 * dans une côte la côte entière du pied au sommet, recalée sur le ClimbPro du Karoo. Le
 * construire par le même chemin que le bandeau garantit qu'il en dit exactement autant.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class MonteeDataType(
    private val provider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(
                provider.snapshot,
                if (config.preview) flowOf(RideData()) else rideDataProvider.data,
                settingsRepository.settings,
            ) { snapshot, rideData, settings -> Triple(snapshot, rideData, settings) }
                // Les relevés se lisent au plus une fois par seconde, avant de bâtir le
                // modèle : le bâtir à chaque valeur reçue — une vingtaine par seconde —
                // coûtait plus que le dessin qu'on plafonnait déjà.
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (snapshot, rideData, settings) ->
                    DashboardModels.profileBand(
                        context, snapshot, settings,
                        preview = config.preview && !snapshot.state.navigating,
                        rideData = rideData,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    ProfileRenderer.render(width, height, model, FieldPalette.of(context), corpsTextes = CORPS_TEXTES)
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "montee"

        /**
         * Les textes du champ un sixième plus petits que ceux du bandeau : la silhouette y
         * gagne un cinquième de hauteur (68 → 82 px dans une case de 160), demandé en roulant.
         * Le bandeau du tableau de bord garde les siens.
         */
        const val CORPS_TEXTES = 0.83f
    }
}
