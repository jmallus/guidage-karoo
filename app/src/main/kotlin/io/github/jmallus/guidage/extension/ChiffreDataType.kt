package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.ui.Alignement
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.Chiffre
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Les champs « chiffre seul » : la valeur, en grand, et rien d'autre.
 *
 * Demandés pour se poser sur la page carte du Karoo, où chaque point de surface compte :
 * ni titre, ni icône, ni aplat de zone — la zone se lit à la couleur du chiffre. Le fond
 * reste transparent, c'est le Karoo qui le peint. Les valeurs sont celles des cases du
 * tableau de bord, construites par les mêmes fonctions.
 */
enum class ChiffreSeul(val typeId: String) {
    VITESSE("chiffre-vitesse"),
    PUISSANCE("chiffre-puissance"),
    COEUR("chiffre-coeur"),
    RESTANT("chiffre-restant"),
    RAPPORT("chiffre-rapport"),
    ;

    fun chiffre(context: Context, units: Units, rideData: RideData): Chiffre = when (this) {
        VITESSE -> DashboardModels.effortTiles(context, units, rideData)[0].chiffre()
        PUISSANCE -> DashboardModels.effortTiles(context, units, rideData)[2].chiffre()
        COEUR -> DashboardModels.heartRateTile(context, rideData).chiffre()
        RESTANT -> DashboardModels.remainingTile(context, rideData, units).chiffre()
        // Les dentures quand le groupe les donne (« 50×24 »), sinon les rangs (« 2×8 »).
        RAPPORT -> rideData.drivetrain.let { t ->
            val avant = t.frontTeeth?.takeIf { it > 0 } ?: t.front
            val arriere = t.rearTeeth?.takeIf { it > 0 } ?: t.rear
            Chiffre(if (avant != null && arriere != null) "$avant×$arriere" else arriere?.toString() ?: "--")
        }
    }

    /** La valeur d'une case du tableau de bord, sa couleur de zone passant au chiffre. */
    private fun Tile.chiffre() = Chiffre(valeur = value, decimale = decimal, couleur = background)
}

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ChiffreDataType(
    private val donnee: ChiffreSeul,
    private val guidanceProvider: GuidanceProvider,
    private val rideDataProvider: RideDataProvider,
    extension: String,
) : DataTypeImpl(extension, donnee.typeId) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(dataTypeId, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            // Le chiffre se range comme l'utilisateur l'a réglé pour ce champ sur le Karoo.
            val alignement = when (config.alignment) {
                ViewConfig.Alignment.LEFT -> Alignement.GAUCHE
                ViewConfig.Alignment.CENTER -> Alignement.CENTRE
                ViewConfig.Alignment.RIGHT -> Alignement.DROITE
            }
            combine(
                guidanceProvider.snapshot,
                if (config.preview) apercu() else rideDataProvider.data,
            ) { snapshot, rideData -> snapshot.units to rideData }
                .auPlusUneFoisPar(PERIODE_RENDU_MS)
                .map { (units, rideData) -> donnee.chiffre(context, units, rideData) }
                .distinctUntilChanged()
                .map { chiffre ->
                    val (width, height) = FieldSize.of(config)
                    DashboardRenderer.renderChiffre(width, height, chiffre, alignement, FieldPalette.of(context))
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
