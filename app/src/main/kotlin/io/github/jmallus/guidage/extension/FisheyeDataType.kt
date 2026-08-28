package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.FisheyeScale
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.BitmapField
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.FisheyeFieldModel
import io.github.jmallus.guidage.ui.FisheyeRenderer
import io.github.jmallus.guidage.ui.FisheyeTick
import io.github.jmallus.guidage.ui.PreviewData
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Champ graphique « Profil jusqu'à l'arrivée » : tout ce qui reste, sur une échelle qui
 * dilate le proche et comprime le lointain.
 *
 * Il ne remplace pas « Profil à venir » et ne lui emprunte pas sa portée : celui-là montre
 * une fenêtre choisie, à l'échelle droite, et c'est ce qu'on veut quand on suit une côte.
 * Celui-ci répond à l'autre question, celle qu'on se pose une fois la côte passée : qu'est-ce
 * qu'il reste, et où sont les prochaines. Les deux se posent sur la même page.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class FisheyeDataType(
    private val provider: GuidanceProvider,
    private val settingsRepository: SettingsRepository,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            FieldReportStore(context).record(TYPE_ID, config)
            emitter.onNext(UpdateGraphicConfig(showHeader = false))

            combine(provider.snapshot, settingsRepository.settings) { snapshot, settings ->
                buildModel(context, snapshot, settings, config)
            }
                .distinctUntilChanged()
                .map { model ->
                    val (width, height) = FieldSize.of(config)
                    FisheyeRenderer.render(width, height, model, FieldPalette.of(context))
                }
                .collect { bitmap ->
                    val composed = glance.compose(context, DpSize.Unspecified) { BitmapField(bitmap) }
                    emitter.updateView(composed.remoteViews)
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun buildModel(
        context: Context,
        snapshot: GuidanceSnapshot,
        settings: GuidageSettings,
        config: ViewConfig,
    ): FisheyeFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) return emptyModel(context, settings)

        // La position est arrondie pour ne pas redessiner à chaque mètre parcouru. Le pas est
        // plus large que celui du profil à échelle droite : ici le coureur reste au bord
        // gauche et c'est tout le dessin qui se recalcule.
        val quantized = (along / POSITION_STEP_METERS).toInt() * POSITION_STEP_METERS
        // La portée demandée dépasse tout itinéraire concevable ; la fenêtre la ramène
        // d'elle-même à la longueur du parcours.
        val window = Guidance.profileWindow(route, quantized, lookahead = TO_THE_FINISH_METERS)
        if (window.isEmpty) return emptyModel(context, settings)

        val remaining = window.distanceSpan
        val units = snapshot.units
        val ascent = route.profile?.ascentBetween(window.start, window.end)
        val scale = FisheyeScale.of(remaining)

        return FisheyeFieldModel(
            window = window,
            scale = scale,
            ticks = ticks(remaining, units),
            hingeAheadMeters = FisheyeScale.DEFAULT_FINE_METERS,
            remainingLabel = Format.longDistance(remaining, units),
            ascentLabel = ascent?.let { "+${Format.elevation(it, units)}" },
            emptyMessage = context.getString(R.string.field_no_route),
            colorByGrade = settings.colorByGrade,
        )
    }

    /**
     * Les graduations, écrites dans les unités du coureur.
     *
     * Sous le kilomètre on les donne en mètres : « 500 » lu à côté de « 5 » et de « 20 »
     * dirait cinq cents kilomètres. L'unité n'est portée que par la dernière, la seule qui
     * ait la place de la recevoir.
     */
    private fun ticks(remaining: Double, units: Units): List<FisheyeTick> {
        val candidates = FisheyeScale.ticks(remaining, units)
        return candidates.mapIndexed { index, meters ->
            FisheyeTick(
                aheadMeters = meters,
                label = tickLabel(meters, units, withUnit = index == candidates.lastIndex),
            )
        }
    }

    /**
     * Le libellé d'une graduation.
     *
     * Seule la dernière porte son unité : répétée sept fois sur une largeur de champ, elle
     * mangerait la place des chiffres qu'elle qualifie. Sous le kilomètre — ou sous le mille
     * — on descend d'un cran d'unité, faute de quoi « 0,5 » se lirait à côté de « 20 ».
     */
    private fun tickLabel(meters: Double, units: Units, withUnit: Boolean): String = when (units) {
        Units.METRIC ->
            if (meters < 1_000.0) {
                "${meters.roundToInt()} m"
            } else {
                "${(meters / 1_000.0).roundToInt()}" + if (withUnit) " km" else ""
            }

        Units.IMPERIAL -> {
            val miles = meters / Format.METERS_PER_MILE
            val written = if (miles < 1.0) String.format(Locale.getDefault(), "%.1f", miles) else "${miles.roundToInt()}"
            written + if (withUnit) " mi" else ""
        }
    }

    private fun emptyModel(context: Context, settings: GuidageSettings) = FisheyeFieldModel(
        window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
        emptyMessage = context.getString(R.string.field_no_route),
        colorByGrade = settings.colorByGrade,
    )

    companion object {
        const val TYPE_ID = "profil-arrivee"

        /** Pas d'arrondi de la position (m). */
        private const val POSITION_STEP_METERS = 50.0

        /** Portée demandée à la fenêtre : plus longue que tout itinéraire (m). */
        private const val TO_THE_FINISH_METERS = 1_000_000.0
    }
}
