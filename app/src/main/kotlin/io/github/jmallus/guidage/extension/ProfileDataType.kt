package io.github.jmallus.guidage.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.jmallus.guidage.R
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
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.ProfileFieldModel
import io.github.jmallus.guidage.ui.ProfileRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ graphique « Profil à venir » : tout ce qui reste de l'itinéraire, coloré selon la
 * pente, avec les côtes surlignées.
 *
 * Tout ce qui reste, et non une portée choisie : l'échelle horizontale est comprimée au loin
 * (voir `FisheyeScale`), de sorte que la rampe dans trois cents mètres et le col de la
 * quatrième heure tiennent dans la même bande.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ProfileDataType(
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
                    ProfileRenderer.render(width, height, model, FieldPalette.of(context))
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
    ): ProfileFieldModel {
        val state = if (config.preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) return emptyModel(context, settings)

        // La position est arrondie pour éviter de redessiner à chaque mètre parcouru.
        val quantized = (along / POSITION_STEP_METERS).toInt() * POSITION_STEP_METERS
        val window = Guidance.profileToFinish(route, quantized)
        val units = snapshot.units
        val ascent = route.profile?.ascentBetween(window.start, window.end)

        return ProfileFieldModel(
            window = window,
            climbs = route.climbs,
            ascentLabel = ascent?.let { "+${Format.elevation(it, units)}" },
            rangeLabel = rangeLabel(window.distanceSpan, units),
            emptyMessage = context.getString(R.string.field_no_route),
            colorByGrade = settings.colorByGrade,
            units = units,
        )
    }

    private fun emptyModel(context: Context, settings: GuidageSettings) = ProfileFieldModel(
        window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
        emptyMessage = context.getString(R.string.field_no_route),
        colorByGrade = settings.colorByGrade,
    )

    private fun rangeLabel(span: Double, units: Units): String? {
        if (span <= 0.0) return null
        return Format.longDistance(span, units)
    }

    companion object {
        const val TYPE_ID = "profil"
        private const val POSITION_STEP_METERS = 10.0
    }
}
