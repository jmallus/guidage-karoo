package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.Case
import io.github.jmallus.guidage.ui.DashboardRenderer
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
 * Les champs à deux données : deux cases du tableau de bord, détachées pour tenir dans une
 * case ordinaire du Karoo.
 *
 * Le tableau de bord occupe tout l'écran ; qui veut ses cases sur une page à lui, à côté des
 * champs natifs, n'avait que les champs du Karoo, sans les aplats de zone ni le schéma de la
 * transmission. Les paires retenues sont celles qu'on lit ensemble : la vitesse avec le
 * rapport qui la donne, le cœur avec la puissance qui le fait monter.
 */
enum class Paire(val typeId: String) {
    VITESSE_TRANSMISSION("vitesse-transmission"),
    COEUR_PUISSANCE("coeur-puissance"),
    ;

    /** Les deux cases, construites comme celles du tableau de bord. */
    fun cases(context: Context, units: Units, rideData: RideData): List<Case> = when (this) {
        VITESSE_TRANSMISSION -> listOf(
            Case.Chiffre(DashboardModels.effortTiles(context, units, rideData)[0]),
            // « RAPPORT » plutôt que « TRANSMISSION » : dans la demi-case d'un champ standard, le
            // mot entier se coupait, et c'est bien le rapport engagé que le schéma montre.
            Case.Schema(
                DashboardModels.drivetrainModel(context, rideData)
                    .copy(label = context.getString(R.string.pair_label_gear)),
            ),
        )
        COEUR_PUISSANCE -> listOf(
            Case.Chiffre(DashboardModels.heartRateTile(context, rideData)),
            Case.Chiffre(DashboardModels.effortTiles(context, units, rideData)[2]),
        )
    }
}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PaireDataType(
    private val paire: Paire,
    private val guidanceProvider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
) : DataTypeImpl(extension, paire.typeId) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(dataTypeId, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            combine(
                guidanceProvider.snapshot,
                if (config.preview) apercu() else rideDataProvider.data,
            ) { snapshot, rideData -> snapshot.units to rideData }
                // Les relevés se lisent au plus une fois par seconde, avant de bâtir le
                // modèle : le bâtir à chaque valeur reçue — une vingtaine par seconde —
                // coûtait plus que le dessin qu'on plafonnait déjà.
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (units, rideData) -> paire.cases(context, units, rideData) }
                .distinctUntilChanged()
                .map { cases ->
                    val (width, height) = FieldSize.of(config)
                    DashboardRenderer.renderPaire(context, width, height, cases, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    /** Des relevés qui défilent dans le sélecteur de champs, comme pour le tableau de bord. */
    private fun apercu(): Flow<RideData> = flow {
        val samples = PreviewData.rideSamples(System.currentTimeMillis())
        var index = 0
        while (true) {
            emit(samples[index % samples.size])
            index++
            delay(APERCU_MS)
        }
    }

    private companion object {
        const val APERCU_MS = 1_500L
    }
}
